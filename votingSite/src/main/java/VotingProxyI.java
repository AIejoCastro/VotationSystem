//
// VotingProxyI - Implementación del proxy que recibe votos de VotingMachine
//

import Proxy.*;
import Demo.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class VotingProxyI implements VotingProxy {
    private final VotationPrx votationTarget;
    private final ReliableMessagingService messagingService;
    private final com.zeroc.IceGrid.QueryPrx query;
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public VotingProxyI(VotationPrx votationTarget, ReliableMessagingService messagingService, com.zeroc.IceGrid.QueryPrx query) {
        this.votationTarget = votationTarget;
        this.messagingService = messagingService;
        this.query = query;
    }

    @Override
    public String submitVote(String citizenId, String candidateId, com.zeroc.Ice.Current current)
            throws VotingSystemUnavailableException, InvalidVoteException {

        String timestamp = LocalDateTime.now().format(timeFormatter);
        String clientEndpoint = current.con != null ? current.con.toString() : "unknown";

        System.out.println("[" + timestamp + "] [VotingSite-Proxy] Voto recibido desde VotingMachine");
        System.out.println("[" + timestamp + "] [VotingSite-Proxy] Cliente: " + clientEndpoint);
        System.out.println("[" + timestamp + "] [VotingSite-Proxy] Ciudadano: " + citizenId + " -> Candidato: " + candidateId);

        // Validación básica
        if (citizenId == null || citizenId.trim().isEmpty()) {
            throw new InvalidVoteException("Cédula del ciudadano no puede estar vacía");
        }

        if (candidateId == null || candidateId.trim().isEmpty()) {
            throw new InvalidVoteException("ID del candidato no puede estar vacío");
        }

        // Obtener proxy actualizado del servidor de votación
        VotationPrx currentTarget = getCurrentVotationProxy();

        try {
            long startTime = System.currentTimeMillis();
            String ackId = currentTarget.sendVote(citizenId.trim(), candidateId.trim());
            long latency = System.currentTimeMillis() - startTime;

            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] [VotingSite-Proxy] Voto enviado exitosamente");
            System.out.println("[" + timestamp + "] [VotingSite-Proxy] ACK recibido: " + ackId + " (" + latency + "ms)");
            System.out.println("[" + timestamp + "] [VotingSite-Proxy] Retornando confirmación a VotingMachine");

            String voteKey = citizenId + "|" + candidateId;
            messagingService.confirmVoteACK(voteKey, ackId, latency);

            return ackId;

        } catch (AlreadyVotedException e) {
            long latency = System.currentTimeMillis() - System.currentTimeMillis();
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] [VotingSite-Proxy] Ciudadano ya votó - ACK: " + e.ackId);

            String voteKey = citizenId + "|" + candidateId;
            messagingService.confirmVoteACK(voteKey, e.ackId, latency);

            // Retornar el ACK existente, no lanzar excepción
            return e.ackId;

        } catch (com.zeroc.Ice.LocalException e) {
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] [VotingSite-Proxy] Sistema de votación no disponible");
            System.out.println("[" + timestamp + "] [VotingSite-Proxy] Guardando voto para procesamiento automático");

            String offlineVoteKey = messagingService.storeOfflineVoteWithACK(citizenId.trim(), candidateId.trim());
            messagingService.timeoutVote(offlineVoteKey);

            throw new VotingSystemUnavailableException("Sistema de votación temporalmente no disponible. Su voto será procesado automáticamente.");
        }
    }

    @Override
    public String getSystemStatus(com.zeroc.Ice.Current current) {
        try {
            VotationPrx currentTarget = getCurrentVotationProxy();
            if (currentTarget != null) {
                currentTarget.ice_ping();

                boolean hasReliableMessaging = messagingService.isWorkerRunning();
                int pendingVotes = messagingService.getPendingVotesCount();
                boolean isProcessingOffline = messagingService.hasVotesPending();

                if (pendingVotes > 0) {
                    return "OPERACIONAL (Procesando " + pendingVotes + " votos pendientes)";
                } else if (hasReliableMessaging) {
                    return "OPERACIONAL (Reliable Messaging Activo)";
                } else {
                    return "OPERACIONAL";
                }
            } else {
                return "DEGRADADO (Sin servidores de votación disponibles)";
            }
        } catch (Exception e) {
            return "DEGRADADO (" + e.getMessage() + ")";
        }
    }

    @Override
    public int getPendingVotesCount(com.zeroc.Ice.Current current) {
        return messagingService.getPendingVotesCount();
    }

    private VotationPrx getCurrentVotationProxy() {
        try {
            return VotationPrx.checkedCast(query.findObjectByType("::Demo::Votation"));
        } catch (Exception e) {
            System.out.println("[VotingSite-Proxy] Error obteniendo proxy de votación: " + e.getMessage());
            return votationTarget; // Fallback al original
        }
    }
}
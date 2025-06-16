//
// VoteStationI - Implementación del servicio ICE VoteStation
// Para automatización de pruebas y integración
//

import VotingStation.*;
import Proxy.*;
import Central.*;
import CandidateNotification.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class VoteStationI implements VotingStation.VoteStation {
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Referencias a componentes del sistema
    private final VotingProxyPrx votingProxy;
    private final CentralVotationPrx centralProxy;
    private final String stationId;

    // Cache local de candidatos
    private volatile List<CandidateData> currentCandidates = new ArrayList<CandidateData>();
    private volatile long lastUpdateTimestamp = 0;
    private final Object candidatesLock = new Object();

    // Mapeo de candidatos: posición -> candidateId
    private final Map<Integer, String> candidateMapping = new HashMap<Integer, String>();

    public VoteStationI(VotingProxyPrx votingProxy, CentralVotationPrx centralProxy, String stationId) {
        this.votingProxy = votingProxy;
        this.centralProxy = centralProxy;
        this.stationId = stationId;

        // Inicializar candidatos
        initializeCandidates();

        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] Servicio ICE inicializado");
    }

    @Override
    public int vote(String document, int candidateId, com.zeroc.Ice.Current current) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String clientEndpoint = current.con != null ? current.con.toString() : "unknown";

        System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] ICE vote() llamado");
        System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] Cliente: " + clientEndpoint);
        System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] Documento: " + document + ", CandidateId: " + candidateId);

        // Validaciones básicas
        if (document == null || document.trim().isEmpty()) {
            System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] Error: Documento vacío");
            return 1; // Error: documento inválido
        }

        if (candidateId < 1 || candidateId > 5) {
            System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] Error: CandidateId inválido: " + candidateId);
            return 3; // Error: candidateId fuera de rango
        }

        // Mapear candidateId numérico a string
        String candidateString = mapCandidateId(candidateId);
        if (candidateString == null) {
            System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] Error: No se pudo mapear candidateId: " + candidateId);
            return 4; // Error: candidato no encontrado
        }

        try {
            // Procesar voto a través del sistema
            String ackId = votingProxy.submitVote(document.trim(), candidateString);

            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] ✅ Voto exitoso - ACK: " + ackId);

            return 0; // Éxito

        } catch (VotingSystemUnavailableException e) {
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] ⚠️ Sistema no disponible: " + e.reason);

            // El voto se guardó en reliable messaging, consideramos éxito
            return 0; // Éxito (será procesado automáticamente)

        } catch (InvalidVoteException e) {
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] ❌ Voto inválido: " + e.reason);

            // Verificar si es un duplicado
            if (e.reason != null && e.reason.toLowerCase().contains("already voted") ||
                    e.reason.toLowerCase().contains("ya votó") ||
                    e.reason.toLowerCase().contains("duplicado")) {
                return 2; // Ciudadano ya votó
            }

            return 5; // Error: voto inválido

        } catch (Exception e) {
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] ❌ Error inesperado: " + e.getMessage());

            // Verificar si es error de duplicado por algún mensaje específico
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("already voted") ||
                    errorMsg.contains("ya votó") ||
                    errorMsg.contains("duplicado"))) {
                return 2; // Ciudadano ya votó
            }

            return 9; // Error interno del sistema
        }
    }

    @Override
    public String getStationStatus(com.zeroc.Ice.Current current) {
        try {
            String systemStatus = votingProxy.getSystemStatus();
            int pendingVotes = votingProxy.getPendingVotesCount();

            synchronized (candidatesLock) {
                return String.format("VoteStation-%s: %s | Candidatos: %d | Pendientes: %d | Última actualización: %s",
                        stationId,
                        systemStatus,
                        currentCandidates.size(),
                        pendingVotes,
                        lastUpdateTimestamp > 0 ? new Date(lastUpdateTimestamp).toString() : "N/A"
                );
            }
        } catch (Exception e) {
            return "VoteStation-" + stationId + ": ERROR - " + e.getMessage();
        }
    }

    @Override
    public String getCandidateList(com.zeroc.Ice.Current current) {
        synchronized (candidatesLock) {
            if (currentCandidates.isEmpty()) {
                return "No hay candidatos disponibles";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("CANDIDATOS DISPONIBLES:\n");
            sb.append("======================\n");

            int position = 1;
            for (CandidateData candidate : currentCandidates) {
                if (!"blank".equals(candidate.candidateId)) {
                    sb.append(String.format("%d) %s - %s\n",
                            position,
                            candidate.fullName,
                            candidate.partyName));
                    position++;
                }
            }

            sb.append(String.format("%d) VOTO EN BLANCO\n", position));
            sb.append("======================");

            return sb.toString();
        }
    }

    @Override
    public boolean hasVoted(String document, com.zeroc.Ice.Current current) {
        if (document == null || document.trim().isEmpty()) {
            return false;
        }

        try {
            // Verificar a través del sistema central si es posible
            if (centralProxy != null) {
                return centralProxy.hasVoted(document.trim());
            } else {
                // Fallback: intentar un voto dummy para verificar duplicado
                // NOTA: Este método no es ideal, pero es un fallback
                return false; // No podemos verificar sin acceso al central
            }
        } catch (Exception e) {
            System.err.println("[VoteStation-" + stationId + "] Error verificando voto: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void shutdown(com.zeroc.Ice.Current current) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [VoteStation-" + stationId + "] Shutdown solicitado vía ICE");

        // El shutdown será manejado por el main del VotingMachine
        current.adapter.getCommunicator().shutdown();
    }

    /**
     * Inicializar candidatos y mapeo
     */
    private void initializeCandidates() {
        try {
            if (centralProxy != null) {
                CandidateListResponse response = centralProxy.getCurrentCandidates();

                synchronized (candidatesLock) {
                    currentCandidates = Arrays.asList(response.candidates);
                    lastUpdateTimestamp = response.updateTimestamp;

                    // Construir mapeo de posición a candidateId
                    candidateMapping.clear();
                    int position = 1;

                    for (CandidateData candidate : currentCandidates) {
                        if (!"blank".equals(candidate.candidateId)) {
                            candidateMapping.put(position, candidate.candidateId);
                            position++;
                        }
                    }

                    // Voto en blanco siempre en la última posición
                    candidateMapping.put(position, "blank");
                }

                System.out.println("[VoteStation-" + stationId + "] Candidatos cargados: " + currentCandidates.size());
                System.out.println("[VoteStation-" + stationId + "] Mapeo de candidatos:");
                candidateMapping.forEach((pos, id) ->
                        System.out.println("  " + pos + " -> " + id));

            } else {
                // Candidatos por defecto si no hay conexión al central
                initializeDefaultCandidates();
            }
        } catch (Exception e) {
            System.err.println("[VoteStation-" + stationId + "] Error cargando candidatos: " + e.getMessage());
            initializeDefaultCandidates();
        }
    }

    /**
     * Candidatos por defecto para fallback
     */
    private void initializeDefaultCandidates() {
        candidateMapping.clear();
        candidateMapping.put(1, "candidate001");
        candidateMapping.put(2, "candidate002");
        candidateMapping.put(3, "candidate003");
        candidateMapping.put(4, "candidate004");
        candidateMapping.put(5, "blank");

        System.out.println("[VoteStation-" + stationId + "] Usando candidatos por defecto");
    }

    /**
     * Mapear candidateId numérico a string
     */
    private String mapCandidateId(int candidateId) {
        return candidateMapping.get(candidateId);
    }

    /**
     * Actualizar candidatos (llamado desde VotingMachine cuando recibe notificaciones)
     */
    public void updateCandidates(List<CandidateData> newCandidates, long updateTimestamp) {
        synchronized (candidatesLock) {
            currentCandidates = new ArrayList<CandidateData>(newCandidates);
            lastUpdateTimestamp = updateTimestamp;

            // Reconstruir mapeo
            candidateMapping.clear();
            int position = 1;

            for (CandidateData candidate : currentCandidates) {
                if (!"blank".equals(candidate.candidateId)) {
                    candidateMapping.put(position, candidate.candidateId);
                    position++;
                }
            }

            // Voto en blanco siempre en la última posición
            candidateMapping.put(position, "blank");
        }

        System.out.println("[VoteStation-" + stationId + "] Candidatos actualizados vía notificación");
    }
}
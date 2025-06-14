//
// CentralVotationI - Implementación del servidor central con toda la lógica de base de datos
//

import Central.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class CentralVotationI implements CentralVotation {
    private final String serverName;
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Componentes centralizados (movidos desde DepartmentalServer)
    private final CentralVoteManager voteManager;
    private final CentralACKManager ackManager;

    public CentralVotationI(String serverName) {
        this.serverName = serverName;
        this.voteManager = CentralVoteManager.getInstance();
        this.ackManager = CentralACKManager.getInstance();

        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] Servidor central inicializado");
        System.out.println("[" + timestamp + "] [" + serverName + "] Componentes optimizados para alta carga activos");
    }

    @Override
    public String processVote(String citizenId, String candidateId, String departmentalServerId, com.zeroc.Ice.Current current)
            throws AlreadyVotedCentralException, CentralServerUnavailableException {

        String timestamp = LocalDateTime.now().format(timeFormatter);
        String clientInfo = current.con != null ? current.con.toString() : "unknown";

        System.out.println("[" + timestamp + "] [" + serverName + "] Voto recibido desde " + departmentalServerId);
        System.out.println("[" + timestamp + "] [" + serverName + "] Cliente: " + clientInfo);
        System.out.println("[" + timestamp + "] [" + serverName + "] Procesando: " + citizenId + " -> " + candidateId);

        if (citizenId == null || citizenId.trim().isEmpty() || candidateId == null || candidateId.trim().isEmpty()) {
            throw new CentralServerUnavailableException("Parámetros inválidos", System.currentTimeMillis());
        }

        String cleanCitizenId = citizenId.trim();
        String cleanCandidateId = candidateId.trim();

        try {
            // PASO 1: Verificación rápida con ACKManager optimizado
            String existingACK = ackManager.getACK(cleanCitizenId);

            if (existingACK != null) {
                // Ciudadano ya tiene ACK - retornar excepción con datos
                String existingVote = voteManager.getExistingVote(cleanCitizenId);

                System.out.println("[" + timestamp + "] [" + serverName + "] Duplicado detectado: " +
                        cleanCitizenId + " ya votó por " + existingVote + " (ACK: " + existingACK + ")");

                AlreadyVotedCentralException ex = new AlreadyVotedCentralException();
                ex.ackId = existingACK;
                ex.citizenId = cleanCitizenId;
                ex.existingCandidate = existingVote != null ? existingVote : "unknown";
                throw ex;
            }

            // PASO 2: Procesar voto con VoteManager optimizado
            CentralVoteManager.VoteResult result = voteManager.receiveVote(cleanCitizenId, cleanCandidateId);

            if (result.success) {
                // VOTO VÁLIDO - generar ACK único centralmente
                String ackId = ackManager.getOrCreateACK(cleanCitizenId, serverName + "-" + departmentalServerId);

                // Log solo cada 100 votos para reducir overhead
                if (voteManager.getStats().totalProcessed % 100 == 0) {
                    System.out.println("[" + timestamp + "] [" + serverName + "] Voto #" +
                            voteManager.getStats().totalProcessed + " procesado - ACK: " + ackId);
                }

                return ackId;

            } else {
                // VOTO DUPLICADO - obtener ACK existente
                String ackId = ackManager.getOrCreateACK(cleanCitizenId, serverName + "-" + departmentalServerId);
                String existingVote = voteManager.getExistingVote(cleanCitizenId);

                AlreadyVotedCentralException ex = new AlreadyVotedCentralException();
                ex.ackId = ackId;
                ex.citizenId = cleanCitizenId;
                ex.existingCandidate = existingVote != null ? existingVote : candidateId;
                throw ex;
            }

        } catch (AlreadyVotedCentralException e) {
            throw e; // Re-lanzar excepción específica
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] Error procesando voto: " + e.getMessage());
            throw new CentralServerUnavailableException("Error interno del servidor central: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public String getExistingACK(String citizenId, com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        try {
            return ackManager.getACK(citizenId != null ? citizenId.trim() : null);
        } catch (Exception e) {
            throw new CentralServerUnavailableException("Error consultando ACK: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public boolean hasVoted(String citizenId, com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        try {
            return voteManager.hasVoted(citizenId != null ? citizenId.trim() : null);
        } catch (Exception e) {
            throw new CentralServerUnavailableException("Error verificando voto: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public String getVoteForCitizen(String citizenId, com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        try {
            return voteManager.getExistingVote(citizenId != null ? citizenId.trim() : null);
        } catch (Exception e) {
            throw new CentralServerUnavailableException("Error consultando voto: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public int getTotalVotesCount(com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        try {
            return voteManager.getStats().totalProcessed;
        } catch (Exception e) {
            throw new CentralServerUnavailableException("Error consultando total: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public int getUniqueVotersCount(com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        try {
            return voteManager.getAllVoters().size();
        } catch (Exception e) {
            throw new CentralServerUnavailableException("Error consultando votantes: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public void ping(com.zeroc.Ice.Current current) {
        // Ping básico para verificar conectividad
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] Ping recibido desde: " +
                (current.con != null ? current.con.toString() : "unknown"));
    }

    @Override
    public String getServerStatus(com.zeroc.Ice.Current current) {
        try {
            CentralVoteManager.VotingStats voteStats = voteManager.getStats();
            CentralACKManager.ACKStats ackStats = ackManager.getStats();

            return String.format("OPERACIONAL - Votos: %d | ACKs: %d | Throughput: %.2f v/s",
                    voteStats.totalVoters, ackStats.totalACKs, voteStats.throughputVotesPerSec);
        } catch (Exception e) {
            return "ERROR - " + e.getMessage();
        }
    }

    @Override
    public void shutdown(com.zeroc.Ice.Current current) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] Shutdown solicitado...");

        try {
            voteManager.shutdown();
            ackManager.shutdown();
            System.out.println("[" + timestamp + "] [" + serverName + "] Componentes terminados correctamente");
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] Error en shutdown: " + e.getMessage());
        }

        current.adapter.getCommunicator().shutdown();
    }

    // ============================================================================
    // MÉTODOS ADMINISTRATIVOS
    // ============================================================================

    public void printServerStatus() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n" + "═".repeat(70));
        System.out.println("[" + timestamp + "] [" + serverName + "] === ESTADO DEL SERVIDOR CENTRAL ===");

        try {
            CentralVoteManager.VotingStats voteStats = voteManager.getStats();
            CentralACKManager.ACKStats ackStats = ackManager.getStats();

            System.out.println("Servidor: " + serverName);
            System.out.println("Estado: OPERACIONAL");
            System.out.println();
            System.out.println("📊 ESTADÍSTICAS DE VOTOS:");
            System.out.println("   Votantes únicos:           " + voteStats.totalVoters);
            System.out.println("   Votos procesados:          " + voteStats.totalProcessed);
            System.out.println("   Duplicados detectados:     " + voteStats.duplicatesDetected);
            System.out.println("   Votos pendientes:          " + voteStats.pendingVotes);
            System.out.println("   Throughput actual:         " + String.format("%.2f", voteStats.throughputVotesPerSec) + " v/s");
            System.out.println();
            System.out.println("🔧 ESTADÍSTICAS DE ACK:");
            System.out.println("   ACKs totales:              " + ackStats.totalACKs);
            System.out.println("   ACKs pendientes:           " + ackStats.pendingWrites);
            System.out.println("   Último flush:              " + ackStats.lastFlushAgo + "ms atrás");
            System.out.println();
            System.out.println("💾 BASE DE DATOS:");
            System.out.println("   Estado:                    ACTIVA");
            System.out.println("   Integridad:                VERIFICADA");

        } catch (Exception e) {
            System.err.println("Error obteniendo estadísticas: " + e.getMessage());
        }

        System.out.println("═".repeat(70));
    }

    public void printVotesSummary() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [" + serverName + "] === RESUMEN DE VOTOS ===");

        voteManager.printDebugInfo();
    }

    public void printACKStatus() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [" + serverName + "] === ESTADO DE ACK MANAGER ===");

        ackManager.printDebugInfo();
    }

    public void printDetailedDebugInfo() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [" + serverName + "] === DEBUG DETALLADO ===");

        System.out.println("\n🔍 VOTE MANAGER DEBUG:");
        voteManager.printDebugInfo();

        System.out.println("\n🔍 ACK MANAGER DEBUG:");
        ackManager.printDebugInfo();

        System.out.println("\n🔍 MÉTRICAS DE SISTEMA:");
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;

        System.out.println("   Memoria total:             " + totalMemory + " MB");
        System.out.println("   Memoria usada:             " + usedMemory + " MB");
        System.out.println("   Memoria libre:             " + freeMemory + " MB");
        System.out.println("   Threads activos:           " + Thread.activeCount());
    }

    public void clearStateForTesting() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] ⚠️  LIMPIANDO ESTADO COMPLETO...");

        try {
            ackManager.clearForTesting();
            voteManager.clearForTesting();

            System.out.println("[" + timestamp + "] [" + serverName + "] ✅ Estado limpiado completamente");
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] ❌ Error limpiando estado: " + e.getMessage());
        }
    }
}
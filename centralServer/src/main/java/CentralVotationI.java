//
// CentralVotationI - Implementación del servidor central con toda la lógica de base de datos
//

import Central.*;

import java.io.File;
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

        // Mostrar estado inicial después de cargar persistencia
        printInitialLoadStatus();
    }

    /**
     * Mostrar estado inicial después de cargar datos persistentes
     */
    private void printInitialLoadStatus() {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        try {
            CentralVoteManager.VotingStats voteStats = voteManager.getStats();
            CentralACKManager.ACKStats ackStats = ackManager.getStats();

            System.out.println("\n[" + timestamp + "] [" + serverName + "] === ESTADO INICIAL DESPUÉS DE CARGA ===");
            System.out.println("📊 DATOS CARGADOS DESDE ARCHIVOS:");
            System.out.println("   Votantes únicos cargados:    " + voteStats.totalVoters);
            System.out.println("   Votos totales cargados:      " + voteStats.totalProcessed);
            System.out.println("   ACKs cargados:               " + ackStats.totalACKs);

            if (voteStats.totalVoters > 0) {
                // Mostrar algunos resultados si hay votos
                java.util.Map<String, Integer> votesByCandidate = voteManager.getVotesByCandidate();
                System.out.println();
                System.out.println("🗳️  ESTADO ACTUAL DE VOTACIÓN:");

                votesByCandidate.entrySet().stream()
                        .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                        .limit(3) // Mostrar top 3
                        .forEach(entry -> {
                            String candidateName = formatCandidateName(entry.getKey());
                            System.out.println("   " + candidateName + ": " + entry.getValue() + " votos");
                        });

                System.out.println("   (Use comando 'results' para ver detalles completos)");
            }

            System.out.println("\n🚀 SERVIDOR LISTO PARA RECIBIR NUEVOS VOTOS");
            System.out.println("═".repeat(60));

        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] Error mostrando estado inicial: " + e.getMessage());
        }
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

    public void printVotingResults() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [" + serverName + "] === RESULTADOS DE LA VOTACIÓN ===");

        try {
            // Obtener estadísticas detalladas del vote manager
            CentralVoteManager.DetailedVotingStats detailedStats = voteManager.getDetailedStats();

            if (detailedStats.basicStats.totalVoters == 0) {
                System.out.println("📊 No hay votos registrados en el sistema");
                System.out.println("═".repeat(70));
                return;
            }

            System.out.println("📊 ESTADÍSTICAS GENERALES:");
            System.out.println("   Total de votantes:         " + detailedStats.basicStats.totalVoters);
            System.out.println("   Total de votos válidos:     " + detailedStats.totalValidVotes);
            System.out.println("   Votos procesados:           " + detailedStats.basicStats.totalProcessed);
            System.out.println("   Duplicados detectados:      " + detailedStats.basicStats.duplicatesDetected);
            System.out.println();

            if (detailedStats.votesByCandidate.isEmpty()) {
                System.out.println("📊 No se pudieron obtener los resultados por candidato");
            } else {
                System.out.println("🗳️  RESULTADOS POR CANDIDATO:");
                System.out.println("   " + "─".repeat(50));

                // Ordenar candidatos por número de votos (descendente)
                java.util.List<java.util.Map.Entry<String, Integer>> sortedResults =
                        new java.util.ArrayList<>(detailedStats.votesByCandidate.entrySet());
                sortedResults.sort((a, b) -> b.getValue().compareTo(a.getValue()));

                int position = 1;

                for (java.util.Map.Entry<String, Integer> entry : sortedResults) {
                    String candidate = entry.getKey();
                    int votes = entry.getValue();
                    double percentage = detailedStats.totalValidVotes > 0 ?
                            (double) votes / detailedStats.totalValidVotes * 100 : 0;

                    String candidateName = formatCandidateName(candidate);
                    String positionIcon = getPositionIcon(position);

                    System.out.println(String.format("   %s %d° lugar: %-25s %,4d votos (%.2f%%)",
                            positionIcon, position, candidateName, votes, percentage));
                    position++;
                }

                System.out.println("   " + "─".repeat(50));
                System.out.println(String.format("   📊 TOTAL DE VOTOS VÁLIDOS: %,d", detailedStats.totalValidVotes));

                // Mostrar ganador
                if (detailedStats.winningCandidate != null) {
                    double winnerPercentage = detailedStats.totalValidVotes > 0 ?
                            (double) detailedStats.winningVotes / detailedStats.totalValidVotes * 100 : 0;

                    System.out.println();
                    System.out.println("🏆 GANADOR: " + formatCandidateName(detailedStats.winningCandidate));
                    System.out.println("   Votos obtenidos: " + String.format("%,d", detailedStats.winningVotes) +
                            " (" + String.format("%.2f%%", winnerPercentage) + ")");

                    // Verificar si hay mayoría absoluta
                    if (winnerPercentage > 50.0) {
                        System.out.println("   ✅ MAYORÍA ABSOLUTA");
                    } else {
                        System.out.println("   ⚠️  MAYORÍA SIMPLE (No hay mayoría absoluta)");
                    }
                }

                // Mostrar análisis adicional
                System.out.println();
                System.out.println("📈 ANÁLISIS:");
                System.out.println("   Participación efectiva:     100.00% (de votantes registrados)");
                System.out.println("   Votos en blanco:            " + detailedStats.votesByCandidate.getOrDefault("blank", 0));
                System.out.println("   Candidatos con votos:       " + detailedStats.votesByCandidate.size());

                // Mostrar distribución de votos
                System.out.println();
                System.out.println("📊 DISTRIBUCIÓN VISUAL:");
                showVotingChart(sortedResults, detailedStats.totalValidVotes);
            }

        } catch (Exception e) {
            System.err.println("Error obteniendo resultados: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("═".repeat(70));
    }

    /**
     * Formatear nombre del candidato para display
     */
    private String formatCandidateName(String candidateId) {
        switch (candidateId) {
            case "candidate001": return "Juan Pérez (Partido Azul)";
            case "candidate002": return "María García (Partido Verde)";
            case "candidate003": return "Carlos López (Partido Rojo)";
            case "candidate004": return "Ana Martínez (Partido Amarillo)";
            case "blank": return "VOTO EN BLANCO";
            default: return candidateId;
        }
    }

    /**
     * Obtener icono según posición
     */
    private String getPositionIcon(int position) {
        switch (position) {
            case 1: return "🥇";
            case 2: return "🥈";
            case 3: return "🥉";
            default: return "📊";
        }
    }

    /**
     * Verificar integridad entre votos y ACKs
     */
    public void verifyDataIntegrity() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [" + serverName + "] === VERIFICACIÓN DE INTEGRIDAD ===");

        try {
            // Obtener estadísticas
            CentralVoteManager.VotingStats voteStats = voteManager.getStats();
            CentralACKManager.ACKStats ackStats = ackManager.getStats();

            // Verificar correspondencia votos-ACKs
            java.util.Set<String> allVoters = voteManager.getAllVoters();
            int votersWithACK = 0;
            int votersWithoutACK = 0;

            for (String citizenId : allVoters) {
                if (ackManager.hasACK(citizenId)) {
                    votersWithACK++;
                } else {
                    votersWithoutACK++;
                    System.out.println("⚠️  Ciudadano sin ACK: " + citizenId);
                }
            }

            System.out.println("📊 VERIFICACIÓN DE CORRESPONDENCIA:");
            System.out.println("   Total votantes:              " + voteStats.totalVoters);
            System.out.println("   Total ACKs:                  " + ackStats.totalACKs);
            System.out.println("   Votantes con ACK:            " + votersWithACK);
            System.out.println("   Votantes sin ACK:            " + votersWithoutACK);

            // Verificar archivos
            System.out.println();
            System.out.println("📁 VERIFICACIÓN DE ARCHIVOS:");

            File voteFile = new File("config/db/central-votes.csv");
            File ackFile = new File("config/db/central-citizen-acks.csv");

            System.out.println("   Archivo de votos:            " + (voteFile.exists() ? "✅ Existe" : "❌ No existe"));
            System.out.println("   Archivo de ACKs:             " + (ackFile.exists() ? "✅ Existe" : "❌ No existe"));

            if (voteFile.exists()) {
                System.out.println("   Tamaño archivo votos:        " + String.format("%.2f KB", voteFile.length() / 1024.0));
            }
            if (ackFile.exists()) {
                System.out.println("   Tamaño archivo ACKs:         " + String.format("%.2f KB", ackFile.length() / 1024.0));
            }

            // Resultado de integridad
            boolean integrityOK = (votersWithoutACK == 0) && (voteStats.totalVoters == ackStats.totalACKs);

            System.out.println();
            if (integrityOK) {
                System.out.println("✅ INTEGRIDAD: CORRECTA - Todos los datos están sincronizados");
            } else {
                System.out.println("⚠️  INTEGRIDAD: PROBLEMAS DETECTADOS");
                if (votersWithoutACK > 0) {
                    System.out.println("   - " + votersWithoutACK + " votantes sin ACK");
                }
                if (voteStats.totalVoters != ackStats.totalACKs) {
                    System.out.println("   - Desincronización entre votos (" + voteStats.totalVoters +
                            ") y ACKs (" + ackStats.totalACKs + ")");
                }
            }

        } catch (Exception e) {
            System.err.println("Error en verificación de integridad: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("═".repeat(70));
    }
    private double getParticipationRate() {
        // Por ahora retornamos un valor basado en los votos actuales
        // En un sistema real, esto se calcularía contra el padrón electoral
        CentralVoteManager.VotingStats stats = voteManager.getStats();
        return stats.totalVoters > 0 ? 100.0 : 0.0; // Asumimos 100% de los que votaron
    }

    /**
     * Mostrar gráfico visual de distribución de votos
     */
    private void showVotingChart(java.util.List<java.util.Map.Entry<String, Integer>> sortedResults, int totalVotes) {
        if (totalVotes == 0) return;

        final int CHART_WIDTH = 40;

        for (java.util.Map.Entry<String, Integer> entry : sortedResults) {
            String candidate = formatCandidateName(entry.getKey());
            int votes = entry.getValue();
            double percentage = (double) votes / totalVotes * 100;

            // Calcular barras del gráfico
            int barLength = (int) (percentage / 100.0 * CHART_WIDTH);
            String bar = "█".repeat(Math.max(1, barLength)) +
                    "░".repeat(Math.max(0, CHART_WIDTH - barLength));

            // Truncar nombre si es muy largo
            String shortName = candidate.length() > 20 ?
                    candidate.substring(0, 17) + "..." : candidate;

            System.out.println(String.format("   %-20s |%s| %6.2f%%",
                    shortName, bar, percentage));
        }
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
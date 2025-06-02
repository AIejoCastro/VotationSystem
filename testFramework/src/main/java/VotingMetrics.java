import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class VotingMetrics {
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Contadores thread-safe
    private static final AtomicInteger totalVotesSent = new AtomicInteger(0);
    private static final AtomicInteger totalACKsReceived = new AtomicInteger(0);
    private static final AtomicInteger duplicatesDetected = new AtomicInteger(0);
    private static final AtomicInteger votesFailed = new AtomicInteger(0);

    // Colecciones thread-safe
    private static final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> uniqueACKs = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> citizenVotes = new ConcurrentHashMap<>();
    private static final List<VoteRecord> voteHistory = Collections.synchronizedList(new ArrayList<>());

    // Para debugging de ACKs duplicados
    private static final Map<String, List<String>> ackToCitizens = new ConcurrentHashMap<>();

    public static class VoteRecord {
        public final String citizenId;
        public final String candidateId;
        public final String ackId;
        public final long latencyMs;
        public final String timestamp;
        public final boolean success;
        public final boolean isDuplicate;

        public VoteRecord(String citizenId, String candidateId, String ackId, long latencyMs, boolean success, boolean isDuplicate) {
            this.citizenId = citizenId;
            this.candidateId = candidateId;
            this.ackId = ackId;
            this.latencyMs = latencyMs;
            this.success = success;
            this.isDuplicate = isDuplicate;
            this.timestamp = LocalDateTime.now().format(timeFormatter);
        }
    }

    public static synchronized void recordVoteSent(String citizenId, String candidateId) {
        totalVotesSent.incrementAndGet();
        log("VOTE_SENT", citizenId, candidateId, null, 0);
    }

    public static synchronized void recordVoteSuccess(String citizenId, String candidateId, String ackId, long latencyMs) {
        totalACKsReceived.incrementAndGet();
        latencies.add(latencyMs);

        // Verificacion mejorada de unicidad de ACK
        if (!uniqueACKs.add(ackId)) {
            System.err.println("ERROR CRITICO: ACK duplicado detectado: " + ackId);
            System.err.println("ACK ya usado por: " + ackToCitizens.get(ackId));
            System.err.println("Nuevo intento de: " + citizenId + " -> " + candidateId);
        } else {
            // Registrar que ciudadano uso este ACK
            ackToCitizens.computeIfAbsent(ackId, k -> new ArrayList<>()).add(citizenId + "->" + candidateId);
        }

        // Verificar duplicados ANTES de actualizar el mapa
        String existingVote = citizenVotes.get(citizenId);
        boolean isDuplicate = false;

        if (existingVote != null) {
            // El ciudadano ya habia votado previamente
            isDuplicate = true;
            duplicatesDetected.incrementAndGet();
            log("DUPLICATE_DETECTED", citizenId, candidateId, ackId, latencyMs);
            System.out.println("[METRICS] Duplicado detectado: Ciudadano " + citizenId +
                    " ya voto por " + existingVote + ", nuevo intento por " + candidateId);
        } else {
            // Primer voto valido del ciudadano
            citizenVotes.put(citizenId, candidateId);
            log("VOTE_SUCCESS", citizenId, candidateId, ackId, latencyMs);
        }

        voteHistory.add(new VoteRecord(citizenId, candidateId, ackId, latencyMs, true, isDuplicate));
    }

    public static synchronized void recordVoteFailure(String citizenId, String candidateId, String error) {
        votesFailed.incrementAndGet();
        log("VOTE_FAILED", citizenId, candidateId, error, 0);
        voteHistory.add(new VoteRecord(citizenId, candidateId, null, 0, false, false));
    }

    public static synchronized TestResults getResults() {
        return new TestResults(
                totalVotesSent.get(),
                totalACKsReceived.get(),
                duplicatesDetected.get(),
                votesFailed.get(),
                new ArrayList<>(latencies),
                uniqueACKs.size(),
                citizenVotes.size(),
                new ArrayList<>(voteHistory)
        );
    }

    public static synchronized void reset() {
        totalVotesSent.set(0);
        totalACKsReceived.set(0);
        duplicatesDetected.set(0);
        votesFailed.set(0);
        latencies.clear();
        uniqueACKs.clear();
        citizenVotes.clear();
        voteHistory.clear();
        ackToCitizens.clear();
        log("METRICS_RESET", "", "", "", 0);
    }

    // Analisis de ACKs duplicados
    public static synchronized void printACKAnalysis() {
        System.out.println("\nANALISIS DETALLADO DE ACKs:");
        System.out.println("=======================================");

        Map<String, Integer> ackUsageCount = new HashMap<>();
        for (VoteRecord record : voteHistory) {
            if (record.ackId != null) {
                ackUsageCount.merge(record.ackId, 1, Integer::sum);
            }
        }

        int duplicateACKs = 0;
        System.out.println("ACKs con multiples usos:");
        for (Map.Entry<String, Integer> entry : ackUsageCount.entrySet()) {
            if (entry.getValue() > 1) {
                duplicateACKs++;
                System.out.println("  " + entry.getKey() + " usado " + entry.getValue() + " veces");

                // Mostrar que votos usaron este ACK
                List<String> users = ackToCitizens.get(entry.getKey());
                if (users != null) {
                    users.forEach(user -> System.out.println("    - " + user));
                }
            }
        }

        System.out.println("-----------------------------------------");
        System.out.println("Total ACKs unicos: " + uniqueACKs.size());
        System.out.println("Total ACKs recibidos: " + totalACKsReceived.get());
        System.out.println("ACKs con uso multiple: " + duplicateACKs);
        System.out.println("=======================================");
    }

    public static synchronized void printDuplicateAnalysis() {
        System.out.println("\nANALISIS DETALLADO DE DUPLICADOS:");
        System.out.println("=======================================");

        Map<String, List<VoteRecord>> votesByCitizen = new HashMap<>();
        for (VoteRecord record : voteHistory) {
            votesByCitizen.computeIfAbsent(record.citizenId, k -> new ArrayList<>()).add(record);
        }

        int citizensWithMultipleAttempts = 0;
        int totalDuplicateAttempts = 0;

        for (Map.Entry<String, List<VoteRecord>> entry : votesByCitizen.entrySet()) {
            List<VoteRecord> votes = entry.getValue();
            if (votes.size() > 1) {
                citizensWithMultipleAttempts++;
                totalDuplicateAttempts += (votes.size() - 1);

                System.out.println("Ciudadano " + entry.getKey() + " - " + votes.size() + " intentos:");
                for (int i = 0; i < votes.size(); i++) {
                    VoteRecord vote = votes.get(i);
                    String status = (i == 0) ? "VALIDO" : "DUPLICADO";
                    System.out.println("  " + (i+1) + ". " + vote.candidateId + " -> " + vote.ackId + " (" + status + ")");
                }
            }
        }

        System.out.println("-----------------------------------------");
        System.out.println("Ciudadanos con multiples intentos: " + citizensWithMultipleAttempts);
        System.out.println("Total intentos duplicados: " + totalDuplicateAttempts);
        System.out.println("Duplicados detectados por metricas: " + duplicatesDetected.get());
        System.out.println("=======================================");
    }

    private static void log(String event, String citizenId, String candidateId, String ackId, long latency) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [METRICS] " + event +
                " | Citizen: " + citizenId +
                " | Candidate: " + candidateId +
                " | ACK: " + ackId +
                " | Latency: " + latency + "ms");
    }

    public static class TestResults {
        public final int totalVotesSent;
        public final int totalACKsReceived;
        public final int duplicatesDetected;
        public final int votesFailed;
        public final List<Long> latencies;
        public final int uniqueACKsCount;
        public final int uniqueVotersCount;
        public final List<VoteRecord> voteHistory;

        // Metricas calculadas
        public final double successRate;
        public final double avgLatency;
        public final long maxLatency;
        public final long minLatency;
        public final long p95Latency;
        public final long p99Latency;

        public TestResults(int totalVotesSent, int totalACKsReceived, int duplicatesDetected,
                           int votesFailed, List<Long> latencies, int uniqueACKsCount,
                           int uniqueVotersCount, List<VoteRecord> voteHistory) {
            this.totalVotesSent = totalVotesSent;
            this.totalACKsReceived = totalACKsReceived;
            this.duplicatesDetected = duplicatesDetected;
            this.votesFailed = votesFailed;
            this.latencies = latencies;
            this.uniqueACKsCount = uniqueACKsCount;
            this.uniqueVotersCount = uniqueVotersCount;
            this.voteHistory = voteHistory;

            // Calcular metricas
            this.successRate = totalVotesSent > 0 ? (double) totalACKsReceived / totalVotesSent * 100.0 : 0.0;
            this.avgLatency = latencies.isEmpty() ? 0.0 : latencies.stream().mapToLong(l -> l).average().orElse(0.0);
            this.maxLatency = latencies.isEmpty() ? 0L : Collections.max(latencies);
            this.minLatency = latencies.isEmpty() ? 0L : Collections.min(latencies);

            // Calcular percentiles
            if (!latencies.isEmpty()) {
                List<Long> sortedLatencies = new ArrayList<>(latencies);
                Collections.sort(sortedLatencies);
                this.p95Latency = sortedLatencies.get((int) (sortedLatencies.size() * 0.95));
                this.p99Latency = sortedLatencies.get((int) (sortedLatencies.size() * 0.99));
            } else {
                this.p95Latency = 0L;
                this.p99Latency = 0L;
            }
        }

        public void printSummary() {
            System.out.println("\nRESUMEN DE RESULTADOS");
            System.out.println("====================================");
            System.out.println("Votos enviados:           " + totalVotesSent);
            System.out.println("ACKs recibidos:           " + totalACKsReceived);
            System.out.println("Duplicados detectados:    " + duplicatesDetected);
            System.out.println("Fallos:                   " + votesFailed);
            System.out.println("Votantes unicos:          " + uniqueVotersCount);
            System.out.println("ACKs unicos:              " + uniqueACKsCount);
            System.out.println();
            System.out.println("Tasa de exito:            " + String.format("%.2f%%", successRate));
            System.out.println("Latencia promedio:        " + String.format("%.2f ms", avgLatency));
            System.out.println("Latencia minima:          " + minLatency + " ms");
            System.out.println("Latencia maxima:          " + maxLatency + " ms");
            System.out.println("Latencia P95:             " + p95Latency + " ms");
            System.out.println("Latencia P99:             " + p99Latency + " ms");
            System.out.println("====================================");
        }

        public boolean passesReliabilityTest() {
            // Criterios de confiabilidad
            boolean noVoteLoss = (totalACKsReceived >= totalVotesSent - duplicatesDetected);
            boolean acceptableLatency = avgLatency <= 2000.0;
            boolean uniqueACKs = (uniqueACKsCount == totalACKsReceived);

            return noVoteLoss && acceptableLatency && uniqueACKs;
        }

        public boolean passesUniquenessTest() {
            // Criterios de unicidad mas estrictos
            boolean uniqueACKsGenerated = (uniqueACKsCount == totalACKsReceived);
            boolean correctVoterCount = (uniqueVotersCount <= totalVotesSent);
            boolean noACKCollisions = uniqueACKsGenerated; // ACKs deben ser unicos

            return uniqueACKsGenerated && correctVoterCount && noACKCollisions;
        }
    }
}
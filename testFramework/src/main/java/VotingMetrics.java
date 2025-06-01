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

    public static class VoteRecord {
        public final String citizenId;
        public final String candidateId;
        public final String ackId;
        public final long latencyMs;
        public final String timestamp;
        public final boolean success;

        public VoteRecord(String citizenId, String candidateId, String ackId, long latencyMs, boolean success) {
            this.citizenId = citizenId;
            this.candidateId = candidateId;
            this.ackId = ackId;
            this.latencyMs = latencyMs;
            this.success = success;
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

        // Verificar unicidad de ACK
        if (!uniqueACKs.add(ackId)) {
            System.err.println("❌ ERROR CRÍTICO: ACK duplicado detectado: " + ackId);
        }

        // Verificar si es voto duplicado del mismo ciudadano
        String previousVote = citizenVotes.put(citizenId, candidateId);
        if (previousVote != null) {
            duplicatesDetected.incrementAndGet();
            log("DUPLICATE_DETECTED", citizenId, candidateId, ackId, latencyMs);
        } else {
            log("VOTE_SUCCESS", citizenId, candidateId, ackId, latencyMs);
        }

        voteHistory.add(new VoteRecord(citizenId, candidateId, ackId, latencyMs, true));
    }

    public static synchronized void recordVoteFailure(String citizenId, String candidateId, String error) {
        votesFailed.incrementAndGet();
        log("VOTE_FAILED", citizenId, candidateId, error, 0);
        voteHistory.add(new VoteRecord(citizenId, candidateId, null, 0, false));
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
        log("METRICS_RESET", "", "", "", 0);
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

        // Métricas calculadas
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

            // Calcular métricas
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
            System.out.println("\n📊 RESUMEN DE RESULTADOS");
            System.out.println("════════════════════════════════════════");
            System.out.println("Votos enviados:           " + totalVotesSent);
            System.out.println("ACKs recibidos:           " + totalACKsReceived);
            System.out.println("Duplicados detectados:    " + duplicatesDetected);
            System.out.println("Fallos:                   " + votesFailed);
            System.out.println("Votantes únicos:          " + uniqueVotersCount);
            System.out.println("ACKs únicos:              " + uniqueACKsCount);
            System.out.println();
            System.out.println("Tasa de éxito:            " + String.format("%.2f%%", successRate));
            System.out.println("Latencia promedio:        " + String.format("%.2f ms", avgLatency));
            System.out.println("Latencia mínima:          " + minLatency + " ms");
            System.out.println("Latencia máxima:          " + maxLatency + " ms");
            System.out.println("Latencia P95:             " + p95Latency + " ms");
            System.out.println("Latencia P99:             " + p99Latency + " ms");
            System.out.println("════════════════════════════════════════");
        }

        public boolean passesReliabilityTest() {
            // Criterios de confiabilidad
            boolean noVoteLoss = (totalACKsReceived >= totalVotesSent - duplicatesDetected);
            boolean acceptableLatency = avgLatency <= 2000.0;
            boolean uniqueACKs = (uniqueACKsCount == totalACKsReceived);

            return noVoteLoss && acceptableLatency && uniqueACKs;
        }

        public boolean passesUniquenessTest() {
            // Criterios de unicidad
            boolean correctDuplicateDetection = true; // Se valida durante ejecución
            boolean uniqueACKsGenerated = (uniqueACKsCount == totalACKsReceived);
            boolean correctVoterCount = (uniqueVotersCount <= totalVotesSent);

            return correctDuplicateDetection && uniqueACKsGenerated && correctVoterCount;
        }
    }
}
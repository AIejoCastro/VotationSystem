import Proxy.*;
import java.util.concurrent.*;
import java.util.Random;

public class VotingSimulator {
    private final ExecutorService executor;
    private final Random random = new Random();

    public VotingSimulator(int threadPoolSize) {
        this.executor = Executors.newFixedThreadPool(threadPoolSize);
    }

    public CompletableFuture<Void> simulateVoter(String citizenId, String candidateId, VotingProxyPrx proxy, long delayMs) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }

                VotingMetrics.recordVoteSent(citizenId, candidateId);

                long startTime = System.currentTimeMillis();
                String ackId = proxy.submitVote(citizenId, candidateId);
                long latency = System.currentTimeMillis() - startTime;

                VotingMetrics.recordVoteSuccess(citizenId, candidateId, ackId, latency);

            } catch (Exception e) {
                VotingMetrics.recordVoteFailure(citizenId, candidateId, e.getMessage());
            }
        }, executor);
    }

    public void simulateConcurrentVoting(int voterCount, VotingProxyPrx proxy) {
        System.out.println("🚀 Iniciando simulación de " + voterCount + " votantes concurrentes");

        CompletableFuture<Void>[] futures = new CompletableFuture[voterCount];

        for (int i = 0; i < voterCount; i++) {
            String citizenId = String.format("citizen%05d", i + 1);
            String candidateId = "candidate" + String.format("%03d", (i % 4) + 1);
            long randomDelay = random.nextInt(5000); // 0-5 segundos de delay aleatorio

            futures[i] = simulateVoter(citizenId, candidateId, proxy, randomDelay);
        }

        // Esperar a que todos terminen
        CompletableFuture.allOf(futures).join();
        System.out.println("✅ Simulación de votantes concurrentes completada");
    }

    public void simulateDuplicateVoting(int citizenCount, VotingProxyPrx proxy) {
        System.out.println("🔄 Iniciando simulación de votos duplicados");

        for (int i = 0; i < citizenCount; i++) {
            String citizenId = String.format("citizen%05d", i + 1);

            // Primer voto (válido)
            simulateVoter(citizenId, "candidate001", proxy, 0);

            // Segundo voto del mismo ciudadano (duplicado)
            simulateVoter(citizenId, "candidate002", proxy, 100);

            // Tercer voto del mismo ciudadano (duplicado)
            simulateVoter(citizenId, "candidate001", proxy, 200);
        }

        // Esperar un poco para que se procesen
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("✅ Simulación de votos duplicados completada");
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
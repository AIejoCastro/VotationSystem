import Proxy.*;
import java.util.concurrent.*;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;

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

    // MÉTODO CORREGIDO: Ahora espera que cada grupo de votos se procese completamente
    public void simulateDuplicateVoting(int citizenCount, VotingProxyPrx proxy) {
        System.out.println("🔄 Iniciando simulación de votos duplicados para " + citizenCount + " ciudadanos");

        List<CompletableFuture<Void>> allVotes = new ArrayList<>();

        for (int i = 0; i < citizenCount; i++) {
            String citizenId = String.format("citizen%05d", i + 1);

            System.out.println("📤 Procesando ciudadano: " + citizenId);

            // Primer voto (válido) - candidate001
            CompletableFuture<Void> vote1 = simulateVoter(citizenId, "candidate001", proxy, 0);
            allVotes.add(vote1);

            // Segundo voto del mismo ciudadano (duplicado) - candidate002
            CompletableFuture<Void> vote2 = simulateVoter(citizenId, "candidate002", proxy, 200);
            allVotes.add(vote2);

            // Tercer voto del mismo ciudadano (duplicado) - candidate001 otra vez
            CompletableFuture<Void> vote3 = simulateVoter(citizenId, "candidate001", proxy, 400);
            allVotes.add(vote3);

            // Esperar cada 10 ciudadanos para no sobrecargar
            if ((i + 1) % 10 == 0) {
                try {
                    Thread.sleep(1000);
                    System.out.println("⏳ Procesados " + (i + 1) + " ciudadanos hasta ahora...");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // Esperar a que TODOS los votos terminen de procesarse
        System.out.println("⏳ Esperando que todos los votos se procesen...");
        CompletableFuture.allOf(allVotes.toArray(new CompletableFuture[0])).join();

        // Tiempo adicional para que el sistema procese las respuestas
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("✅ Simulación de votos duplicados completada");

        // Debug: Mostrar estado actual
        VotingMetrics.TestResults currentResults = VotingMetrics.getResults();
        System.out.println("📊 Resultados parciales:");
        System.out.println("   Votos enviados: " + currentResults.totalVotesSent);
        System.out.println("   ACKs recibidos: " + currentResults.totalACKsReceived);
        System.out.println("   Duplicados detectados: " + currentResults.duplicatesDetected);
        System.out.println("   Votantes únicos: " + currentResults.uniqueVotersCount);
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
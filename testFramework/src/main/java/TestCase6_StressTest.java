import Proxy.*;

import java.util.Random;
import java.util.concurrent.CompletableFuture;

public class TestCase6_StressTest {

    public static boolean runTest() {
        System.out.println("\nCASO 6: STRESS TEST DE CONCURRENCIA");
        System.out.println("═══════════════════════════════════════════════");

        VotingMetrics.reset();

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize()) {
            communicator.getProperties().setProperty("Ice.Default.Locator", "DemoIceGrid/Locator:default -h localhost -p 4061");

            VotingProxyPrx proxy = VotingProxyPrx.checkedCast(
                    communicator.stringToProxy("VotingProxy:default -h localhost -p 9999")
            );

            if (proxy == null) {
                System.err.println("No se pudo conectar al VotingSite");
                return false;
            }

            System.out.println("Iniciando stress test con 500 votantes simultáneos");
            System.out.println("Votación en burst intenso durante 2 minutos");

            VotingSimulator simulator = new VotingSimulator(50); // Pool más grande para stress

            long stressTestStart = System.currentTimeMillis();

            // Crear array de futures para todos los votantes
            CompletableFuture<Void>[] votingFutures = new CompletableFuture[500];

            // Lanzar 500 votantes con delays aleatorios en ventana de 30 segundos
            Random random = new Random();
            for (int i = 0; i < 500; i++) {
                String citizenId = String.format("stress_citizen%05d", i + 1);
                String candidateId = "candidate" + String.format("%03d", (i % 4) + 1);
                long randomDelay = random.nextInt(30000); // 0-30 segundos

                votingFutures[i] = simulator.simulateVoter(citizenId, candidateId, proxy, randomDelay)
                        .thenApply(v -> null);
            }

            // Monitorear progreso cada 10 segundos
            CompletableFuture<Void> allVotes = CompletableFuture.allOf(votingFutures);

            while (!allVotes.isDone()) {
                Thread.sleep(10000);
                VotingMetrics.TestResults progressResults = VotingMetrics.getResults();
                long elapsedTime = System.currentTimeMillis() - stressTestStart;
                double currentThroughput = (double) progressResults.totalACKsReceived / elapsedTime * 1000;

                System.out.println("Progreso (" + (elapsedTime / 1000) + "s): " +
                        progressResults.totalACKsReceived + "/500 votos | " +
                        String.format("%.2f", currentThroughput) + " votos/seg | " +
                        String.format("%.2f", progressResults.avgLatency) + "ms promedio");
            }

            // Esperar a que terminen todos
            allVotes.join();

            long votingPhaseTime = System.currentTimeMillis() - stressTestStart;

            System.out.println("Fase de votación completada. Esperando procesamiento final...");
            Thread.sleep(15000);

            simulator.shutdown();

            long totalStressTime = System.currentTimeMillis() - stressTestStart;

            // Análisis detallado de stress test
            VotingMetrics.TestResults stressResults = VotingMetrics.getResults();
            stressResults.printSummary();

            // Calcular métricas de rendimiento
            double overallThroughput = (double) stressResults.totalACKsReceived / totalStressTime * 1000;
            double votingPhaseThroughput = (double) stressResults.totalACKsReceived / votingPhaseTime * 1000;

            System.out.println("\nMÉTRICAS DE RENDIMIENTO:");
            System.out.println("Tiempo de votación:       " + (votingPhaseTime / 1000) + " segundos");
            System.out.println("Tiempo total:             " + (totalStressTime / 1000) + " segundos");
            System.out.println("Throughput de votación:   " + String.format("%.2f", votingPhaseThroughput) + " votos/segundo");
            System.out.println("Throughput general:       " + String.format("%.2f", overallThroughput) + " votos/segundo");
            System.out.println("Latencia P95:             " + stressResults.p95Latency + " ms");
            System.out.println("Latencia P99:             " + stressResults.p99Latency + " ms");
            System.out.println("Latencia máxima:          " + stressResults.maxLatency + " ms");

            // Criterios de éxito para stress test
            boolean highSuccessRate = stressResults.successRate >= 95.0; // Mínimo 95% de éxito
            boolean acceptableLatency = stressResults.p95Latency <= 5000; // P95 < 5 segundos
            boolean maxLatencyOK = stressResults.maxLatency <= 10000; // Máximo absoluto 10 segundos
            boolean goodThroughput = overallThroughput >= 5.0; // Mínimo 5 votos/segundo
            boolean integrityOK = stressResults.passesUniquenessTest();
            boolean mostVotesProcessed = stressResults.totalACKsReceived >= 475; // Al menos 95% procesados

            System.out.println("\nEVALUACIÓN DE CRITERIOS:");
            System.out.println("Tasa de éxito ≥ 95%:      " + (highSuccessRate ? "Bien" : "Mal") + " (" + String.format("%.2f%%", stressResults.successRate) + ")");
            System.out.println("Latencia P95 ≤ 5s:        " + (acceptableLatency ? "Bien" : "Mal") + " (" + stressResults.p95Latency + "ms)");
            System.out.println("Latencia máx ≤ 10s:       " + (maxLatencyOK ? "Bien" : "Mal") + " (" + stressResults.maxLatency + "ms)");
            System.out.println("Throughput ≥ 5 v/s:       " + (goodThroughput ? "Bien" : "Mal") + " (" + String.format("%.2f", overallThroughput) + " v/s)");
            System.out.println("Integridad mantenida:     " + (integrityOK ? "Bien" : "Mal"));
            System.out.println("Votos procesados ≥ 95%:   " + (mostVotesProcessed ? "Bien" : "Mal") + " (" + stressResults.totalACKsReceived + "/500)");

            boolean success = highSuccessRate && acceptableLatency && maxLatencyOK &&
                    goodThroughput && integrityOK && mostVotesProcessed;

            if (success) {
                System.out.println("\nCASO 6: EXITOSO - Sistema soporta carga de stress correctamente");
            } else {
                System.out.println("\nCASO 6: FALLIDO - Sistema no soporta la carga de stress");
            }

            return success;

        } catch (Exception e) {
            System.err.println("Error ejecutando Caso 6: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
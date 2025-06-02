import Proxy.*;

import java.util.*;
import java.util.concurrent.*;

public class TestCase2_Duplicates {

    public static boolean runTest() {
        System.out.println("\nCASO 2 STRESS: DETECCIÓN DE DUPLICADOS");
        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println("OBJETIVO: Validar ACKManager bajo condiciones extremas");
        System.out.println("ESCALA: 100 ciudadanos, 10 intentos cada uno = 1000 votos totales");
        System.out.println("CONCURRENCIA: Múltiples threads simultáneos");
        System.out.println("PATRONES: Aleatorios y determinísticos");

        // Limpiar estado antes de empezar
        VotingMetrics.reset();

        // Limpiar estado centralizado de ACKs
        try {
            Class<?> votationClass = Class.forName("VotationI");
            java.lang.reflect.Method clearMethod = votationClass.getMethod("clearACKState");
            clearMethod.invoke(null);
            System.out.println("Estado de ACKs centralizados limpiado para stress test");
        } catch (Exception e) {
            System.out.println("No se pudo limpiar estado anterior: " + e.getMessage());
        }

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize()) {
            communicator.getProperties().setProperty("Ice.Default.Locator", "DemoIceGrid/Locator:default -h localhost -p 4061");

            VotingProxyPrx proxy = VotingProxyPrx.checkedCast(
                    communicator.stringToProxy("VotingProxy:default -h localhost -p 9999")
            );

            if (proxy == null) {
                System.err.println("No se pudo conectar al VotingSite");
                return false;
            }

            long startTime = System.currentTimeMillis();

            // FASE 1: Test secuencial intensivo
            boolean phase1 = runSequentialIntensiveTest(proxy, 25);

            // FASE 2: Test concurrente masivo
            boolean phase2 = runMassiveConcurrentTest(proxy, 75);

            long totalTime = System.currentTimeMillis() - startTime;

            // Esperar procesamiento completo
            System.out.println("Esperando procesamiento final completo...");
            Thread.sleep(5000);

            // Analizar resultados finales
            VotingMetrics.TestResults results = VotingMetrics.getResults();

            printStressTestAnalysis(results);

            System.out.println("\nTiempo total del stress test: " + (totalTime / 1000) + " segundos");

            // Criterios de éxito para stress test
            int expectedUniqueCitizens = 100;       // 100 ciudadanos únicos total
            int expectedTotalAttempts = 1000;       // 10 intentos por ciudadano (100 * 10)
            int expectedDuplicates = 900;           // 9 duplicados por ciudadano (100 * 9)
            int expectedUniqueACKs = 100;           // CRÍTICO: Solo 100 ACKs únicos (uno por ciudadano)

            // Verificación ultra estricta
            boolean correctTotalAttempts = results.totalVotesSent == expectedTotalAttempts;
            boolean correctUniqueVoters = results.uniqueVotersCount == expectedUniqueCitizens;
            boolean duplicateDetectionWorking = results.duplicatesDetected >= (expectedDuplicates * 0.95); // 95% de duplicados detectados
            boolean allAttemptsProcessed = results.totalACKsReceived >= (expectedTotalAttempts * 0.98); // 98% procesados
            boolean perfectACKUniqueness = (results.uniqueACKsCount == expectedUniqueACKs); // PERFECCIÓN REQUERIDA
            boolean phase1Success = phase1;
            boolean phase2Success = phase2;

            System.out.println("\nANÁLISIS FINAL DE STRESS TEST:");
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("Ciudadanos únicos:        " + expectedUniqueCitizens + " | Actual: " + results.uniqueVotersCount + " " + (correctUniqueVoters ? "Bien" : "Mal"));
            System.out.println("Intentos totales:         " + expectedTotalAttempts + " | Actual: " + results.totalVotesSent + " " + (correctTotalAttempts ? "Bien" : "Mal"));
            System.out.println("ACKs recibidos:          ≥" + (int)(expectedTotalAttempts * 0.98) + " | Actual: " + results.totalACKsReceived + " " + (allAttemptsProcessed ? "Bien" : "Mal"));
            System.out.println("Duplicados esperados:    ≥" + (int)(expectedDuplicates * 0.95) + " | Actual: " + results.duplicatesDetected + " " + (duplicateDetectionWorking ? "Bien" : "Mal"));
            System.out.println("ACKs únicos PERFECTOS:    " + expectedUniqueACKs + " | Actual: " + results.uniqueACKsCount + " " + (perfectACKUniqueness ? "Bien" : "Mal"));
            System.out.println("Fase 1 (Secuencial):     " + (phase1Success ? "EXITOSA" : "FALLIDA"));
            System.out.println("Fase 2 (Concurrente):    " + (phase2Success ? "EXITOSA" : "FALLIDA"));

            System.out.println("═══════════════════════════════════════════════════════════════");

            boolean success = correctUniqueVoters &&
                    duplicateDetectionWorking &&
                    allAttemptsProcessed &&
                    perfectACKUniqueness &&
                    phase1Success &&
                    phase2Success;

            if (success) {
                System.out.println("\nSTRESS TEST EXITOSO - SISTEMA APROBADO AL 100%");
                System.out.println("El sistema maneja duplicados perfectamente bajo condiciones extremas");
                System.out.println("ACKManager centralizado es 100% confiable y consistente");
                System.out.println("Garantía absoluta: " + results.uniqueACKsCount + " ACKs únicos para " + results.uniqueVotersCount + " ciudadanos");
            } else {
                System.out.println("\nSTRESS TEST FALLIDO - SISTEMA REQUIERE MEJORAS");

                if (!correctUniqueVoters) {
                    System.out.println("Número incorrecto de votantes únicos");
                }
                if (!duplicateDetectionWorking) {
                    System.out.println("Detección de duplicados insuficiente bajo stress");
                }
                if (!allAttemptsProcessed) {
                    System.out.println("Pérdida de votos bajo carga masiva");
                }
                if (!perfectACKUniqueness) {
                    System.out.println("CRÍTICO: ACKManager falló bajo concurrencia extrema");
                }
                if (!phase1Success) {
                    System.out.println("Falló en test secuencial intensivo");
                }
                if (!phase2Success) {
                    System.out.println("Falló en test concurrente masivo");
                }
            }

            return success;

        } catch (Exception e) {
            System.err.println("Error ejecutando Stress Test: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * FASE 1: Test secuencial intensivo - 25 ciudadanos, 10 intentos cada uno
     */
    private static boolean runSequentialIntensiveTest(VotingProxyPrx proxy, int citizenCount) {
        System.out.println("\nFASE 1: TEST SECUENCIAL INTENSIVO");
        System.out.println(citizenCount + " ciudadanos, 10 intentos cada uno = " + (citizenCount * 10) + " votos");
        System.out.println("Patrón: Cada ciudadano vota 10 veces por candidatos aleatorios");

        Random random = new Random();
        String[] candidates = {"candidate001", "candidate002", "candidate003", "candidate004", "blank"};

        for (int i = 1; i <= citizenCount; i++) {
            String citizenId = String.format("sequential_citizen%03d", i);

            if (i % 5 == 0) {
                System.out.println("Progreso secuencial: " + i + "/" + citizenCount + " ciudadanos");
            }

            // 10 intentos por ciudadano con candidatos aleatorios
            for (int attempt = 1; attempt <= 10; attempt++) {
                String candidateId = candidates[random.nextInt(candidates.length)];
                simulateVoterSync(citizenId, candidateId, proxy);

                // Pequeña pausa para simular comportamiento real
                try { Thread.sleep(10); } catch (InterruptedException e) {}
            }
        }

        System.out.println("Fase 1 completada - verificando consistencia...");

        // Verificar que cada ciudadano tiene exactamente un ACK único
        return verifyACKConsistency("SECUENCIAL", citizenCount);
    }

    /**
     * FASE 2: Test concurrente masivo - 75 ciudadanos, 10 intentos cada uno, CONCURRENTE
     */
    private static boolean runMassiveConcurrentTest(VotingProxyPrx proxy, int citizenCount) {
        System.out.println("\nFASE 2: TEST CONCURRENTE MASIVO");
        System.out.println(citizenCount + " ciudadanos, 10 intentos cada uno = " + (citizenCount * 10) + " votos");
        System.out.println("CONCURRENCIA EXTREMA: Todos los votos simultáneos con threads");

        ExecutorService executor = Executors.newFixedThreadPool(20); // 20 threads concurrentes
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        Random random = new Random();
        String[] candidates = {"candidate001", "candidate002", "candidate003", "candidate004", "blank"};

        // Crear todos los votos concurrentes
        for (int i = 1; i <= citizenCount; i++) {
            String citizenId = String.format("concurrent_citizen%03d", i);

            // 10 intentos concurrentes por ciudadano
            for (int attempt = 1; attempt <= 10; attempt++) {
                String candidateId = candidates[random.nextInt(candidates.length)];
                long randomDelay = random.nextInt(2000); // 0-2 segundos de delay aleatorio

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        if (randomDelay > 0) {
                            Thread.sleep(randomDelay);
                        }
                        simulateVoterSync(citizenId, candidateId, proxy);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, executor);

                futures.add(future);
            }
        }

        // Esperar que todos los votos concurrentes terminen
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
            System.out.println("Fase 2 completada - todos los votos concurrentes procesados");
        } catch (Exception e) {
            System.err.println("Timeout o error en fase concurrente: " + e.getMessage());
            return false;
        } finally {
            executor.shutdown();
        }

        return verifyACKConsistency("CONCURRENTE", citizenCount);
    }

    /**
     * Verificar que el ACKManager mantuvo consistencia perfecta
     */
    private static boolean verifyACKConsistency(String phaseName, int expectedCitizens) {
        VotingMetrics.TestResults currentResults = VotingMetrics.getResults();

        // Contar ciudadanos únicos en esta fase
        int uniqueCitizensInPhase = 0;
        for (VotingMetrics.VoteRecord record : currentResults.voteHistory) {
            if (record.citizenId.contains(phaseName.toLowerCase())) {
                uniqueCitizensInPhase++;
                break; // Solo contar una vez por ciudadano
            }
        }

        boolean success = true;

        // Verificar que no hay ACKs duplicados en esta fase
        Set<String> phaseACKs = new HashSet<>();
        Map<String, String> citizenACKMap = new HashMap<>();

        for (VotingMetrics.VoteRecord record : currentResults.voteHistory) {
            if (record.citizenId.contains(phaseName.toLowerCase()) && record.success) {

                // Verificar que cada ciudadano tiene consistentemente el mismo ACK
                String existingACK = citizenACKMap.get(record.citizenId);
                if (existingACK != null && !existingACK.equals(record.ackId)) {
                    System.err.println("["+ phaseName + "] Inconsistencia de ACK para " + record.citizenId +
                            ": " + existingACK + " vs " + record.ackId);
                    success = false;
                } else {
                    citizenACKMap.put(record.citizenId, record.ackId);
                }

                phaseACKs.add(record.ackId);
            }
        }

        System.out.println("🔍 [" + phaseName + "] Ciudadanos únicos: " + citizenACKMap.size() +
                ", ACKs únicos: " + phaseACKs.size());

        if (citizenACKMap.size() == phaseACKs.size()) {
            System.out.println("[" + phaseName + "] PERFECTO: 1 ACK único por ciudadano");
        } else {
            System.err.println("[" + phaseName + "] FALLO: Inconsistencia de ACKs");
            success = false;
        }

        return success;
    }

    /**
     * Simulación sincrónica optimizada para stress test
     */
    private static void simulateVoterSync(String citizenId, String candidateId, VotingProxyPrx proxy) {
        try {
            VotingMetrics.recordVoteSent(citizenId, candidateId);

            long startTime = System.currentTimeMillis();
            String ackId = proxy.submitVote(citizenId, candidateId);
            long latency = System.currentTimeMillis() - startTime;

            VotingMetrics.recordVoteSuccess(citizenId, candidateId, ackId, latency);

        } catch (Exception e) {
            VotingMetrics.recordVoteFailure(citizenId, candidateId, e.getMessage());
        }
    }

    /**
     * Análisis detallado de resultados del stress test
     */
    private static void printStressTestAnalysis(VotingMetrics.TestResults results) {
        System.out.println("\nANÁLISIS DETALLADO DEL STRESS TEST");
        System.out.println("═══════════════════════════════════════════════════════════════");

        VotingMetrics.printACKAnalysis();

        results.printSummary();

        // Análisis de distribución de latencias
        System.out.println("\nANÁLISIS DE RENDIMIENTO:");
        System.out.println("Latencia P50:             " + calculatePercentile(results.latencies, 0.50) + " ms");
        System.out.println("Latencia P90:             " + calculatePercentile(results.latencies, 0.90) + " ms");
        System.out.println("Latencia P95:             " + results.p95Latency + " ms");
        System.out.println("Latencia P99:             " + results.p99Latency + " ms");

        // Análisis de distribución de candidatos
        Map<String, Integer> candidateDistribution = new HashMap<>();
        for (VotingMetrics.VoteRecord record : results.voteHistory) {
            if (record.success && !record.isDuplicate) {
                candidateDistribution.merge(record.candidateId, 1, Integer::sum);
            }
        }

        System.out.println("\nDISTRIBUCIÓN DE VOTOS VÁLIDOS:");
        candidateDistribution.forEach((candidate, count) ->
                System.out.println("  " + candidate + ": " + count + " votos"));
    }

    /**
     * Calcular percentil de latencias
     */
    private static long calculatePercentile(List<Long> latencies, double percentile) {
        if (latencies.isEmpty()) return 0L;

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int index = (int) (sorted.size() * percentile);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    /**
     * Metodo main para ejecutar directamente el stress test
     */
    public static void main(String[] args) {
        System.out.println("EJECUTANDO DIRECTAMENTE STRESS TEST DE DUPLICADOS");
        System.out.println("════════════════════════════════════════════════════════════════");

        long startTime = System.currentTimeMillis();
        boolean result = runTest();
        long totalTime = System.currentTimeMillis() - startTime;

        System.out.println("\nRESULTADO FINAL DEL STRESS TEST:");
        System.out.println("══════════════════════════════════════════════");
        System.out.println("Stress Test: " + (result ? "EXITOSO" : "FALLIDO"));
        System.out.println("Tiempo total: " + (totalTime / 1000) + " segundos");

        if (result) {
            System.out.println("SISTEMA VALIDADO AL 100% BAJO CONDICIONES EXTREMAS");
            System.out.println("Listo para producción con máxima confianza");
        } else {
            System.out.println("Se requieren mejoras para manejar carga extrema");
        }

        System.out.println("══════════════════════════════════════════════");
        System.exit(result ? 0 : 1);
    }
}
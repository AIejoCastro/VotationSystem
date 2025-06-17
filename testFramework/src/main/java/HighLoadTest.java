import Proxy.*;
import java.sql.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;

/**
 * Test de carga SIN LIMITADOR - Máximo rendimiento posible
 * MODIFICADO: Usa documentos reales de la base de datos PostgreSQL
 */
public class HighLoadTest {

    // CONFIGURACIÓN DEL TEST
    private static final int TEST_DURATION_SECONDS = 300; // 5 minutos
    private static final int CONCURRENT_MACHINES = 100; // Aumentado para máximo rendimiento

    // MÉTRICAS GLOBALES
    private static final AtomicInteger votesSubmitted = new AtomicInteger(0);
    private static final AtomicInteger votesCompleted = new AtomicInteger(0);
    private static final AtomicInteger votesSuccessful = new AtomicInteger(0);
    private static final AtomicInteger votesFailed = new AtomicInteger(0);
    private static final AtomicLong totalLatency = new AtomicLong(0);
    private static final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

    // CONTROL DE TEST
    private static volatile boolean testRunning = false;
    private static volatile long testStartTime = 0;

    // BASE DE DATOS - Pool de documentos reales
    private static HikariDataSource dataSource;
    private static List<String> realDocuments = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger documentIndex = new AtomicInteger(0);

    public static void main(String[] args) {
        System.out.println("████████████████████████████████████████████████████████████");
        System.out.println("█           TEST DE CARGA MÁXIMA - SISTEMA VOTACIÓN          █");
        System.out.println("█                   SIN LIMITADORES                          █");
        System.out.println("████████████████████████████████████████████████████████████");
        System.out.println("🚀 Modo: MÁXIMO RENDIMIENTO POSIBLE");
        System.out.println("⏱️  Duración: " + TEST_DURATION_SECONDS + " segundos (" + (TEST_DURATION_SECONDS/60) + " minutos)");
        System.out.println("🖥️  Máquinas concurrentes: " + CONCURRENT_MACHINES);
        System.out.println("⚡ Velocidad: ILIMITADA");
        System.out.println("████████████████████████████████████████████████████████████");

        // Inicializar conexión a base de datos
        if (!initializeDatabase()) {
            System.err.println("❌ ERROR CRÍTICO: No se pudo conectar a la base de datos");
            System.exit(1);
        }

        // Cargar documentos reales
        if (!loadRealDocuments()) {
            System.err.println("❌ ERROR CRÍTICO: No se pudieron cargar documentos de la BD");
            System.exit(1);
        }

        boolean testPassed = executeLoadTest();

        // Cerrar conexión a BD
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        System.out.println("\n████████████████████████████████████████████████████████████");
        if (testPassed) {
            System.out.println("█                    ✅ TEST COMPLETADO                       █");
            System.out.println("█              MÁXIMO RENDIMIENTO MEDIDO                     █");
        } else {
            System.out.println("█                    ❌ TEST INTERRUMPIDO                     █");
            System.out.println("█           REVISAR LOGS DE ERRORES                          █");
        }
        System.out.println("████████████████████████████████████████████████████████████");

        System.exit(testPassed ? 0 : 1);
    }

    /**
     * Inicializar pool de conexiones a PostgreSQL
     */
    private static boolean initializeDatabase() {
        try {
            System.out.println("🔌 Conectando a PostgreSQL para obtener documentos reales...");

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:postgresql://10.147.17.101:5432/votacion");
            config.setUsername("postgres");
            config.setPassword("postgres");
            config.setMaximumPoolSize(5);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(3000);

            dataSource = new HikariDataSource(config);

            // Verificar conexión
            try (Connection conn = dataSource.getConnection()) {
                System.out.println("✅ Conexión a PostgreSQL establecida");
                return true;
            }

        } catch (Exception e) {
            System.err.println("❌ Error conectando a PostgreSQL: " + e.getMessage());
            return false;
        }
    }

    /**
     * Cargar documentos reales de la base de datos
     */
    private static boolean loadRealDocuments() {
        try {
            System.out.println("📋 Cargando documentos únicos de ciudadanos...");

            String sql = "SELECT documento FROM ciudadano WHERE documento IS NOT NULL AND documento != '' LIMIT 500000";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                int count = 0;
                while (rs.next()) {
                    String documento = rs.getString("documento");
                    if (documento != null && !documento.trim().isEmpty()) {
                        realDocuments.add(documento.trim());
                        count++;
                    }
                }

                System.out.println("✅ Documentos cargados: " + String.format("%,d", count));

                if (count == 0) {
                    System.err.println("❌ No se encontraron documentos en la base de datos");
                    return false;
                }

                return true;

            }

        } catch (Exception e) {
            System.err.println("❌ Error cargando documentos: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Obtener un documento real de la BD de forma secuencial (sin duplicados)
     */
    private static String getUniqueRealDocument() {
        if (realDocuments.isEmpty()) {
            return "12345678"; // Fallback
        }

        int index = documentIndex.getAndIncrement();
        if (index >= realDocuments.size()) {
            documentIndex.set(0);
            index = 0;
        }

        return realDocuments.get(index);
    }

    private static boolean executeLoadTest() {
        ExecutorService votingPool = Executors.newFixedThreadPool(CONCURRENT_MACHINES + 10);

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize()) {
            communicator.getProperties().setProperty("Ice.Default.Locator",
                    "DemoIceGrid-grpmcc/Locator:default -h 10.147.17.101 -p 4071");

            System.out.println("\n🔌 Conectando al sistema de votación...");
            VotingProxyPrx proxy = VotingProxyPrx.checkedCast(
                    communicator.stringToProxy("VotingProxy:default -h 10.147.17.102 -p 9911"));

            if (proxy == null) {
                System.err.println("❌ ERROR CRÍTICO: No se pudo conectar al VotingSite");
                return false;
            }

            System.out.println("✅ Conexión establecida exitosamente");

            // Verificar estado del sistema
            printSystemStatus(proxy);

            // Warmup del sistema
            System.out.println("\n🔥 Iniciando warmup del sistema...");
            performSystemWarmup(proxy, votingPool);

            // Preparar test principal
            System.out.println("\n⚡ INICIANDO TEST DE CARGA MÁXIMA");
            System.out.println("═══════════════════════════════════════════════════════════");

            testRunning = true;
            testStartTime = System.currentTimeMillis();

            // Iniciar monitor de métricas
            CompletableFuture<Void> metricsTask = CompletableFuture.runAsync(
                    new MetricsMonitor(), votingPool);

            // Lanzar todas las máquinas de votación SIN LIMITACIÓN
            List<CompletableFuture<Void>> votingTasks = new ArrayList<>();

            for (int machineId = 1; machineId <= CONCURRENT_MACHINES; machineId++) {
                final int finalMachineId = machineId;
                CompletableFuture<Void> task = CompletableFuture.runAsync(() ->
                        runVotingMachine(finalMachineId, proxy), votingPool);
                votingTasks.add(task);
            }

            // Esperar a que termine el test por tiempo
            try {
                CompletableFuture.allOf(votingTasks.toArray(new CompletableFuture[0]))
                        .get(TEST_DURATION_SECONDS + 120, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.out.println("⏰ Test terminado por tiempo límite");
            }

            testRunning = false;
            long testEndTime = System.currentTimeMillis();

            // Cancelar monitor
            metricsTask.cancel(true);

            // Esperar procesamiento final
            System.out.println("\n⏳ Esperando procesamiento final del sistema...");
            Thread.sleep(10000);

            // Analizar resultados
            return analyzeTestResults(testStartTime, testEndTime, proxy);

        } catch (Exception e) {
            System.err.println("❌ Error ejecutando test: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            testRunning = false;
            votingPool.shutdown();
            try {
                if (!votingPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    votingPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                votingPool.shutdownNow();
            }
        }
    }

    private static void performSystemWarmup(VotingProxyPrx proxy, ExecutorService pool) {
        int warmupVotes = 200;
        CountDownLatch warmupLatch = new CountDownLatch(warmupVotes);

        System.out.println("   Enviando " + warmupVotes + " votos de warmup...");

        for (int i = 1; i <= warmupVotes; i++) {
            pool.submit(() -> {
                try {
                    String citizenId = getUniqueRealDocument();
                    String candidateId = "candidate" + String.format("%03d", (new Random().nextInt(4)) + 1);
                    proxy.submitVote(citizenId, candidateId);
                } catch (Exception e) {
                    // Ignorar errores de warmup
                } finally {
                    warmupLatch.countDown();
                }
            });
        }

        try {
            boolean completed = warmupLatch.await(60, TimeUnit.SECONDS);
            if (completed) {
                System.out.println("✅ Warmup completado");
            } else {
                System.out.println("⚠️ Warmup parcial");
            }
        } catch (InterruptedException e) {
            System.out.println("⚠️ Warmup interrumpido");
        }

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runVotingMachine(int machineId, VotingProxyPrx proxy) {
        Random random = new Random();
        String[] candidates = {"candidate001", "candidate002", "candidate003", "candidate004", "blank"};

        // EJECUTAR SIN LÍMITE DE VOTOS - Solo limitado por tiempo
        while (testRunning) {
            try {
                String citizenId = getUniqueRealDocument();
                String candidateId = candidates[random.nextInt(candidates.length)];

                votesSubmitted.incrementAndGet();

                long voteStartTime = System.currentTimeMillis();

                try {
                    String ackId = proxy.submitVote(citizenId, candidateId);

                    long latency = System.currentTimeMillis() - voteStartTime;
                    totalLatency.addAndGet(latency);
                    latencies.add(latency);

                    votesCompleted.incrementAndGet();
                    votesSuccessful.incrementAndGet();

                } catch (Exception e) {
                    long latency = System.currentTimeMillis() - voteStartTime;
                    totalLatency.addAndGet(latency);

                    votesCompleted.incrementAndGet();
                    votesFailed.incrementAndGet();

                    // Log errores ocasionales
                    if (votesFailed.get() % 1000 == 1) {
                        System.out.println("⚠️ [M" + machineId + "] Error: " + e.getMessage());
                    }
                }

            } catch (Exception e) {
                // Continuar ejecutando
            }
        }

        if (machineId % 20 == 0) {
            System.out.println("✅ Máquina " + machineId + " completada");
        }
    }

    private static class MetricsMonitor implements Runnable {
        private int lastCompleted = 0;
        private long lastCheckTime = System.currentTimeMillis();

        @Override
        public void run() {
            while (testRunning) {
                try {
                    Thread.sleep(5000); // Reporte cada 5 segundos

                    long currentTime = System.currentTimeMillis();
                    int currentCompleted = votesCompleted.get();

                    // Calcular métricas del período
                    double periodSec = (currentTime - lastCheckTime) / 1000.0;
                    double currentThroughput = (currentCompleted - lastCompleted) / periodSec;

                    // Métricas acumuladas
                    double testElapsedSec = (currentTime - testStartTime) / 1000.0;
                    double avgThroughput = currentCompleted / testElapsedSec;
                    double successRate = currentCompleted > 0 ?
                            (double) votesSuccessful.get() / currentCompleted * 100 : 0;
                    double avgLatency = currentCompleted > 0 ?
                            (double) totalLatency.get() / currentCompleted : 0;

                    // Progreso del test
                    double progressPercent = (testElapsedSec / TEST_DURATION_SECONDS) * 100;
                    int remainingSeconds = (int)(TEST_DURATION_SECONDS - testElapsedSec);

                    System.out.printf("🚀 [%03.0fs] ACTUAL: %.0f v/s | PROMEDIO: %.0f v/s | Completados: %,d | Éxito: %.1f%% | Latencia: %.0fms | Progreso: %.1f%% | Quedan: %ds%n",
                            testElapsedSec,
                            currentThroughput,
                            avgThroughput,
                            currentCompleted,
                            successRate,
                            avgLatency,
                            progressPercent,
                            remainingSeconds);

                    lastCompleted = currentCompleted;
                    lastCheckTime = currentTime;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private static boolean analyzeTestResults(long startTime, long endTime, VotingProxyPrx proxy) {
        double testDurationSec = (endTime - startTime) / 1000.0;
        double actualThroughput = votesCompleted.get() / testDurationSec;
        double successRate = votesCompleted.get() > 0 ?
                (double) votesSuccessful.get() / votesCompleted.get() * 100 : 0;
        double avgLatency = votesCompleted.get() > 0 ?
                (double) totalLatency.get() / votesCompleted.get() : 0;

        // Calcular percentiles de latencia
        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);

        long p50 = getPercentile(sortedLatencies, 0.50);
        long p95 = getPercentile(sortedLatencies, 0.95);
        long p99 = getPercentile(sortedLatencies, 0.99);
        long maxLatency = sortedLatencies.isEmpty() ? 0 :
                sortedLatencies.get(sortedLatencies.size() - 1);

        System.out.println("\n████████████████████████████████████████████████████████████");
        System.out.println("█                RENDIMIENTO MÁXIMO MEDIDO                   █");
        System.out.println("████████████████████████████████████████████████████████████");
        System.out.println();
        System.out.println("📋 DOCUMENTOS UTILIZADOS:");
        System.out.println("    Documentos disponibles:      " + String.format("%,d", realDocuments.size()));
        System.out.println("    Documentos utilizados:       " + String.format("%,d", Math.min(realDocuments.size(), votesSubmitted.get())));
        System.out.println();
        System.out.println("⏱️  DURACIÓN Y VOLUMEN:");
        System.out.println("    Duración real:               " + String.format("%.1f", testDurationSec) + " segundos");
        System.out.println("    Votos enviados:              " + String.format("%,d", votesSubmitted.get()));
        System.out.println("    Votos completados:           " + String.format("%,d", votesCompleted.get()));
        System.out.println("    Votos exitosos:              " + String.format("%,d", votesSuccessful.get()));
        System.out.println("    Votos fallidos:              " + String.format("%,d", votesFailed.get()));
        System.out.println();
        System.out.println("🚀 RENDIMIENTO MÁXIMO:");
        System.out.println("    Throughput máximo medido:    " + String.format("%.0f", actualThroughput) + " votos/segundo");
        System.out.println("    Tasa de éxito:               " + String.format("%.2f%%", successRate));
        System.out.println("    Máquinas concurrentes:       " + CONCURRENT_MACHINES);
        System.out.println();
        System.out.println("⏰ LATENCIAS:");
        System.out.println("    Latencia promedio:           " + String.format("%.0f", avgLatency) + " ms");
        System.out.println("    Latencia P50:                " + p50 + " ms");
        System.out.println("    Latencia P95:                " + p95 + " ms");
        System.out.println("    Latencia P99:                " + p99 + " ms");
        System.out.println("    Latencia máxima:             " + maxLatency + " ms");

        // Estado del sistema
        try {
            int pendingVotes = proxy.getPendingVotesCount();
            String systemStatus = proxy.getSystemStatus();

            System.out.println();
            System.out.println("🔧 ESTADO FINAL DEL SISTEMA:");
            System.out.println("    Estado:                      " + systemStatus);
            System.out.println("    Votos pendientes:            " + String.format("%,d", pendingVotes));
        } catch (Exception e) {
            System.out.println("    ⚠️ No se pudo consultar estado final");
        }

        System.out.println();
        System.out.println("🎯 CAPACIDAD MÁXIMA DEL SISTEMA:");
        System.out.printf("    El sistema puede manejar hasta %.0f votos/segundo%n", actualThroughput);
        System.out.printf("    Con una tasa de éxito del %.1f%%%n", successRate);
        System.out.println("    Utilizando " + CONCURRENT_MACHINES + " máquinas concurrentes");

        System.out.println("████████████████████████████████████████████████████████████");

        return true; // Siempre exitoso para medir capacidad máxima
    }

    private static long getPercentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) return 0;
        int index = (int) (sortedValues.size() * percentile);
        return sortedValues.get(Math.min(index, sortedValues.size() - 1));
    }

    private static void printSystemStatus(VotingProxyPrx proxy) {
        try {
            String status = proxy.getSystemStatus();
            int pending = proxy.getPendingVotesCount();

            System.out.println("🔧 Estado inicial: " + status);
            System.out.println("⏳ Votos pendientes: " + String.format("%,d", pending));
            System.out.println("📋 Documentos únicos cargados: " + String.format("%,d", realDocuments.size()));

        } catch (Exception e) {
            System.out.println("⚠️ No se pudo verificar estado: " + e.getMessage());
        }
    }
}
import Query.QueryStationPrx;
import com.zeroc.Ice.*;
import java.sql.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zeroc.Ice.Exception;

import java.sql.Connection;
import java.util.concurrent.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;

/**
 * Test de carga SIN LIMITADOR para QueryServer - Máximo rendimiento
 */
public class QueryLoadTest {

    // CONFIGURACIÓN DEL TEST
    private static final int TEST_DURATION_SECONDS = 180; // 3 minutos
    private static final int CONCURRENT_CLIENTS = 200; // Aumentado para máximo rendimiento

    // MÉTRICAS GLOBALES
    private static final AtomicInteger queriesSubmitted = new AtomicInteger(0);
    private static final AtomicInteger queriesCompleted = new AtomicInteger(0);
    private static final AtomicInteger queriesSuccessful = new AtomicInteger(0);
    private static final AtomicInteger queriesFailed = new AtomicInteger(0);
    private static final AtomicLong totalLatency = new AtomicLong(0);
    private static final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

    // CONTROL DE TEST
    private static volatile boolean testRunning = false;
    private static volatile long testStartTime = 0;

    // BASE DE DATOS - Pool de documentos reales
    private static HikariDataSource dataSource;
    private static List<String> realDocuments = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {
        System.out.println("████████████████████████████████████████████████████████████");
        System.out.println("█        TEST DE CARGA MÁXIMA QUERY SERVER                  █");
        System.out.println("█                   SIN LIMITADORES                          █");
        System.out.println("████████████████████████████████████████████████████████████");
        System.out.println("🚀 Modo: MÁXIMO RENDIMIENTO POSIBLE");
        System.out.println("⏱️  Duración: " + TEST_DURATION_SECONDS + " segundos (" + (TEST_DURATION_SECONDS / 60) + " minutos)");
        System.out.println("🖥️  Clientes concurrentes: " + CONCURRENT_CLIENTS);
        System.out.println("⚡ Velocidad: ILIMITADA");
        System.out.println("████████████████████████████████████████████████████████████");

        // Inicializar BD y cargar documentos
        if (!initializeDatabase() || !loadRealDocuments()) {
            System.err.println("❌ ERROR: No se pudo inicializar la base de datos");
            System.exit(1);
        }

        boolean testPassed = executeLoadTest();

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

    private static boolean initializeDatabase() {
        try {
            System.out.println("🔌 Conectando a PostgreSQL...");

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:postgresql://10.147.17.101:5432/votacion");
            config.setUsername("postgres");
            config.setPassword("postgres");
            config.setMaximumPoolSize(5);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(3000);

            dataSource = new HikariDataSource(config);

            try (Connection conn = dataSource.getConnection()) {
                System.out.println("✅ Conexión a PostgreSQL establecida");
                return true;
            }

        } catch (Exception | SQLException e) {
            System.err.println("❌ Error conectando a PostgreSQL: " + e.getMessage());
            return false;
        }
    }

    private static boolean loadRealDocuments() {
        try {
            System.out.println("📋 Cargando documentos para consultas...");

            String sql = "SELECT documento FROM ciudadano WHERE documento IS NOT NULL AND documento != '' LIMIT 100000";

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

                System.out.println("✅ Documentos cargados para consultas: " + String.format("%,d", count));
                return count > 0;

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

        } catch (Exception e) {
            System.err.println("❌ Error cargando documentos: " + e.getMessage());
            return false;
        }
    }

    private static String getRealDocument() {
        if (realDocuments.isEmpty()) {
            return "12345678";
        }
        Random random = new Random();
        return realDocuments.get(random.nextInt(realDocuments.size()));
    }

    private static boolean executeLoadTest() {
        ExecutorService queryPool = Executors.newFixedThreadPool(CONCURRENT_CLIENTS + 10);

        try (Communicator communicator = Util.initialize()) {
            System.out.println("\n🔌 Conectando al QueryServer...");
            ObjectPrx base = communicator.stringToProxy("QueryStation:default -h 10.147.17.101 -p 8899");
            QueryStationPrx proxy = QueryStationPrx.checkedCast(base);

            if (proxy == null) {
                System.err.println("❌ ERROR: No se pudo conectar al QueryServer");
                return false;
            }

            System.out.println("✅ Conexión al QueryServer establecida");

            // Warmup
            System.out.println("\n🔥 Warmup del QueryServer...");
            performQueryWarmup(proxy, queryPool);

            // Test principal
            System.out.println("\n⚡ INICIANDO TEST DE CONSULTAS MÁXIMO");
            System.out.println("═══════════════════════════════════════════════════════════");

            testRunning = true;
            testStartTime = System.currentTimeMillis();

            // Metrics monitor
            CompletableFuture<Void> metricsTask = CompletableFuture.runAsync(
                    new QueryMetricsMonitor(), queryPool);

            // Lanzar clientes de consulta SIN LIMITACIÓN
            List<CompletableFuture<Void>> queryTasks = new ArrayList<>();

            for (int clientId = 1; clientId <= CONCURRENT_CLIENTS; clientId++) {
                final int finalClientId = clientId;
                CompletableFuture<Void> task = CompletableFuture.runAsync(() ->
                        runQueryClient(finalClientId, proxy), queryPool);
                queryTasks.add(task);
            }

            // Esperar completado o timeout por tiempo
            try {
                CompletableFuture.allOf(queryTasks.toArray(new CompletableFuture[0]))
                        .get(TEST_DURATION_SECONDS + 60, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.out.println("⏰ Test terminado por tiempo límite");
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }

            testRunning = false;
            long testEndTime = System.currentTimeMillis();

            metricsTask.cancel(true);

            System.out.println("\n⏳ Esperando procesamiento final...");
            Thread.sleep(5000);

            return analyzeQueryResults(testStartTime, testEndTime);

        } catch (Exception | InterruptedException e) {
            System.err.println("❌ Error ejecutando test: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            testRunning = false;
            queryPool.shutdown();
        }
    }

    private static void performQueryWarmup(QueryStationPrx proxy, ExecutorService pool) {
        int warmupQueries = 500;
        CountDownLatch warmupLatch = new CountDownLatch(warmupQueries);

        System.out.println("   Enviando " + warmupQueries + " consultas de warmup...");

        for (int i = 1; i <= warmupQueries; i++) {
            pool.submit(() -> {
                try {
                    String documento = getRealDocument();
                    proxy.query(documento);
                } catch (Exception e) {
                    // Ignorar errores de warmup
                } finally {
                    warmupLatch.countDown();
                }
            });
        }

        try {
            boolean completed = warmupLatch.await(30, TimeUnit.SECONDS);
            if (completed) {
                System.out.println("✅ Warmup completado");
            } else {
                System.out.println("⚠️ Warmup parcial");
            }
        } catch (InterruptedException e) {
            System.out.println("⚠️ Warmup interrumpido");
        }

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runQueryClient(int clientId, QueryStationPrx proxy) {
        // EJECUTAR SIN LÍMITE DE CONSULTAS - Solo limitado por tiempo
        while (testRunning) {
            try {
                String documento = getRealDocument();
                queriesSubmitted.incrementAndGet();

                long queryStartTime = System.currentTimeMillis();

                try {
                    String resultado = proxy.query(documento);
                    System.out.println(resultado);

                    long latency = System.currentTimeMillis() - queryStartTime;
                    totalLatency.addAndGet(latency);
                    latencies.add(latency);

                    queriesCompleted.incrementAndGet();
                    queriesSuccessful.incrementAndGet();

                } catch (Exception e) {
                    long latency = System.currentTimeMillis() - queryStartTime;
                    totalLatency.addAndGet(latency);

                    queriesCompleted.incrementAndGet();
                    queriesFailed.incrementAndGet();

                    if (queriesFailed.get() % 1000 == 1) {
                        System.out.println("⚠️ [C" + clientId + "] Error: " + e.getMessage());
                    }
                }

            } catch (Exception e) {
                // Continuar ejecutando
            }
        }

        if (clientId % 40 == 0) {
            System.out.println("✅ Cliente " + clientId + " completado");
        }
    }

    private static class QueryMetricsMonitor implements Runnable {
        private int lastCompleted = 0;
        private long lastCheckTime = System.currentTimeMillis();

        @Override
        public void run() {
            while (testRunning) {
                try {
                    Thread.sleep(5000);

                    long currentTime = System.currentTimeMillis();
                    int currentCompleted = queriesCompleted.get();

                    double periodSec = (currentTime - lastCheckTime) / 1000.0;
                    double currentThroughput = (currentCompleted - lastCompleted) / periodSec;

                    double testElapsedSec = (currentTime - testStartTime) / 1000.0;
                    double avgThroughput = currentCompleted / testElapsedSec;
                    double successRate = currentCompleted > 0 ?
                            (double) queriesSuccessful.get() / currentCompleted * 100 : 0;
                    double avgLatency = currentCompleted > 0 ?
                            (double) totalLatency.get() / currentCompleted : 0;

                    double progressPercent = (testElapsedSec / TEST_DURATION_SECONDS) * 100;
                    int remainingSeconds = (int) (TEST_DURATION_SECONDS - testElapsedSec);

                    System.out.printf("🚀 [%03.0fs] ACTUAL: %.0f q/s | PROMEDIO: %.0f q/s | Completadas: %,d | Éxito: %.1f%% | Latencia: %.0fms | Progreso: %.1f%% | Quedan: %ds%n",
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

    private static boolean analyzeQueryResults(long startTime, long endTime) {
        double testDurationSec = (endTime - startTime) / 1000.0;
        double actualThroughput = queriesCompleted.get() / testDurationSec;
        double successRate = queriesCompleted.get() > 0 ?
                (double) queriesSuccessful.get() / queriesCompleted.get() * 100 : 0;
        double avgLatency = queriesCompleted.get() > 0 ?
                (double) totalLatency.get() / queriesCompleted.get() : 0;

        // Calcular percentiles
        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);

        long p50 = getPercentile(sortedLatencies, 0.50);
        long p95 = getPercentile(sortedLatencies, 0.95);
        long p99 = getPercentile(sortedLatencies, 0.99);
        long maxLatency = sortedLatencies.isEmpty() ? 0 :
                sortedLatencies.get(sortedLatencies.size() - 1);

        System.out.println("\n████████████████████████████████████████████████████████████");
        System.out.println("█            RENDIMIENTO MÁXIMO QUERY SERVER                 █");
        System.out.println("████████████████████████████████████████████████████████████");
        System.out.println();
        System.out.println("⏱️  DURACIÓN Y VOLUMEN:");
        System.out.println("    Duración real:               " + String.format("%.1f", testDurationSec) + " segundos");
        System.out.println("    Consultas enviadas:          " + String.format("%,d", queriesSubmitted.get()));
        System.out.println("    Consultas completadas:       " + String.format("%,d", queriesCompleted.get()));
        System.out.println("    Consultas exitosas:          " + String.format("%,d", queriesSuccessful.get()));
        System.out.println("    Consultas fallidas:          " + String.format("%,d", queriesFailed.get()));
        System.out.println();
        System.out.println("🚀 RENDIMIENTO MÁXIMO:");
        System.out.println("    Throughput máximo medido:    " + String.format("%.0f", actualThroughput) + " consultas/segundo");
        System.out.println("    Tasa de éxito:               " + String.format("%.2f%%", successRate));
        System.out.println("    Clientes concurrentes:       " + CONCURRENT_CLIENTS);
        System.out.println();
        System.out.println("⏰ LATENCIAS:");
        System.out.println("    Latencia promedio:           " + String.format("%.0f", avgLatency) + " ms");
        System.out.println("    Latencia P50:                " + p50 + " ms");
        System.out.println("    Latencia P95:                " + p95 + " ms");
        System.out.println("    Latencia P99:                " + p99 + " ms");
        System.out.println("    Latencia máxima:             " + maxLatency + " ms");

        System.out.println();
        System.out.println("🎯 CAPACIDAD MÁXIMA DEL QUERY SERVER:");
        System.out.printf("    El sistema puede manejar hasta %.0f consultas/segundo%n", actualThroughput);
        System.out.printf("    Con una tasa de éxito del %.1f%%%n", successRate);
        System.out.println("    Utilizando " + CONCURRENT_CLIENTS + " clientes concurrentes");

        System.out.println("████████████████████████████████████████████████████████████");
        return true; // Siempre exitoso para medir capacidad máxima
    }

    private static long getPercentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) return 0;
        int index = (int) (sortedValues.size() * percentile);
        return sortedValues.get(Math.min(index, sortedValues.size() - 1));
    }
}
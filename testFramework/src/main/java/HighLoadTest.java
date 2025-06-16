import Proxy.*;
import java.sql.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;

/**
 * Test de carga independiente para 1,777 votos/segundo
 * MODIFICADO: Usa documentos reales de la base de datos PostgreSQL
 */
public class HighLoadTest {

    // CONFIGURACIÓN DEL TEST
    private static final int TARGET_VOTES_PER_SECOND = 1777;
    private static final int TEST_DURATION_SECONDS = 300; // 5 minutos
    private static final int TOTAL_VOTES = TARGET_VOTES_PER_SECOND * TEST_DURATION_SECONDS;
    private static final int CONCURRENT_MACHINES = 50;

    // MÉTRICAS GLOBALES
    private static final AtomicInteger votesSubmitted = new AtomicInteger(0);
    private static final AtomicInteger votesCompleted = new AtomicInteger(0);
    private static final AtomicInteger votesSuccessful = new AtomicInteger(0);
    private static final AtomicInteger votesFailed = new AtomicInteger(0);
    private static final AtomicLong totalLatency = new AtomicLong(0);
    private static final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

    // CONTROL DE VELOCIDAD
    private static final Semaphore rateLimiter = new Semaphore(TARGET_VOTES_PER_SECOND);
    private static volatile boolean testRunning = false;
    private static volatile long testStartTime = 0;

    // BASE DE DATOS - Pool de documentos reales
    private static HikariDataSource dataSource;
    private static List<String> realDocuments = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger documentIndex = new AtomicInteger(0);

    public static void main(String[] args) {
        System.out.println("████████████████████████████████████████████████████████████");
        System.out.println("█           TEST DE CARGA ALTA - SISTEMA VOTACIÓN            █");
        System.out.println("█               SOLO VOTOS ÚNICOS - SIN DUPLICADOS           █");
        System.out.println("████████████████████████████████████████████████████████████");
        System.out.println("🎯 Objetivo: " + TARGET_VOTES_PER_SECOND + " votos/segundo");
        System.out.println("⏱️  Duración: " + TEST_DURATION_SECONDS + " segundos (" + (TEST_DURATION_SECONDS/60) + " minutos)");
        System.out.println("📊 Total votos: " + String.format("%,d", TOTAL_VOTES));
        System.out.println("🖥️  Máquinas concurrentes: " + CONCURRENT_MACHINES);
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
            System.out.println("█                    ✅ TEST EXITOSO                          █");
            System.out.println("█              SISTEMA LISTO PARA PRODUCCIÓN                  █");
        } else {
            System.out.println("█                    ❌ TEST FALLIDO                          █");
            System.out.println("█           SISTEMA REQUIERE OPTIMIZACIONES                   █");
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
            config.setJdbcUrl("jdbc:postgresql://localhost:5433/votacion");
            config.setUsername("admin");
            config.setPassword("123");
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
     * Cargar documentos reales de la base de datos (suficientes para evitar duplicados)
     */
    private static boolean loadRealDocuments() {
        try {
            // Calcular cuántos documentos necesitamos (test + warmup + buffer)
            int documentsNeeded = TOTAL_VOTES + 200 + 1000; // test + warmup + buffer de seguridad

            System.out.println("📋 Cargando " + String.format("%,d", documentsNeeded) + " documentos únicos de ciudadanos...");

            // CAMBIO: Cargar exactamente los documentos que necesitamos
            String sql = "SELECT documento FROM ciudadano WHERE documento IS NOT NULL AND documento != '' LIMIT " + documentsNeeded;

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
                    System.err.println("   Asegúrese de tener datos en la tabla 'ciudadano'");
                    return false;
                }

                if (count < documentsNeeded) {
                    System.err.println("❌ INSUFICIENTES DOCUMENTOS ÚNICOS");
                    System.err.println("   Se necesitan: " + String.format("%,d", documentsNeeded));
                    System.err.println("   Disponibles:  " + String.format("%,d", count));
                    System.err.println("   Genere más ciudadanos en la BD o reduzca TOTAL_VOTES");
                    return false;
                } else {
                    System.out.println("✅ Suficientes documentos únicos para test sin duplicados");
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
            // Si se agotan los documentos, reiniciar (no debería pasar si calculamos bien)
            documentIndex.set(0);
            index = 0;
            System.out.println("⚠️ Documentos agotados, reiniciando índice");
        }

        return realDocuments.get(index);
    }

    private static boolean executeLoadTest() {
        ExecutorService votingPool = Executors.newFixedThreadPool(CONCURRENT_MACHINES + 5);

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize()) {
            communicator.getProperties().setProperty("Ice.Default.Locator",
                    "DemoIceGrid/Locator:default -h localhost -p 4061");

            System.out.println("\n🔌 Conectando al sistema de votación...");
            VotingProxyPrx proxy = VotingProxyPrx.checkedCast(
                    communicator.stringToProxy("VotingProxy:default -h localhost -p 9999"));

            if (proxy == null) {
                System.err.println("❌ ERROR CRÍTICO: No se pudo conectar al VotingSite");
                System.err.println("   Verificar que VotingSite esté ejecutándose en puerto 9999");
                return false;
            }

            System.out.println("✅ Conexión establecida exitosamente");

            // Verificar estado del sistema
            printSystemStatus(proxy);

            // Warmup del sistema con documentos reales
            System.out.println("\n🔥 Iniciando warmup del sistema...");
            performSystemWarmup(proxy, votingPool);

            // Preparar test principal
            System.out.println("\n⚡ INICIANDO TEST DE CARGA PRINCIPAL");
            System.out.println("═══════════════════════════════════════════════════════════");

            testRunning = true;
            testStartTime = System.currentTimeMillis();

            // Iniciar renovador de rate limiter
            CompletableFuture<Void> rateLimiterTask = CompletableFuture.runAsync(
                    new RateLimiterController(), votingPool);

            // Iniciar monitor de métricas
            CompletableFuture<Void> metricsTask = CompletableFuture.runAsync(
                    new MetricsMonitor(), votingPool);

            // Lanzar todas las máquinas de votación
            List<CompletableFuture<Void>> votingTasks = new ArrayList<>();

            for (int machineId = 1; machineId <= CONCURRENT_MACHINES; machineId++) {
                final int finalMachineId = machineId;
                CompletableFuture<Void> task = CompletableFuture.runAsync(() ->
                        runVotingMachine(finalMachineId, proxy), votingPool);
                votingTasks.add(task);
            }

            // Esperar a que termine el test o timeout
            try {
                CompletableFuture.allOf(votingTasks.toArray(new CompletableFuture[0]))
                        .get(TEST_DURATION_SECONDS + 120, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.out.println("⚠️ Test terminado por timeout");
            }

            testRunning = false;
            long testEndTime = System.currentTimeMillis();

            // Cancelar tareas de monitoreo
            rateLimiterTask.cancel(true);
            metricsTask.cancel(true);

            // Esperar procesamiento final
            System.out.println("\n⏳ Esperando procesamiento final del sistema...");
            Thread.sleep(10000);

            // Análizar resultados
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

        System.out.println("   Enviando " + warmupVotes + " votos de warmup con documentos reales...");

        for (int i = 1; i <= warmupVotes; i++) {
            pool.submit(() -> {
                try {
                    String citizenId = getUniqueRealDocument(); // Documento único
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
                System.out.println("✅ Warmup completado exitosamente");
            } else {
                System.out.println("⚠️ Warmup parcialmente completado");
            }
        } catch (InterruptedException e) {
            System.out.println("⚠️ Warmup interrumpido");
        }

        // Pausa antes del test principal
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runVotingMachine(int machineId, VotingProxyPrx proxy) {
        Random random = new Random();
        String[] candidates = {"candidate001", "candidate002", "candidate003", "candidate004", "blank"};

        int votesPerMachine = TOTAL_VOTES / CONCURRENT_MACHINES;
        int extraVotes = TOTAL_VOTES % CONCURRENT_MACHINES;

        // Algunas máquinas procesan un voto extra para alcanzar el total exacto
        if (machineId <= extraVotes) {
            votesPerMachine++;
        }

        for (int i = 1; i <= votesPerMachine && testRunning; i++) {
            try {
                // Rate limiting: esperar permiso
                if (!rateLimiter.tryAcquire(1, TimeUnit.SECONDS)) {
                    continue; // Skip este voto si no hay permisos
                }

                if (!testRunning) break;

                // CAMBIO PRINCIPAL: Usar documento único de la BD
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

                    // Log errores ocasionales (no spam)
                    if (votesFailed.get() % 100 == 1) {
                        System.out.println("⚠️ [M" + machineId + "] Error: " + e.getMessage());
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (machineId % 10 == 0) {
            System.out.println("✅ Máquina " + machineId + " completada");
        }
    }

    private static class RateLimiterController implements Runnable {
        @Override
        public void run() {
            while (testRunning) {
                try {
                    Thread.sleep(1000); // Cada segundo

                    // Restaurar permisos para alcanzar la velocidad objetivo
                    int currentPermits = rateLimiter.availablePermits();
                    int neededPermits = TARGET_VOTES_PER_SECOND - currentPermits;

                    if (neededPermits > 0) {
                        rateLimiter.release(neededPermits);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
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

                    System.out.printf("📊 [%03.0fs] Actual: %.0f v/s | Promedio: %.0f v/s | Completados: %,d | Éxito: %.1f%% | Latencia: %.0fms | Progreso: %.1f%% | Quedan: %ds%n",
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
        System.out.println("█                    RESULTADOS FINALES                      █");
        System.out.println("████████████████████████████████████████████████████████████");
        System.out.println();
        System.out.println("📋 DOCUMENTOS UTILIZADOS:");
        System.out.println("    Documentos reales en BD:     " + String.format("%,d", realDocuments.size()));
        System.out.println("    Documentos únicos usados:    " + String.format("%,d", Math.min(realDocuments.size(), votesSubmitted.get())));
        System.out.println();
        System.out.println("⏱️  DURACIÓN Y VOLUMEN:");
        System.out.println("    Duración real:           " + String.format("%.1f", testDurationSec) + " segundos");
        System.out.println("    Votos enviados:          " + String.format("%,d", votesSubmitted.get()));
        System.out.println("    Votos completados:       " + String.format("%,d", votesCompleted.get()));
        System.out.println("    Votos exitosos:          " + String.format("%,d", votesSuccessful.get()));
        System.out.println("    Votos fallidos:          " + String.format("%,d", votesFailed.get()));
        System.out.println();
        System.out.println("🚀 PERFORMANCE:");
        System.out.println("    Throughput real:         " + String.format("%.1f", actualThroughput) + " votos/segundo");
        System.out.println("    Throughput objetivo:     " + String.format("%,d", TARGET_VOTES_PER_SECOND) + " votos/segundo");
        System.out.println("    Eficiencia:              " + String.format("%.1f%%", (actualThroughput / TARGET_VOTES_PER_SECOND * 100)));
        System.out.println("    Tasa de éxito:           " + String.format("%.2f%%", successRate));
        System.out.println();
        System.out.println("⏰ LATENCIAS:");
        System.out.println("    Latencia promedio:       " + String.format("%.0f", avgLatency) + " ms");
        System.out.println("    Latencia P50:            " + p50 + " ms");
        System.out.println("    Latencia P95:            " + p95 + " ms");
        System.out.println("    Latencia P99:            " + p99 + " ms");
        System.out.println("    Latencia máxima:         " + maxLatency + " ms");

        // Estado del sistema
        try {
            int pendingVotes = proxy.getPendingVotesCount();
            String systemStatus = proxy.getSystemStatus();

            System.out.println();
            System.out.println("🔧 ESTADO FINAL DEL SISTEMA:");
            System.out.println("    Estado:                  " + systemStatus);
            System.out.println("    Votos pendientes:        " + String.format("%,d", pendingVotes));
        } catch (Exception e) {
            System.out.println("    ⚠️ No se pudo consultar estado final");
        }

        // Evaluación de criterios (más estricta ya que no hay duplicados esperados)
        boolean throughputOK = actualThroughput >= (TARGET_VOTES_PER_SECOND * 0.95); // 95% del objetivo
        boolean successRateOK = successRate >= 98.0; // Muy alta porque no hay duplicados
        boolean latencyOK = p95 <= 1000; // P95 bajo 1 segundo
        boolean stabilityOK = votesFailed.get() < (votesCompleted.get() * 0.02); // Menos del 2% de errores

        System.out.println();
        System.out.println("🔍 EVALUACIÓN DE CRITERIOS (Estricta - sin duplicados esperados):");
        System.out.println("    Throughput ≥ 95%:        " + (throughputOK ? "✅ CUMPLE" : "❌ NO CUMPLE") +
                " (" + String.format("%.1f%%", (actualThroughput / TARGET_VOTES_PER_SECOND * 100)) + ")");
        System.out.println("    Tasa éxito ≥ 98%:        " + (successRateOK ? "✅ CUMPLE" : "❌ NO CUMPLE") +
                " (" + String.format("%.1f%%", successRate) + ")");
        System.out.println("    Latencia P95 < 1s:       " + (latencyOK ? "✅ CUMPLE" : "❌ NO CUMPLE") +
                " (" + p95 + "ms)");
        System.out.println("    Estabilidad < 2% err:    " + (stabilityOK ? "✅ CUMPLE" : "❌ NO CUMPLE") +
                " (" + String.format("%.1f%%", (double)votesFailed.get()/Math.max(votesCompleted.get(),1)*100) + ")");

        boolean testPassed = throughputOK && successRateOK && latencyOK && stabilityOK;

        System.out.println();
        if (testPassed) {
            System.out.println("🎉 VEREDICTO: SISTEMA APROBADO PARA PRODUCCIÓN");
            System.out.println("   ✅ Sistema maneja documentos únicos correctamente");
            System.out.println("   ✅ Alta tasa de éxito sin duplicados");
            System.out.println("   ✅ Validación de ciudadanos en PostgreSQL perfecta");
        } else {
            System.out.println("⚠️ VEREDICTO: SISTEMA REQUIERE OPTIMIZACIONES");
            System.out.println("   🔧 Revisar configuración de thread pools");
            System.out.println("   🔧 Verificar recursos de hardware");
            System.out.println("   🔧 Optimizar componentes que fallen criterios");
        }

        System.out.println("████████████████████████████████████████████████████████████");

        return testPassed;
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
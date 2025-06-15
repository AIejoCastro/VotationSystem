//
// CentralVoteManager - Version centralizada del VoteManager original
// Toda la lógica de votación centralizada con acceso exclusivo a base de datos
//

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

public class CentralVoteManager {
    private static final CentralVoteManager instance = new CentralVoteManager();

    // OPTIMIZACIÓN: StampedLock para mejor concurrencia de lectura
    private final StampedLock lock = new StampedLock();

    // OPTIMIZACIÓN: Partitioning para reducir contención
    private final int PARTITION_COUNT = 16;
    private final Map<String, String>[] citizenVotesPartitions;
    private final StampedLock[] partitionLocks;

    // OPTIMIZACIÓN: Queue con mayor capacidad y múltiples workers
    private final BlockingQueue<CentralVoteCommand> queue = new LinkedBlockingQueue<>(50000);
    private final ExecutorService writerPool;
    private final int WRITER_THREAD_COUNT = 8;

    // MÉTRICAS de performance
    private final AtomicInteger totalVotes = new AtomicInteger(0);
    private final AtomicInteger duplicateVotes = new AtomicInteger(0);
    private final AtomicInteger queueOverflows = new AtomicInteger(0);
    private volatile long lastStatsTime = System.currentTimeMillis();

    @SuppressWarnings("unchecked")
    private CentralVoteManager() {
        // Inicializar particiones
        citizenVotesPartitions = new Map[PARTITION_COUNT];
        partitionLocks = new StampedLock[PARTITION_COUNT];

        for (int i = 0; i < PARTITION_COUNT; i++) {
            citizenVotesPartitions[i] = new ConcurrentHashMap<>(5000);
            partitionLocks[i] = new StampedLock();
        }

        // OPTIMIZACIÓN: Pool de threads optimizado para escritura
        writerPool = Executors.newFixedThreadPool(WRITER_THREAD_COUNT, r -> {
            Thread t = new Thread(r, "CentralVoteWriter-" + Thread.currentThread().getId());
            t.setDaemon(true);
            return t;
        });

        // Iniciar workers
        for (int i = 0; i < WRITER_THREAD_COUNT; i++) {
            writerPool.submit(new CentralVoteWriter(queue));
        }

        // NUEVA: Cargar votos existentes antes de iniciar métricas
        loadExistingVotes();

        // NUEVA: Thread para métricas periódicas
        startMetricsReporter();

        System.out.println("[CentralVoteManager] Inicializado con " + PARTITION_COUNT +
                " particiones y " + WRITER_THREAD_COUNT + " workers");
    }

    public static CentralVoteManager getInstance() {
        return instance;
    }

    /**
     * Recibe voto con partitioning optimizado para alta concurrencia
     */
    public VoteResult receiveVote(String citizenId, String candidateId) {
        totalVotes.incrementAndGet();

        // OPTIMIZACIÓN: Determinar partición basada en hash del citizenId
        int partition = Math.abs(citizenId.hashCode()) % PARTITION_COUNT;
        Map<String, String> citizenVotes = citizenVotesPartitions[partition];
        StampedLock partitionLock = partitionLocks[partition];

        // PASO 1: Verificación optimista con read lock
        long stamp = partitionLock.tryOptimisticRead();
        String existingVote = citizenVotes.get(citizenId);

        if (!partitionLock.validate(stamp)) {
            // Fallback a read lock si optimistic falló
            stamp = partitionLock.readLock();
            try {
                existingVote = citizenVotes.get(citizenId);
            } finally {
                partitionLock.unlockRead(stamp);
            }
        }

        if (existingVote != null) {
            // Ciudadano ya votó - incrementar contador de duplicados
            duplicateVotes.incrementAndGet();

            System.out.println("[CentralVoteManager] Duplicado en partición " + partition +
                    ": " + citizenId + " ya votó por " + existingVote);

            if (existingVote.equals(candidateId)) {
                return new VoteResult(false, true, existingVote, "Voto duplicado idéntico");
            } else {
                return new VoteResult(false, true, existingVote, "Ciudadano ya votó por candidato diferente");
            }
        }

        // PASO 2: Registrar nuevo voto con write lock
        stamp = partitionLock.writeLock();
        try {
            // Double-check después de obtener write lock
            existingVote = citizenVotes.get(citizenId);
            if (existingVote != null) {
                duplicateVotes.incrementAndGet();
                return new VoteResult(false, true, existingVote, "Voto duplicado detectado en write lock");
            }

            // Registrar voto nuevo ATÓMICAMENTE
            citizenVotes.put(citizenId, candidateId);

        } finally {
            partitionLock.unlockWrite(stamp);
        }

        // PASO 3: Agregar a cola de escritura con overflow handling
        CentralVoteCommand command = new CentralVoteCommand(citizenId, candidateId);
        boolean queued = queue.offer(command);

        if (!queued) {
            queueOverflows.incrementAndGet();
            System.err.println("[CentralVoteManager] OVERFLOW: Cola de escritura llena. Intentando escritura directa.");

            // Fallback: escritura directa en caso de overflow
            try {
                command.persist(new CentralVoteDAO());
            } catch (Exception e) {
                System.err.println("[CentralVoteManager] ERROR en escritura directa: " + e.getMessage());
            }
        }

        System.out.println("[CentralVoteManager] Nuevo voto válido en partición " + partition +
                ": " + citizenId + " -> " + candidateId);

        return new VoteResult(true, false, candidateId, "Voto registrado exitosamente");
    }

    /**
     * Obtener voto existente con partitioning
     */
    public String getExistingVote(String citizenId) {
        if (citizenId == null) return null;

        int partition = Math.abs(citizenId.hashCode()) % PARTITION_COUNT;
        Map<String, String> citizenVotes = citizenVotesPartitions[partition];
        StampedLock partitionLock = partitionLocks[partition];

        // Lectura optimista
        long stamp = partitionLock.tryOptimisticRead();
        String vote = citizenVotes.get(citizenId);

        if (!partitionLock.validate(stamp)) {
            // Fallback a read lock
            stamp = partitionLock.readLock();
            try {
                vote = citizenVotes.get(citizenId);
            } finally {
                partitionLock.unlockRead(stamp);
            }
        }

        return vote;
    }

    /**
     * Verificación rápida de voto existente
     */
    public boolean hasVoted(String citizenId) {
        return getExistingVote(citizenId) != null;
    }

    /**
     * Obtener todos los votantes (agregando todas las particiones)
     */
    public Set<String> getAllVoters() {
        Set<String> allVoters = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < PARTITION_COUNT; i++) {
            Map<String, String> partition = citizenVotesPartitions[i];
            StampedLock lock = partitionLocks[i];

            long stamp = lock.readLock();
            try {
                allVoters.addAll(partition.keySet());
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return allVoters;
    }

    /**
     * Resultado de procesamiento de voto
     */
    public static class VoteResult {
        public final boolean success;
        public final boolean isDuplicate;
        public final String candidateId;
        public final String message;

        public VoteResult(boolean success, boolean isDuplicate, String candidateId, String message) {
            this.success = success;
            this.isDuplicate = isDuplicate;
            this.candidateId = candidateId;
            this.message = message;
        }

        @Override
        public String toString() {
            return String.format("VoteResult{success=%s, isDuplicate=%s, candidateId='%s', message='%s'}",
                    success, isDuplicate, candidateId, message);
        }
    }

    /**
     * Estadísticas optimizadas con métricas de performance
     */
    public VotingStats getStats() {
        int totalVotersCount = 0;
        for (int i = 0; i < PARTITION_COUNT; i++) {
            totalVotersCount += citizenVotesPartitions[i].size();
        }

        return new VotingStats(
                totalVotersCount,
                queue.size(),
                totalVotes.get(),
                duplicateVotes.get(),
                queueOverflows.get(),
                calculateThroughput()
        );
    }

    /**
     * Calcular throughput en votos/segundo
     */
    private double calculateThroughput() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastStatsTime;

        if (timeDiff > 0) {
            return (double) totalVotes.get() / (timeDiff / 1000.0);
        }
        return 0.0;
    }

    /**
     * Debug info con métricas por partición
     */
    public void printDebugInfo() {
        System.out.println("[CentralVoteManager] === DEBUG INFO ===");

        int totalCitizens = 0;
        for (int i = 0; i < PARTITION_COUNT; i++) {
            int partitionSize = citizenVotesPartitions[i].size();
            totalCitizens += partitionSize;

            if (partitionSize > 0) {
                System.out.println("Partición " + i + ": " + partitionSize + " votantes");
            }
        }

        System.out.println("Total ciudadanos registrados: " + totalCitizens);
        System.out.println("Votos en cola de escritura: " + queue.size());
        System.out.println("Total votos procesados: " + totalVotes.get());
        System.out.println("Duplicados detectados: " + duplicateVotes.get());
        System.out.println("Overflows de cola: " + queueOverflows.get());
        System.out.println("Throughput estimado: " + String.format("%.2f", calculateThroughput()) + " votos/seg");
        System.out.println("Workers activos: " + WRITER_THREAD_COUNT);
        System.out.println("================================");
    }

    /**
     * Reporter de métricas en background
     */
    private void startMetricsReporter() {
        Thread reporter = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30000); // Reporte cada 30 segundos

                    if (totalVotes.get() > 0) {
                        VotingStats stats = getStats();
                        System.out.println("[CentralVoteManager-METRICS] " + stats.toString());

                        // Reset counters para próximo período
                        lastStatsTime = System.currentTimeMillis();
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        reporter.setDaemon(true);
        reporter.setName("CentralVoteManager-MetricsReporter");
        reporter.start();
    }

    /**
     * Limpiar estado para testing
     */
    public synchronized void clearForTesting() {
        System.out.println("[CentralVoteManager] Limpiando estado para testing...");

        for (int i = 0; i < PARTITION_COUNT; i++) {
            StampedLock lock = partitionLocks[i];
            long stamp = lock.writeLock();
            try {
                citizenVotesPartitions[i].clear();
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        // Limpiar cola
        queue.clear();

        // Reset contadores
        totalVotes.set(0);
        duplicateVotes.set(0);
        queueOverflows.set(0);
        lastStatsTime = System.currentTimeMillis();

        System.out.println("[CentralVoteManager] Estado limpiado completamente");
    }

    /**
     * Shutdown graceful
     */
    public void shutdown() {
        System.out.println("[CentralVoteManager] Iniciando shutdown...");

        writerPool.shutdown();
        try {
            if (!writerPool.awaitTermination(30, TimeUnit.SECONDS)) {
                writerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            writerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("[CentralVoteManager] Shutdown completo. Votos pendientes en cola: " + queue.size());
    }

    /**
     * Cargar votos existentes desde el archivo CSV al inicializar
     */
    private void loadExistingVotes() {
        System.out.println("[CentralVoteManager] Cargando votos existentes...");

        File voteFile = new File("config/db/central-votes.csv");
        if (!voteFile.exists()) {
            System.out.println("[CentralVoteManager] No hay archivo de votos previo - iniciando limpio");
            return;
        }

        int loadedVotes = 0;
        int duplicatesIgnored = 0;

        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(voteFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    String citizenId = parts[0].trim();
                    String candidateId = parts[1].trim();

                    // Determinar partición
                    int partition = Math.abs(citizenId.hashCode()) % PARTITION_COUNT;
                    Map<String, String> citizenVotes = citizenVotesPartitions[partition];

                    // Verificar si ya existe (para manejar duplicados en archivo)
                    if (!citizenVotes.containsKey(citizenId)) {
                        citizenVotes.put(citizenId, candidateId);
                        loadedVotes++;
                    } else {
                        duplicatesIgnored++;
                    }
                }
            }

            // Actualizar contador de votos procesados
            totalVotes.set(loadedVotes);

            System.out.println("[CentralVoteManager] ✅ Carga completada:");
            System.out.println("   Votos cargados: " + loadedVotes);
            System.out.println("   Duplicados ignorados: " + duplicatesIgnored);
            System.out.println("   Votantes únicos: " + getAllVoters().size());

        } catch (java.io.IOException e) {
            System.err.println("[CentralVoteManager] Error cargando votos existentes: " + e.getMessage());
            System.out.println("[CentralVoteManager] Continuando con estado limpio");
        }
    }
    public java.util.Map<String, Integer> getVotesByCandidate() {
        java.util.Map<String, Integer> voteCount = new java.util.HashMap<>();

        for (int i = 0; i < PARTITION_COUNT; i++) {
            Map<String, String> partition = citizenVotesPartitions[i];
            StampedLock lock = partitionLocks[i];

            long stamp = lock.readLock();
            try {
                for (String candidateId : partition.values()) {
                    voteCount.merge(candidateId, 1, Integer::sum);
                }
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return voteCount;
    }

    /**
     * Obtener estadísticas detalladas para reportes
     */
    public DetailedVotingStats getDetailedStats() {
        java.util.Map<String, Integer> votesByCandidate = getVotesByCandidate();
        VotingStats basicStats = getStats();

        return new DetailedVotingStats(basicStats, votesByCandidate);
    }

    /**
     * Estadísticas detalladas con información de candidatos
     */
    public static class DetailedVotingStats {
        public final VotingStats basicStats;
        public final java.util.Map<String, Integer> votesByCandidate;
        public final int totalValidVotes;
        public final String winningCandidate;
        public final int winningVotes;

        public DetailedVotingStats(VotingStats basicStats, java.util.Map<String, Integer> votesByCandidate) {
            this.basicStats = basicStats;
            this.votesByCandidate = votesByCandidate;
            this.totalValidVotes = votesByCandidate.values().stream().mapToInt(Integer::intValue).sum();

            // Determinar ganador
            java.util.Map.Entry<String, Integer> winner = votesByCandidate.entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .orElse(null);

            if (winner != null) {
                this.winningCandidate = winner.getKey();
                this.winningVotes = winner.getValue();
            } else {
                this.winningCandidate = null;
                this.winningVotes = 0;
            }
        }

        @Override
        public String toString() {
            return String.format("DetailedVotingStats{totalVoters=%d, totalValidVotes=%d, winner='%s' (%d votos)}",
                    basicStats.totalVoters, totalValidVotes, winningCandidate, winningVotes);
        }
    }
    public static class VotingStats {
        public final int totalVoters;
        public final int pendingVotes;
        public final int totalProcessed;
        public final int duplicatesDetected;
        public final int queueOverflows;
        public final double throughputVotesPerSec;

        public VotingStats(int totalVoters, int pendingVotes, int totalProcessed,
                           int duplicatesDetected, int queueOverflows, double throughputVotesPerSec) {
            this.totalVoters = totalVoters;
            this.pendingVotes = pendingVotes;
            this.totalProcessed = totalProcessed;
            this.duplicatesDetected = duplicatesDetected;
            this.queueOverflows = queueOverflows;
            this.throughputVotesPerSec = throughputVotesPerSec;
        }

        @Override
        public String toString() {
            return String.format("VotingStats{voters=%d, pending=%d, processed=%d, " +
                            "duplicates=%d, overflows=%d, throughput=%.2f v/s}",
                    totalVoters, pendingVotes, totalProcessed, duplicatesDetected,
                    queueOverflows, throughputVotesPerSec);
        }
    }
}
//
// CentralACKManager - Version centralizada del ACKManager original
// Manejo centralizado de ACKs con acceso exclusivo a archivos
//

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicLong;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;

public class CentralACKManager {
    private static final CentralACKManager instance = new CentralACKManager();
    private final File ackStateFile;
    private final ConcurrentHashMap<String, String> citizenACKs = new ConcurrentHashMap<>(50000);
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final AtomicLong ackCounter = new AtomicLong(System.currentTimeMillis());

    // OPTIMIZACIÓN: Buffering de escrituras
    private final List<String> writeBuffer = Collections.synchronizedList(new ArrayList<>());
    private final int BUFFER_SIZE = 100;
    private volatile long lastFlush = System.currentTimeMillis();
    private final long FLUSH_INTERVAL = 2000; // 2 segundos

    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private CentralACKManager() {
        this.ackStateFile = new File("config/db/central-citizen-acks.csv");
        File parentDir = ackStateFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // NUEVA: Cargar ACKs usando lectura optimizada
        loadACKsOptimized();

        // NUEVA: Iniciar background thread para flush periódico
        startBackgroundFlusher();

        System.out.println("[CentralACKManager] Inicializado con archivo: " + ackStateFile.getPath());
    }

    public static CentralACKManager getInstance() {
        return instance;
    }

    /**
     * Versión optimizada para alta concurrencia centralizada
     * Usa read locks para verificación y write locks solo cuando necesario
     */
    public String getOrCreateACK(String citizenId, String serverInfo) {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        // PASO 1: Verificación rápida con read lock
        rwLock.readLock().lock();
        try {
            String existingACK = citizenACKs.get(citizenId);
            if (existingACK != null) {
                System.out.println("[" + timestamp + "] [CentralACKManager-FAST] [" + serverInfo + "] ACK cache hit: " + existingACK);
                return existingACK;
            }
        } finally {
            rwLock.readLock().unlock();
        }

        // PASO 2: Doble verificación con write lock
        rwLock.writeLock().lock();
        try {
            // Verificar nuevamente por si otro thread lo creó
            String existingACK = citizenACKs.get(citizenId);
            if (existingACK != null) {
                System.out.println("[" + timestamp + "] [CentralACKManager-RACE] [" + serverInfo + "] ACK encontrado: " + existingACK);
                return existingACK;
            }

            // PASO 3: Crear nuevo ACK con contador optimizado
            String newACK = generateOptimizedACK(serverInfo);
            citizenACKs.put(citizenId, newACK);

            // OPTIMIZACIÓN: Escritura buffered en lugar de inmediata
            addToWriteBuffer(citizenId, newACK);

            System.out.println("[" + timestamp + "] [CentralACKManager-NEW] [" + serverInfo + "] ACK creado: " + newACK);
            return newACK;

        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Generación optimizada de ACK único centralizado
     */
    private String generateOptimizedACK(String serverInfo) {
        long uniqueId = ackCounter.incrementAndGet();
        String serverSuffix = extractServerSuffix(serverInfo);
        return "CENTRAL-ACK-" + serverSuffix + "-" + Long.toHexString(uniqueId).toUpperCase();
    }

    /**
     * Extraer sufijo del servidor para ACK único
     */
    private String extractServerSuffix(String serverInfo) {
        if (serverInfo == null || serverInfo.isEmpty()) {
            return "XX";
        }

        // Extraer últimos 2 caracteres o usar hash
        if (serverInfo.length() >= 2) {
            return serverInfo.substring(serverInfo.length() - 2).toUpperCase();
        } else {
            return String.format("%02X", Math.abs(serverInfo.hashCode()) % 256);
        }
    }

    /**
     * Buffer de escritura para mejor performance I/O
     */
    private void addToWriteBuffer(String citizenId, String ackId) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String entry = timestamp + "," + citizenId + "," + ackId;

        writeBuffer.add(entry);

        // Flush si buffer está lleno o ha pasado tiempo suficiente
        if (writeBuffer.size() >= BUFFER_SIZE ||
                (System.currentTimeMillis() - lastFlush) > FLUSH_INTERVAL) {
            flushWriteBuffer();
        }
    }

    /**
     * Flush optimizado del buffer de escritura
     */
    private void flushWriteBuffer() {
        if (writeBuffer.isEmpty()) return;

        try {
            List<String> toWrite;
            synchronized (writeBuffer) {
                toWrite = new ArrayList<>(writeBuffer);
                writeBuffer.clear();
            }

            // OPTIMIZACIÓN: Escritura batch usando NIO
            try (FileChannel channel = FileChannel.open(ackStateFile.toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                StringBuilder batch = new StringBuilder();
                for (String entry : toWrite) {
                    batch.append(entry).append("\n");
                }

                channel.write(java.nio.ByteBuffer.wrap(batch.toString().getBytes()));
                channel.force(false); // Sync metadata también si es crítico
            }

            lastFlush = System.currentTimeMillis();

        } catch (IOException e) {
            System.err.println("[CentralACKManager] Error en flush batch: " + e.getMessage());
            // Reintentar individual en caso de error
            retryIndividualWrites();
        }
    }

    /**
     * Fallback para escrituras individuales en caso de error batch
     */
    private void retryIndividualWrites() {
        try (FileWriter fw = new FileWriter(ackStateFile, true)) {
            synchronized (writeBuffer) {
                for (String entry : writeBuffer) {
                    fw.write(entry + "\n");
                }
                writeBuffer.clear();
            }
            fw.flush();
        } catch (IOException e) {
            System.err.println("[CentralACKManager] Error crítico en retry writes: " + e.getMessage());
        }
    }

    /**
     * Background thread para flush periódico
     */
    private void startBackgroundFlusher() {
        Thread flusher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(FLUSH_INTERVAL);
                    if (!writeBuffer.isEmpty()) {
                        flushWriteBuffer();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        flusher.setDaemon(true);
        flusher.setName("CentralACKManager-Flusher");
        flusher.start();
    }

    /**
     * Carga optimizada de ACKs usando NIO y parsing eficiente
     */
    private void loadACKsOptimized() {
        if (!ackStateFile.exists()) {
            System.out.println("[CentralACKManager] No hay archivo previo - iniciando limpio");
            return;
        }

        try {
            // OPTIMIZACIÓN: Lectura usando NIO para mejor performance
            List<String> lines = Files.readAllLines(ackStateFile.toPath());

            int loadedCount = 0;
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;

                // OPTIMIZACIÓN: Parsing rápido sin split completo
                int firstComma = line.indexOf(',');
                int secondComma = line.indexOf(',', firstComma + 1);

                if (firstComma > 0 && secondComma > firstComma) {
                    String citizenId = line.substring(firstComma + 1, secondComma);
                    String ackId = line.substring(secondComma + 1);

                    // Solo mantener el ACK más reciente (último en archivo)
                    citizenACKs.put(citizenId, ackId);
                    loadedCount++;
                }
            }

            System.out.println("[CentralACKManager] Carga optimizada: " + loadedCount + " ACKs leídos");
            System.out.println("[CentralACKManager] ACKs únicos activos: " + citizenACKs.size());

            // OPTIMIZACIÓN: Inicializar contador basado en ACKs existentes
            initializeCounterFromExisting();

        } catch (IOException e) {
            System.err.println("[CentralACKManager] Error en carga optimizada: " + e.getMessage());
            fallbackToNormalLoad();
        }
    }

    /**
     * Inicializar contador para evitar colisiones de ACK
     */
    private void initializeCounterFromExisting() {
        long maxCounter = System.currentTimeMillis();
        for (String ack : citizenACKs.values()) {
            try {
                // Extraer el número del ACK para establecer contador
                String[] parts = ack.split("-");
                if (parts.length >= 4) { // CENTRAL-ACK-XX-HEXNUMBER
                    long ackNum = Long.parseLong(parts[3], 16);
                    maxCounter = Math.max(maxCounter, ackNum);
                }
            } catch (NumberFormatException e) {
                // Ignorar ACKs con formato diferente
            }
        }
        ackCounter.set(maxCounter + 1);
    }

    /**
     * Fallback a carga normal en caso de error en optimizada
     */
    private void fallbackToNormalLoad() {
        try (BufferedReader br = new BufferedReader(new FileReader(ackStateFile))) {
            String line;
            int loadedCount = 0;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    String citizenId = parts[1];
                    String ackId = parts[2];
                    citizenACKs.put(citizenId, ackId);
                    loadedCount++;
                }
            }
            System.out.println("[CentralACKManager] Fallback load: " + loadedCount + " ACKs");
        } catch (IOException e) {
            System.err.println("[CentralACKManager] Error crítico en fallback: " + e.getMessage());
        }
    }

    /**
     * Verificación rápida con read lock
     */
    public boolean hasACK(String citizenId) {
        rwLock.readLock().lock();
        try {
            return citizenACKs.containsKey(citizenId);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Obtención rápida con read lock
     */
    public String getACK(String citizenId) {
        rwLock.readLock().lock();
        try {
            return citizenACKs.get(citizenId);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Estadísticas optimizadas
     */
    public ACKStats getStats() {
        rwLock.readLock().lock();
        try {
            return new ACKStats(citizenACKs.size(), writeBuffer.size(),
                    System.currentTimeMillis() - lastFlush);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Debug info con métricas de performance
     */
    public void printDebugInfo() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        rwLock.readLock().lock();
        try {
            System.out.println("[" + timestamp + "] [CentralACKManager] === DEBUG INFO ===");
            System.out.println("Total ciudadanos con ACK: " + citizenACKs.size());
            System.out.println("Buffer pendiente: " + writeBuffer.size());
            System.out.println("Último flush: " + (System.currentTimeMillis() - lastFlush) + "ms atrás");
            System.out.println("Contador actual: " + ackCounter.get());
            System.out.println("Archivo: " + ackStateFile.getAbsolutePath());

            if (citizenACKs.size() <= 20) {
                System.out.println("Muestra de ACKs:");
                citizenACKs.entrySet().stream().limit(20).forEach(entry ->
                        System.out.println("  " + entry.getKey() + " -> " + entry.getValue()));
            }
            System.out.println("===============================");
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Limpieza para testing con flush forzado
     */
    public synchronized void clearForTesting() {
        rwLock.writeLock().lock();
        try {
            // Flush buffer antes de limpiar
            flushWriteBuffer();

            citizenACKs.clear();
            if (ackStateFile.exists()) {
                ackStateFile.delete();
            }
            ackCounter.set(System.currentTimeMillis());

            System.out.println("[CentralACKManager] Estado limpiado para testing");
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Shutdown graceful con flush final
     */
    public void shutdown() {
        rwLock.writeLock().lock();
        try {
            flushWriteBuffer();
            System.out.println("[CentralACKManager] Shutdown completo con flush final");
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Clase de estadísticas mejorada
     */
    public static class ACKStats {
        public final int totalACKs;
        public final int pendingWrites;
        public final long lastFlushAgo;

        public ACKStats(int totalACKs, int pendingWrites, long lastFlushAgo) {
            this.totalACKs = totalACKs;
            this.pendingWrites = pendingWrites;
            this.lastFlushAgo = lastFlushAgo;
        }

        @Override
        public String toString() {
            return String.format("ACKStats{totalACKs=%d, pendingWrites=%d, lastFlushAgo=%dms}",
                    totalACKs, pendingWrites, lastFlushAgo);
        }
    }
}
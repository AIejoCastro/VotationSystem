import java.io.*;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.concurrent.locks.ReentrantLock;

/**
 * VoteDAO optimizado para alta carga con batch processing
 * Usa NIO y buffering para mejor performance I/O
 */
public class VoteDAO {
    private final File file = new File("config/db/votes.csv");
    private final ReentrantLock fileLock = new ReentrantLock();

    public VoteDAO() {
        // Asegurar que el directorio existe
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
    }

    /**
     * Guardado individual sincronizado (método original)
     */
    public synchronized void save(String citizenId, String candidateId) {
        fileLock.lock();
        try (FileWriter fw = new FileWriter(file, true)) {
            fw.write(citizenId + "," + candidateId + "\n");
            fw.flush();
        } catch (IOException e) {
            System.err.println("[VoteDAO] Error guardando voto individual: " + e.getMessage());
            e.printStackTrace();
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * NUEVO: Guardado optimizado en batch usando NIO
     */
    public void saveBatch(List<VoteCommand> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        fileLock.lock();
        try {
            // OPTIMIZACIÓN: Construir todo el contenido en memoria primero
            StringBuilder batchContent = new StringBuilder(batch.size() * 50);
            for (VoteCommand vote : batch) {
                batchContent.append(vote.getCitizenId())
                        .append(",")
                        .append(vote.getCandidateId())
                        .append("\n");
            }

            // OPTIMIZACIÓN: Escribir todo de una vez usando NIO
            try (FileChannel channel = FileChannel.open(file.toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                byte[] data = batchContent.toString().getBytes("UTF-8");
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);

                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }

                // Force sync para asegurar persistencia crítica
                channel.force(false);
            }

        } catch (IOException e) {
            System.err.println("[VoteDAO] Error en batch write, fallback a individual: " + e.getMessage());

            // Fallback: guardar votos individualmente
            for (VoteCommand vote : batch) {
                try {
                    saveIndividualFallback(vote.getCitizenId(), vote.getCandidateId());
                } catch (Exception fallbackError) {
                    System.err.println("[VoteDAO] Error crítico en fallback para voto: " +
                            vote.getCitizenId() + " -> " + vote.getCandidateId());
                }
            }
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Fallback para guardado individual sin sincronización externa
     */
    private void saveIndividualFallback(String citizenId, String candidateId) throws IOException {
        try (FileWriter fw = new FileWriter(file, true)) {
            fw.write(citizenId + "," + candidateId + "\n");
            fw.flush();
        }
    }

    /**
     * NUEVO: Método para obtener estadísticas del archivo
     */
    public VoteFileStats getFileStats() {
        fileLock.lock();
        try {
            if (!file.exists()) {
                return new VoteFileStats(0, 0);
            }

            long fileSize = file.length();
            int lineCount = 0;

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                while (reader.readLine() != null) {
                    lineCount++;
                }
            } catch (IOException e) {
                System.err.println("[VoteDAO] Error contando líneas: " + e.getMessage());
            }

            return new VoteFileStats(lineCount, fileSize);

        } finally {
            fileLock.unlock();
        }
    }

    /**
     * NUEVO: Método para verificar integridad del archivo
     */
    public boolean verifyFileIntegrity() {
        fileLock.lock();
        try {
            if (!file.exists()) {
                return true; // Archivo no existe, técnicamente íntegro
            }

            int validLines = 0;
            int invalidLines = 0;

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue; // Ignorar líneas vacías
                    }

                    String[] parts = line.split(",");
                    if (parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) {
                        validLines++;
                    } else {
                        invalidLines++;
                        System.out.println("[VoteDAO] Línea inválida detectada: " + line);
                    }
                }
            } catch (IOException e) {
                System.err.println("[VoteDAO] Error verificando integridad: " + e.getMessage());
                return false;
            }

            System.out.println("[VoteDAO] Verificación completada: " + validLines +
                    " válidas, " + invalidLines + " inválidas");

            return invalidLines == 0;

        } finally {
            fileLock.unlock();
        }
    }

    /**
     * NUEVO: Compactar archivo removiendo líneas duplicadas
     */
    public int compactFile() {
        fileLock.lock();
        try {
            if (!file.exists()) {
                return 0;
            }

            // Leer todos los votos únicos (último voto por ciudadano)
            java.util.Map<String, String> uniqueVotes = new java.util.LinkedHashMap<>();

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;

                    String[] parts = line.split(",");
                    if (parts.length == 2) {
                        uniqueVotes.put(parts[0].trim(), parts[1].trim());
                    }
                }
            }

            // Crear archivo temporal con votos únicos
            File tempFile = new File(file.getAbsolutePath() + ".compact");
            try (FileWriter writer = new FileWriter(tempFile)) {
                for (java.util.Map.Entry<String, String> entry : uniqueVotes.entrySet()) {
                    writer.write(entry.getKey() + "," + entry.getValue() + "\n");
                }
                writer.flush();
            }

            // Reemplazar archivo original
            long originalSize = file.length();
            if (file.delete() && tempFile.renameTo(file)) {
                long newSize = file.length();
                int savedBytes = (int)(originalSize - newSize);

                System.out.println("[VoteDAO] Compactación exitosa: " + uniqueVotes.size() +
                        " votos únicos, " + savedBytes + " bytes ahorrados");
                return savedBytes;
            } else {
                tempFile.delete();
                throw new IOException("No se pudo reemplazar archivo original");
            }

        } catch (IOException e) {
            System.err.println("[VoteDAO] Error en compactación: " + e.getMessage());
            return -1;
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Clase para estadísticas del archivo
     */
    public static class VoteFileStats {
        public final int totalVotes;
        public final long fileSizeBytes;

        public VoteFileStats(int totalVotes, long fileSizeBytes) {
            this.totalVotes = totalVotes;
            this.fileSizeBytes = fileSizeBytes;
        }

        @Override
        public String toString() {
            return String.format("VoteFileStats{votes=%d, size=%d bytes (%.2f KB)}",
                    totalVotes, fileSizeBytes, fileSizeBytes / 1024.0);
        }
    }
}
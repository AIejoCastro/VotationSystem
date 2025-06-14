import java.io.*;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CentralVoteDAO - DAO centralizado con acceso exclusivo a la base de datos
 * Version centralizada del VoteDAO original con optimizaciones
 */
public class CentralVoteDAO {
    private final File file = new File("config/db/central-votes.csv");
    private final ReentrantLock fileLock = new ReentrantLock();

    public CentralVoteDAO() {
        // Asegurar que el directorio existe
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        System.out.println("[CentralVoteDAO] Inicializado con archivo: " + file.getAbsolutePath());
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
            System.err.println("[CentralVoteDAO] Error guardando voto individual: " + e.getMessage());
            e.printStackTrace();
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Guardado optimizado en batch usando NIO
     */
    public void saveBatch(List<CentralVoteCommand> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        fileLock.lock();
        try {
            // OPTIMIZACIÓN: Construir todo el contenido en memoria primero
            StringBuilder batchContent = new StringBuilder(batch.size() * 50);
            for (CentralVoteCommand vote : batch) {
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
            System.err.println("[CentralVoteDAO] Error en batch write, fallback a individual: " + e.getMessage());

            // Fallback: guardar votos individualmente
            for (CentralVoteCommand vote : batch) {
                try {
                    saveIndividualFallback(vote.getCitizenId(), vote.getCandidateId());
                } catch (Exception fallbackError) {
                    System.err.println("[CentralVoteDAO] Error crítico en fallback para voto: " +
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
     * Método para obtener estadísticas del archivo centralizado
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
                System.err.println("[CentralVoteDAO] Error contando líneas: " + e.getMessage());
            }

            return new VoteFileStats(lineCount, fileSize);

        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Método para verificar integridad del archivo centralizado
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
                        System.out.println("[CentralVoteDAO] Línea inválida detectada: " + line);
                    }
                }
            } catch (IOException e) {
                System.err.println("[CentralVoteDAO] Error verificando integridad: " + e.getMessage());
                return false;
            }

            System.out.println("[CentralVoteDAO] Verificación completada: " + validLines +
                    " válidas, " + invalidLines + " inválidas");

            return invalidLines == 0;

        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Compactar archivo removiendo líneas duplicadas
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

                System.out.println("[CentralVoteDAO] Compactación exitosa: " + uniqueVotes.size() +
                        " votos únicos, " + savedBytes + " bytes ahorrados");
                return savedBytes;
            } else {
                tempFile.delete();
                throw new IOException("No se pudo reemplazar archivo original");
            }

        } catch (IOException e) {
            System.err.println("[CentralVoteDAO] Error en compactación: " + e.getMessage());
            return -1;
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Limpiar archivo para testing
     */
    public synchronized void clearForTesting() {
        fileLock.lock();
        try {
            if (file.exists()) {
                file.delete();
            }
            System.out.println("[CentralVoteDAO] Archivo de votos limpiado para testing");
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Obtener ruta del archivo para debugging
     */
    public String getFilePath() {
        return file.getAbsolutePath();
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
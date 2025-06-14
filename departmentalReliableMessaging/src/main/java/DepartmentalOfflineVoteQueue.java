import java.io.*;
import java.util.*;

/**
 * DepartmentalOfflineVoteQueue - Cola offline para comunicación Departamental → Central
 * Maneja votos que no pudieron ser enviados al CentralServer
 */
public class DepartmentalOfflineVoteQueue {
    private final File file;

    public DepartmentalOfflineVoteQueue(String filePath) {
        this.file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
    }

    public synchronized void enqueue(String citizenId, String candidateId, String departmentalServerId) {
        try (FileWriter fw = new FileWriter(file, true)) {
            String timestamp = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
            fw.write(timestamp + "," + citizenId + "," + candidateId + "," + departmentalServerId + "\n");
            System.out.println("[DepartmentalReliableMessaging] Voto guardado offline: " +
                    citizenId + " -> " + candidateId + " (desde " + departmentalServerId + ")");
        } catch (IOException e) {
            System.err.println("[DepartmentalReliableMessaging] Error guardando voto offline: " + e.getMessage());
        }
    }

    public synchronized List<String[]> getAll() {
        List<String[]> votes = new ArrayList<>();
        if (!file.exists()) {
            return votes;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 4) {
                    // timestamp, citizenId, candidateId, departmentalServerId
                    votes.add(new String[]{parts[1].trim(), parts[2].trim(), parts[3].trim()});
                }
            }
        } catch (IOException e) {
            System.err.println("[DepartmentalReliableMessaging] Error leyendo votos offline: " + e.getMessage());
        }
        return votes;
    }

    public synchronized void clear() {
        if (file.exists()) {
            boolean deleted = file.delete();
            if (deleted) {
                System.out.println("[DepartmentalReliableMessaging] Cola de votos offline limpiada");
            }
        }
    }

    public synchronized int size() {
        return getAll().size();
    }

    public String getFilePath() {
        return file.getAbsolutePath();
    }
}
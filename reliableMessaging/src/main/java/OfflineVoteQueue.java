import java.io.*;
import java.util.*;

public class OfflineVoteQueue {
    private final File file;

    public OfflineVoteQueue(String filePath) {
        this.file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
    }

    public synchronized void enqueue(String citizenId, String candidateId) {
        try (FileWriter fw = new FileWriter(file, true)) {
            fw.write(citizenId + "," + candidateId + "\n");
            System.out.println("[ReliableMessaging] Voto guardado offline: " + citizenId + " -> " + candidateId);
        } catch (IOException e) {
            System.err.println("[ReliableMessaging] Error guardando voto offline: " + e.getMessage());
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
                if (parts.length == 2) {
                    votes.add(new String[]{parts[0].trim(), parts[1].trim()});
                }
            }
        } catch (IOException e) {
            System.err.println("[ReliableMessaging] Error leyendo votos offline: " + e.getMessage());
        }
        return votes;
    }

    public synchronized void clear() {
        if (file.exists()) {
            boolean deleted = file.delete();
            if (deleted) {
                System.out.println("[ReliableMessaging] Cola de votos offline limpiada");
            }
        }
    }

    public synchronized int size() {
        return getAll().size();
    }
}
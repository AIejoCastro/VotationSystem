import java.io.*;
import java.util.*;

public class OfflineVoteQueue {
    private final File file = new File("config/db/pending-votes.csv");

    public synchronized void enqueue(String citizenId, String candidateId) {
        try (FileWriter fw = new FileWriter(file, true)) {
            fw.write(citizenId + "," + candidateId + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized List<String[]> getAll() {
        List<String[]> votes = new ArrayList<>();
        if (!file.exists()) return votes;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                votes.add(line.split(","));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return votes;
    }

    public synchronized void clear() {
        file.delete();
    }
}

import java.io.*;

public class VoteDAO {
    private final File file = new File("config/db/votes.csv");

    public synchronized void save(String citizenId, String candidateId) {
        try (FileWriter fw = new FileWriter(file, true)) {
            fw.write(citizenId + "," + candidateId + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

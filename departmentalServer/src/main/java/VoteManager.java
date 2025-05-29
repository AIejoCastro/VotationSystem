import java.util.Set;
import java.util.concurrent.*;

public class VoteManager {
    private static final VoteManager instance = new VoteManager();
    private final Set<String> votedCitizens = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<VoteCommand> queue = new LinkedBlockingQueue<>();

    private VoteManager() {
        for (int i = 0; i < 4; i++) { // 4 hilos para consumir
            new Thread(new VoteWriter(queue)).start();
        }
    }

    public static VoteManager getInstance() {
        return instance;
    }

    public boolean receiveVote(String citizenId, String candidateId) {
        if (!votedCitizens.add(citizenId)) return false; // ya votó
        queue.add(new VoteCommand(citizenId, candidateId));
        return true;
    }
}

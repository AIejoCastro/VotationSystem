import java.util.concurrent.BlockingQueue;

public class VoteWriter implements Runnable {
    private final BlockingQueue<VoteCommand> queue;
    private final VoteDAO dao = new VoteDAO();

    public VoteWriter(BlockingQueue<VoteCommand> queue) {
        this.queue = queue;
    }

    @Override
    public void run() {
        while (true) {
            try {
                VoteCommand vote = queue.take(); // bloquea hasta que haya uno
                vote.persist(dao);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

import Demo.*;
import com.zeroc.Ice.LocalException;
import com.zeroc.IceGrid.QueryPrx;
import com.zeroc.Ice.Communicator;

import java.util.List;

public class VoteRetryWorker extends Thread {
    private final Communicator communicator;
    private final OfflineVoteQueue queue = new OfflineVoteQueue();

    public VoteRetryWorker(Communicator communicator) {
        this.communicator = communicator;
    }

    @Override
    public void run() {
        while (true) {
            try {
                List<String[]> votes = queue.getAll();
                if (!votes.isEmpty()) {
                    VotationPrx hello = getActiveProxy();
                    if (hello == null) {
                    } else {
                        boolean allSuccess = true;
                        for (String[] vote : votes) {
                            try {
                                hello.sendVote(vote[0], vote[1]);
                            } catch (LocalException | AlreadyVotedException e) {
                                allSuccess = false;
                            }
                        }

                        if (allSuccess) {
                            queue.clear();
                            System.out.println("Todos los votos pendientes fueron reenviados correctamente.");
                        }
                    }
                }

                Thread.sleep(10000); // 10 segundos entre reintentos
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private VotationPrx getActiveProxy() {
        try {
            QueryPrx query = QueryPrx.checkedCast(communicator.stringToProxy("DemoIceGrid/Query"));
            return VotationPrx.checkedCast(query.findObjectByType("::Demo::Votation"));
        } catch (Exception e) {
            return null; // Servidor aún no disponible
        }
    }
}

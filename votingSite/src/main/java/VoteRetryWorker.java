import Demo.*;
import com.zeroc.Ice.LocalException;
import com.zeroc.IceGrid.QueryPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Properties;

import java.util.List;

public class VoteRetryWorker extends Thread {
    private final Communicator communicator;
    private final OfflineVoteQueue queue = new OfflineVoteQueue();
    private final long retryInterval;

    public VoteRetryWorker(Communicator communicator) {
        this.communicator = communicator;
        Properties props = communicator.getProperties();
        this.retryInterval = props.getPropertyAsIntWithDefault("VoteRetry.Interval", 10000); // en milisegundos
    }

    @Override
    public void run() {
        while (true) {
            try {
                List<String[]> votes = queue.getAll();
                if (!votes.isEmpty()) {
                    VotationPrx hello = getActiveProxy();
                    if (hello == null) {
                        System.out.println("No hay servidor disponible para reintentar los votos pendientes.");
                    } else {
                        boolean allSuccess = true;
                        for (String[] vote : votes) {
                            try {
                                hello.sendVote(vote[0], vote[1]);
                            } catch (LocalException | AlreadyVotedException e) {
                                System.out.println("Error reintentando voto: " + e.getMessage());
                                allSuccess = false;
                            }
                        }

                        if (allSuccess) {
                            queue.clear();
                            System.out.println("Todos los votos pendientes fueron reenviados correctamente.");
                        }
                    }
                }

                Thread.sleep(retryInterval);
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

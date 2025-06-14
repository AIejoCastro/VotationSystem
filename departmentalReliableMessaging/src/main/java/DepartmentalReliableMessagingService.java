import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Properties;

/**
 * DepartmentalReliableMessagingService - Servicio de mensajería confiable Departamental → Central
 * Garantiza entrega de votos al CentralServer incluso durante indisponibilidad
 */
public class DepartmentalReliableMessagingService {
    private static DepartmentalReliableMessagingService instance;
    private DepartmentalOfflineVoteQueue offlineQueue;
    private DepartmentalVoteRetryWorker retryWorker;
    private DepartmentalVoteACKManager ackManager;
    private boolean initialized = false;

    private DepartmentalReliableMessagingService() {
    }

    public static synchronized DepartmentalReliableMessagingService getInstance() {
        if (instance == null) {
            instance = new DepartmentalReliableMessagingService();
        }
        return instance;
    }

    public void initialize(Communicator communicator) {
        if (initialized) {
            System.out.println("[DepartmentalReliableMessaging] Ya inicializado");
            return;
        }

        Properties props = communicator.getProperties();
        String offlineQueuePath = props.getPropertyWithDefault(
                "DepartmentalReliableMessaging.OfflineQueuePath",
                "config/db/departmental-pending-votes.csv"
        );

        String ackFilePath = props.getPropertyWithDefault(
                "DepartmentalReliableMessaging.ACKFilePath",
                "config/db/departmental-vote-acks.log"
        );

        this.offlineQueue = new DepartmentalOfflineVoteQueue(offlineQueuePath);
        this.ackManager = new DepartmentalVoteACKManager(ackFilePath);
        this.retryWorker = new DepartmentalVoteRetryWorker(communicator, offlineQueue, ackManager);
        this.retryWorker.start();

        this.initialized = true;
        System.out.println("[DepartmentalReliableMessaging] Servicio inicializado con entrega garantizada hacia CentralServer");

        int pendingVotes = offlineQueue.size();
        if (pendingVotes > 0) {
            System.out.println("[DepartmentalReliableMessaging] " + pendingVotes +
                    " votos pendientes encontrados - Activando entrega automática hacia CentralServer");
            retryWorker.notifyNewVoteAdded(); // Activar worker inmediatamente
        } else {
            System.out.println("[DepartmentalReliableMessaging] No hay votos pendientes hacia CentralServer - Worker en standby");
        }
    }

    public String storeOfflineVoteWithACK(String citizenId, String candidateId, String departmentalServerId) {
        if (!initialized) {
            throw new IllegalStateException("[DepartmentalReliableMessaging] No inicializado");
        }

        if (citizenId == null || citizenId.trim().isEmpty()) {
            throw new IllegalArgumentException("[DepartmentalReliableMessaging] citizenId vacío");
        }

        if (candidateId == null || candidateId.trim().isEmpty()) {
            throw new IllegalArgumentException("[DepartmentalReliableMessaging] candidateId vacío");
        }

        if (departmentalServerId == null || departmentalServerId.trim().isEmpty()) {
            throw new IllegalArgumentException("[DepartmentalReliableMessaging] departmentalServerId vacío");
        }

        String voteKey = ackManager.addPendingVote(citizenId.trim(), candidateId.trim(), departmentalServerId.trim());
        offlineQueue.enqueue(citizenId.trim(), candidateId.trim(), departmentalServerId.trim());

        // IMPORTANTE: Activar worker cuando agregamos voto offline
        System.out.println("[DepartmentalReliableMessaging] Voto offline agregado hacia CentralServer - Activando entrega garantizada");
        retryWorker.notifyNewVoteAdded();

        return voteKey;
    }

    public void confirmVoteACK(String voteKey, String ackId, long latencyMs) {
        if (ackManager != null) {
            ackManager.confirmACK(voteKey, ackId, latencyMs);
        }
    }

    public void timeoutVote(String voteKey) {
        if (ackManager != null) {
            ackManager.timeoutVote(voteKey);
        }
    }

    public void failVote(String voteKey, String reason) {
        if (ackManager != null) {
            ackManager.failVote(voteKey, reason);
        }
    }

    public int getPendingVotesCount() {
        return initialized ? offlineQueue.size() : 0;
    }
}
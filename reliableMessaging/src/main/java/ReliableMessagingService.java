import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Properties;

public class ReliableMessagingService {
    private static ReliableMessagingService instance;
    private OfflineVoteQueue offlineQueue;
    private VoteRetryWorker retryWorker;
    private VoteACKManager ackManager;
    private boolean initialized = false;

    private ReliableMessagingService() {}

    public static synchronized ReliableMessagingService getInstance() {
        if (instance == null) {
            instance = new ReliableMessagingService();
        }
        return instance;
    }

    public void initialize(Communicator communicator) {
        if (initialized) {
            System.out.println("[ReliableMessaging] Ya inicializado");
            return;
        }

        Properties props = communicator.getProperties();
        String offlineQueuePath = props.getPropertyWithDefault(
                "ReliableMessaging.OfflineQueuePath",
                "config/db/pending-votes.csv"
        );

        String ackFilePath = props.getPropertyWithDefault(
                "ReliableMessaging.ACKFilePath",
                "config/db/vote-acks.log"
        );

        this.offlineQueue = new OfflineVoteQueue(offlineQueuePath);
        this.ackManager = new VoteACKManager(ackFilePath);
        this.retryWorker = new VoteRetryWorker(communicator, offlineQueue, ackManager);
        this.retryWorker.start();

        this.initialized = true;
        System.out.println("[ReliableMessaging] Servicio inicializado con entrega garantizada");

        int pendingVotes = offlineQueue.size();
        if (pendingVotes > 0) {
            System.out.println("[ReliableMessaging]" + pendingVotes + " votos pendientes encontrados - Activando entrega automática");
            retryWorker.notifyNewVoteAdded(); // Activar worker inmediatamente
        } else {
            System.out.println("[ReliableMessaging] No hay votos pendientes - Worker en standby");
        }
    }

    public String storeOfflineVoteWithACK(String citizenId, String candidateId) {
        if (!initialized) {
            throw new IllegalStateException("[ReliableMessaging] No inicializado");
        }

        if (citizenId == null || citizenId.trim().isEmpty()) {
            throw new IllegalArgumentException("[ReliableMessaging] citizenId vacío");
        }

        if (candidateId == null || candidateId.trim().isEmpty()) {
            throw new IllegalArgumentException("[ReliableMessaging] candidateId vacío");
        }

        String voteKey = ackManager.addPendingVote(citizenId.trim(), candidateId.trim());
        offlineQueue.enqueue(citizenId.trim(), candidateId.trim());

        // IMPORTANTE: Activar worker cuando agregamos voto offline
        System.out.println("[ReliableMessaging] Voto offline agregado - Activando entrega garantizada");
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

    public int getPendingVotesCount() {
        return initialized ? offlineQueue.size() : 0;
    }

    public boolean isWorkerRunning() {
        return retryWorker != null && retryWorker.isRunning();
    }

    public int getRetryCount() {
        return retryWorker != null ? retryWorker.getRetryCount() : 0;
    }

    public boolean hasVotesPending() {
        return retryWorker != null && retryWorker.hasVotesPending();
    }

    public void printStatus() {
        if (!initialized) {
            System.out.println("[ReliableMessaging] No inicializado");
            return;
        }

        System.out.println("\n=== Estado del Reliable Messaging ===");
        System.out.println("Worker activo: " + isWorkerRunning());
        System.out.println("Votos pendientes: " + getPendingVotesCount());
        System.out.println("Modo actual: " + (hasVotesPending() ? "ENTREGA ACTIVA" : "STANDBY"));

        if (hasVotesPending()) {
            System.out.println("Intentos consecutivos: " + getRetryCount());
            System.out.println("Estrategia: INTENTOS ILIMITADOS hasta completar todos los votos");
        }

        if (ackManager != null) {
            ackManager.printACKStatus();
        }
        System.out.println("====================================\n");
    }

    public void printACKHistory() {
        if (ackManager != null) {
            ackManager.printVoteHistory();
        }
    }

    public void shutdown() {
        if (retryWorker != null) {
            retryWorker.shutdown();
            try {
                retryWorker.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        initialized = false;
        System.out.println("[ReliableMessaging] Servicio detenido");
    }

    public static void main(String[] args) {
        System.out.println("[ReliableMessaging] Ejecutando como servicio independiente");
        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize(args)) {
            communicator.getProperties().setProperty("Ice.Default.Locator", "DemoIceGrid-grpmcc/Locator:default -h 10.147.17.101 -p 4071");

            try {
                communicator.getProperties().load("reliableMessaging/src/main/resources/config.reliableMessaging");
            } catch (Exception e) {
                System.out.println("Usando configuración por defecto");
            }

            ReliableMessagingService service = getInstance();
            service.initialize(communicator);

            System.out.println("Servicio iniciado. Presiona Enter para detener...");
            System.in.read();

            service.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
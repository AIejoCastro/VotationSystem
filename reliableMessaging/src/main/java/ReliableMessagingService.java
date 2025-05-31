import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Properties;

public class ReliableMessagingService {
    private static ReliableMessagingService instance;
    private OfflineVoteQueue offlineQueue;
    private VoteRetryWorker retryWorker;
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

        this.offlineQueue = new OfflineVoteQueue(offlineQueuePath);
        this.retryWorker = new VoteRetryWorker(communicator, offlineQueue);
        this.retryWorker.start();

        this.initialized = true;
        System.out.println("[ReliableMessaging] Servicio inicializado");

        int pendingVotes = offlineQueue.size();
        if (pendingVotes > 0) {
            System.out.println("[ReliableMessaging] " + pendingVotes + " votos pendientes encontrados");
        }
    }

    public void storeOfflineVote(String citizenId, String candidateId) {
        if (!initialized) {
            throw new IllegalStateException("[ReliableMessaging] No inicializado");
        }

        if (citizenId == null || citizenId.trim().isEmpty()) {
            throw new IllegalArgumentException("[ReliableMessaging] citizenId vacío");
        }

        if (candidateId == null || candidateId.trim().isEmpty()) {
            throw new IllegalArgumentException("[ReliableMessaging] candidateId vacío");
        }

        offlineQueue.enqueue(citizenId.trim(), candidateId.trim());
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

    public void printStatus() {
        if (!initialized) {
            System.out.println("[ReliableMessaging] No inicializado");
            return;
        }

        System.out.println("[ReliableMessaging] Estado:");
        System.out.println("  - Votos pendientes: " + getPendingVotesCount());
        System.out.println("  - Worker activo: " + isWorkerRunning());
        System.out.println("  - Reintentos: " + getRetryCount());
    }

    // Main para testing independiente
    public static void main(String[] args) {
        System.out.println("[ReliableMessaging] Ejecutando como servicio independiente");
        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize(args)) {
            // Cargar configuración
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
import Demo.*;
import com.zeroc.Ice.LocalException;
import com.zeroc.IceGrid.QueryPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Properties;
import java.util.List;

public class VoteRetryWorker extends Thread {
    private final Communicator communicator;
    private final OfflineVoteQueue queue;
    private final long retryInterval;
    private final int maxRetries;
    private final boolean verboseLogging;
    private volatile boolean running = true;
    private int retryCount = 0;

    public VoteRetryWorker(Communicator communicator, OfflineVoteQueue queue) {
        this.communicator = communicator;
        this.queue = queue;

        Properties props = communicator.getProperties();
        this.retryInterval = props.getPropertyAsIntWithDefault("ReliableMessaging.RetryInterval", 10000);
        this.maxRetries = props.getPropertyAsIntWithDefault("ReliableMessaging.MaxRetries", 5);
        this.verboseLogging = props.getPropertyAsIntWithDefault("ReliableMessaging.VerboseLogging", 1) == 1;

        this.setDaemon(true);
        this.setName("VoteRetryWorker");

        if (verboseLogging) {
            System.out.println("[ReliableMessaging] Worker configurado:");
            System.out.println("  - Intervalo: " + retryInterval + "ms");
            System.out.println("  - Max reintentos: " + maxRetries);
        }
    }

    @Override
    public void run() {
        System.out.println("[ReliableMessaging] VoteRetryWorker iniciado");

        while (running && retryCount < maxRetries) {
            try {
                processOfflineVotes();
                Thread.sleep(retryInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (retryCount >= maxRetries) {
            System.out.println("[ReliableMessaging] Máximo reintentos alcanzado (" + maxRetries + ")");
        }

        System.out.println("[ReliableMessaging] VoteRetryWorker detenido");
    }

    private void processOfflineVotes() {
        List<String[]> votes = queue.getAll();
        if (votes.isEmpty()) {
            if (verboseLogging) {
                System.out.println("[ReliableMessaging] No hay votos pendientes");
            }
            return;
        }

        System.out.println("[ReliableMessaging] Procesando " + votes.size() + " votos pendientes...");
        System.out.println("[ReliableMessaging] Buscando servidor disponible a través de IceGrid...");

        VotationPrx votationProxy = getActiveProxy();
        if (votationProxy == null) {
            retryCount++;
            System.out.println("[ReliableMessaging] No hay servidor disponible en IceGrid (" + retryCount + "/" + maxRetries + ")");
            return;
        }

        // Reset retry count si conseguimos conexión
        retryCount = 0;
        int successCount = 0;
        int errorCount = 0;

        for (String[] vote : votes) {
            try {
                // Intentar enviar el voto al servidor activo
                votationProxy.sendVote(vote[0], vote[1]);
                successCount++;
                if (verboseLogging) {
                    System.out.println("[ReliableMessaging] Voto procesado: " + vote[0] + " -> " + vote[1]);
                }
            } catch (AlreadyVotedException e) {
                System.out.println("[ReliableMessaging] Ciudadano " + vote[0] + " ya votó");
                successCount++; // Consideramos esto como éxito para eliminar de la cola
            } catch (LocalException e) {
                System.out.println("[ReliableMessaging] Error de conexión: " + e.getMessage());
                System.out.println("[ReliableMessaging] Posible failover de servidor, reintentando en próxima iteración");
                errorCount++;
                break; // Si hay error de conexión, parar e intentar de nuevo en la siguiente iteración
            }
        }

        if (errorCount == 0) {
            queue.clear();
            System.out.println("[ReliableMessaging] Todos los votos pendientes procesados exitosamente (" + successCount + ")");
        } else {
            System.out.println("[ReliableMessaging] Procesados: " + successCount + " votos, " + errorCount + " errores");
            System.out.println("[ReliableMessaging] IceGrid manejará el failover automáticamente");
        }
    }

    private VotationPrx getActiveProxy() {
        try {
            // Primero intentar obtener el proxy del Query de IceGrid
            QueryPrx query = QueryPrx.checkedCast(
                    communicator.stringToProxy("DemoIceGrid/Query")
            );
            if (query == null) {
                if (verboseLogging) {
                    System.out.println("[ReliableMessaging] No se pudo conectar al IceGrid Query");
                }
                return null;
            }

            // Buscar un servidor Votation disponible a través de IceGrid
            VotationPrx proxy = VotationPrx.checkedCast(
                    query.findObjectByType("::Demo::Votation")
            );

            if (proxy == null) {
                if (verboseLogging) {
                    System.out.println("[ReliableMessaging] No hay servidores Votation disponibles en IceGrid");
                }
                return null;
            }

            // Verificar que el proxy está realmente disponible
            try {
                proxy.ice_ping();
                if (verboseLogging) {
                    System.out.println("[ReliableMessaging] Conectado a servidor disponible");
                }
                return proxy;
            } catch (Exception pingEx) {
                if (verboseLogging) {
                    System.out.println("[ReliableMessaging] Servidor no responde al ping: " + pingEx.getMessage());
                }
                return null;
            }

        } catch (Exception e) {
            if (verboseLogging) {
                System.out.println("[ReliableMessaging] Error obteniendo proxy: " + e.getMessage());
            }
            return null;
        }
    }

    public void shutdown() {
        running = false;
        this.interrupt();
    }

    public boolean isRunning() {
        return running && isAlive();
    }

    public int getRetryCount() {
        return retryCount;
    }
}
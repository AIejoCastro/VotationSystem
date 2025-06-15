import Central.*;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Properties;
import java.util.List;

/**
 * DepartmentalVoteRetryWorker - Worker para reintentos de comunicación Departamental → Central
 * Procesa votos offline cuando el CentralServer no está disponible
 */
public class DepartmentalVoteRetryWorker extends Thread {
    private final Communicator communicator;
    private final DepartmentalOfflineVoteQueue queue;
    private final DepartmentalVoteACKManager ackManager;
    private final long retryInterval;
    private final boolean verboseLogging;
    private volatile boolean running = true;
    private int consecutiveFailures = 0;
    private boolean hasVotesPending = false;

    public DepartmentalVoteRetryWorker(Communicator communicator, DepartmentalOfflineVoteQueue queue,
                                       DepartmentalVoteACKManager ackManager) {
        this.communicator = communicator;
        this.queue = queue;
        this.ackManager = ackManager;

        Properties props = communicator.getProperties();
        this.retryInterval = props.getPropertyAsIntWithDefault("DepartmentalReliableMessaging.RetryInterval", 8000);
        this.verboseLogging = props.getPropertyAsIntWithDefault("DepartmentalReliableMessaging.VerboseLogging", 1) == 1;

        this.setDaemon(true);
        this.setName("DepartmentalVoteRetryWorker");

        if (verboseLogging) {
            System.out.println("[DepartmentalReliableMessaging] Worker configurado:");
            System.out.println("  - Intervalo cuando hay votos pendientes: " + retryInterval + "ms");
            System.out.println("  - Estrategia: INTENTOS ILIMITADOS hacia CentralServer");
            System.out.println("  - Solo activo cuando CentralServer no disponible");
        }
    }

    @Override
    public void run() {
        System.out.println("[DepartmentalReliableMessaging] Worker iniciado para comunicación con CentralServer");

        while (running) {
            try {
                // Solo procesar si hay votos pendientes
                if (queue.size() > 0) {
                    if (!hasVotesPending) {
                        hasVotesPending = true;
                        System.out.println("[DepartmentalReliableMessaging] ACTIVANDO modo de entrega garantizada hacia CentralServer");
                    }

                    processOfflineVotes();

                    // Solo esperar intervalo si AÚN hay votos pendientes después del procesamiento
                    if (running && queue.size() > 0) {
                        if (verboseLogging) {
                            System.out.println("[DepartmentalReliableMessaging] Esperando " + (retryInterval/1000) +
                                    " segundos antes del próximo intento hacia CentralServer...");
                        }
                        Thread.sleep(retryInterval);
                    } else if (hasVotesPending && queue.size() == 0) {
                        // Todos los votos fueron procesados exitosamente
                        hasVotesPending = false;
                        consecutiveFailures = 0;
                        System.out.println("[DepartmentalReliableMessaging] MISIÓN CUMPLIDA - Todos los votos entregados a CentralServer");
                    }
                } else {
                    // No hay votos pendientes - dormir indefinidamente hasta ser despertado
                    if (hasVotesPending) {
                        hasVotesPending = false;
                        consecutiveFailures = 0;
                        System.out.println("[DepartmentalReliableMessaging] Sin votos pendientes - Worker en modo eficiente (dormido)");
                    }

                    if (verboseLogging) {
                        System.out.println("[DepartmentalReliableMessaging] Worker durmiendo hasta que CentralServer no esté disponible...");
                    }

                    // Dormir indefinidamente hasta ser interrumpido
                    Thread.sleep(Long.MAX_VALUE);
                }

            } catch (InterruptedException e) {
                // Si nos interrumpen, significa que hay nuevos votos para procesar
                if (running) {
                    if (verboseLogging) {
                        System.out.println("[DepartmentalReliableMessaging] Worker despertado - CentralServer no disponible");
                    }
                    continue; // Procesar inmediatamente sin esperar
                } else {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        System.out.println("[DepartmentalReliableMessaging] Worker detenido");
    }

    private void processOfflineVotes() {
        List<String[]> votes = queue.getAll();

        // Si llegamos aquí, sabemos que hay votos (el run() ya lo verificó)
        System.out.println("[DepartmentalReliableMessaging] Procesando " + votes.size() +
                " votos pendientes hacia CentralServer... (Intento " + (consecutiveFailures + 1) + ")");
        System.out.println("[DepartmentalReliableMessaging] Buscando CentralServer disponible...");

        CentralVotationPrx centralProxy = getActiveCentralProxy();
        if (centralProxy == null) {
            consecutiveFailures++;
            System.out.println("[DepartmentalReliableMessaging] CentralServer no disponible");
            System.out.println("[DepartmentalReliableMessaging] Intento " + consecutiveFailures +
                    " fallido - Reintentando en " + (retryInterval/1000) + " segundos...");
            System.out.println("[DepartmentalReliableMessaging] GARANTIA: Seguiremos intentando hasta que TODOS los votos lleguen a CentralServer");

            // Salir para que el run() maneje el sleep
            return;
        }

        // Si conseguimos un proxy, loggear recuperación
        if (consecutiveFailures > 0) {
            System.out.println("[DepartmentalReliableMessaging] ¡CENTRALSERVER RECUPERADO! Despues de " +
                    consecutiveFailures + " intentos fallidos");
            consecutiveFailures = 0;
        }

        int successCount = 0;
        int errorCount = 0;
        int duplicateCount = 0;

        for (String[] vote : votes) {
            try {
                long startTime = System.currentTimeMillis();
                String ackId = centralProxy.processVote(vote[0], vote[1], vote[2]); // citizenId, candidateId, departmentalServerId
                long latency = System.currentTimeMillis() - startTime;

                successCount++;
                String voteKey = vote[0] + "|" + vote[1] + "|" + vote[2];
                ackManager.confirmACK(voteKey, ackId, latency);

                if (verboseLogging) {
                    System.out.println("[DepartmentalReliableMessaging] Voto entregado a CentralServer: " +
                            vote[0] + " -> " + vote[1] + " (desde " + vote[2] + ") | ACK: " + ackId + " (" + latency + "ms)");
                }

            } catch (AlreadyVotedCentralException e) {
                long latency = System.currentTimeMillis() - System.currentTimeMillis();
                String voteKey = vote[0] + "|" + vote[1] + "|" + vote[2];
                ackManager.confirmACK(voteKey, e.ackId, latency);

                duplicateCount++;
                if (verboseLogging) {
                    System.out.println("[DepartmentalReliableMessaging] Voto duplicado en CentralServer: " +
                            vote[0] + " | ACK: " + e.ackId);
                }

            } catch (CentralServerUnavailableException e) {
                System.out.println("[DepartmentalReliableMessaging] CentralServer temporalmente no disponible: " + e.reason);
                System.out.println("[DepartmentalReliableMessaging] Reintentando todos los votos en proxima iteracion");
                errorCount++;
                consecutiveFailures++; // Contar este como un fallo

                // Si hay error de CentralServer, parar procesamiento y reintentar todo
                break;

            } catch (LocalException e) {
                System.out.println("[DepartmentalReliableMessaging] Error de conexion con CentralServer: " + e.getMessage());
                System.out.println("[DepartmentalReliableMessaging] Posible fallo de CentralServer - reintentando todos los votos");
                errorCount++;
                consecutiveFailures++; // Contar este como un fallo

                // Si hay error de conexión, parar procesamiento y reintentar todo
                break;
            }
        }

        // Logging de resultados
        if (errorCount == 0) {
            // ¡TODOS los votos fueron procesados exitosamente!
            queue.clear();
            System.out.println("[DepartmentalReliableMessaging] ¡EXITO TOTAL! Todos los votos entregados a CentralServer");
            System.out.println("[DepartmentalReliableMessaging] Entregados: " + successCount +
                    " nuevos, " + duplicateCount + " duplicados confirmados");
            // No logging de "volviendo a standby" aquí - lo maneja run()

        } else {
            System.out.println("[DepartmentalReliableMessaging] Procesamiento parcial: " + successCount +
                    " exitosos, " + duplicateCount + " duplicados");
            System.out.println("[DepartmentalReliableMessaging] Reintentos pendientes: " +
                    (votes.size() - successCount - duplicateCount) + " votos");
            System.out.println("[DepartmentalReliableMessaging] GARANTIA: Continuaremos hasta entregar TODOS los votos a CentralServer");
        }
    }

    private CentralVotationPrx getActiveCentralProxy() {
        try {
            // Conectar directamente al CentralServer en puerto 8888
            String centralServerEndpoint = "CentralVotation:default -h localhost -p 8888";

            com.zeroc.Ice.ObjectPrx baseProxy = communicator.stringToProxy(centralServerEndpoint);
            CentralVotationPrx proxy = CentralVotationPrx.checkedCast(baseProxy);

            if (proxy == null) {
                if (verboseLogging) {
                    System.out.println("[DepartmentalReliableMessaging] No se pudo hacer cast a CentralVotationPrx");
                }
                return null;
            }

            // Verificar que el proxy está realmente disponible
            try {
                proxy.ping();
                if (verboseLogging) {
                    System.out.println("[DepartmentalReliableMessaging] CentralServer verificado y disponible");
                }
                return proxy;
            } catch (Exception pingEx) {
                if (verboseLogging) {
                    System.out.println("[DepartmentalReliableMessaging] CentralServer no responde al ping: " + pingEx.getMessage());
                }
                return null;
            }

        } catch (Exception e) {
            if (verboseLogging) {
                System.out.println("[DepartmentalReliableMessaging] Error obteniendo proxy de CentralServer: " + e.getMessage());
            }
            return null;
        }
    }

    public void shutdown() {
        if (hasVotesPending) {
            System.out.println("[DepartmentalReliableMessaging] ADVERTENCIA: Deteniendo worker con " + queue.size() +
                    " votos pendientes hacia CentralServer");
            System.out.println("[DepartmentalReliableMessaging] Los votos se procesaran en el proximo inicio");
        }
        running = false;
        this.interrupt();
    }

    public boolean isRunning() {
        return running && isAlive();
    }

    public int getRetryCount() {
        return consecutiveFailures;
    }

    public boolean hasVotesPending() {
        return hasVotesPending;
    }

    public int getPendingVotesCount() {
        return queue.size();
    }

    // Método para forzar activación cuando CentralServer no está disponible
    public void notifyNewVoteAdded() {
        if (!hasVotesPending && queue.size() > 0) {
            System.out.println("[DepartmentalReliableMessaging] CentralServer no disponible - Activating worker");
            this.interrupt(); // Despertar el thread para procesar inmediatamente
        }
    }
}
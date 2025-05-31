import Demo.*;
import com.zeroc.Ice.LocalException;
import com.zeroc.IceGrid.QueryPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Properties;
import java.util.List;

public class VoteRetryWorker extends Thread {
    private final Communicator communicator;
    private final OfflineVoteQueue queue;
    private final VoteACKManager ackManager;
    private final long retryInterval;
    private final boolean verboseLogging;
    private volatile boolean running = true;
    private int consecutiveFailures = 0;
    private boolean hasVotesPending = false;

    public VoteRetryWorker(Communicator communicator, OfflineVoteQueue queue, VoteACKManager ackManager) {
        this.communicator = communicator;
        this.queue = queue;
        this.ackManager = ackManager;

        Properties props = communicator.getProperties();
        this.retryInterval = props.getPropertyAsIntWithDefault("ReliableMessaging.RetryInterval", 10000);
        this.verboseLogging = props.getPropertyAsIntWithDefault("ReliableMessaging.VerboseLogging", 1) == 1;

        this.setDaemon(true);
        this.setName("VoteRetryWorker");

        if (verboseLogging) {
            System.out.println("[ReliableMessaging] Worker configurado:");
            System.out.println("  - Intervalo cuando hay votos pendientes: " + retryInterval + "ms");
            System.out.println("  - Estrategia: INTENTOS ILIMITADOS hasta completar todos los votos");
            System.out.println("  - Solo activo cuando hay votos offline pendientes");
        }
    }

    @Override
    public void run() {
        System.out.println("[ReliableMessaging] VoteRetryWorker iniciado en modo eficiente");

        while (running) {
            try {
                // Solo procesar si hay votos pendientes
                if (queue.size() > 0) {
                    if (!hasVotesPending) {
                        hasVotesPending = true;
                        System.out.println("[ReliableMessaging] ACTIVANDO modo de entrega garantizada");
                    }

                    processOfflineVotes();

                    // Solo esperar intervalo si AÚN hay votos pendientes después del procesamiento
                    if (running && queue.size() > 0) {
                        if (verboseLogging) {
                            System.out.println("[ReliableMessaging] Esperando " + (retryInterval/1000) + " segundos antes del próximo intento...");
                        }
                        Thread.sleep(retryInterval);
                    } else if (hasVotesPending && queue.size() == 0) {
                        // Todos los votos fueron procesados exitosamente
                        hasVotesPending = false;
                        consecutiveFailures = 0;
                        System.out.println("[ReliableMessaging] MISIÓN CUMPLIDA - Volviendo a modo eficiente (dormido)");
                    }
                } else {
                    // No hay votos pendientes - dormir indefinidamente hasta ser despertado
                    if (hasVotesPending) {
                        hasVotesPending = false;
                        consecutiveFailures = 0;
                        System.out.println("[ReliableMessaging] Sin votos pendientes - Worker en modo eficiente (dormido)");
                    }

                    if (verboseLogging) {
                        System.out.println("[ReliableMessaging] Worker durmiendo hasta que lleguen nuevos votos offline...");
                    }

                    // Dormir indefinidamente hasta ser interrumpido
                    Thread.sleep(Long.MAX_VALUE);
                }

            } catch (InterruptedException e) {
                // Si nos interrumpen, significa que hay nuevos votos para procesar
                if (running) {
                    if (verboseLogging) {
                        System.out.println("[ReliableMessaging] Worker despertado - nuevos votos detectados");
                    }
                    continue; // Procesar inmediatamente sin esperar
                } else {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        System.out.println("[ReliableMessaging] VoteRetryWorker detenido");
    }

    private void processOfflineVotes() {
        List<String[]> votes = queue.getAll();

        // Si llegamos aquí, sabemos que hay votos (el run() ya lo verificó)
        System.out.println("[ReliableMessaging] Procesando " + votes.size() + " votos pendientes... (Intento " + (consecutiveFailures + 1) + ")");
        System.out.println("[ReliableMessaging] Buscando servidor disponible a través de IceGrid...");

        VotationPrx votationProxy = getActiveProxy();
        if (votationProxy == null) {
            consecutiveFailures++;
            System.out.println("[ReliableMessaging] No hay servidor disponible en IceGrid");
            System.out.println("[ReliableMessaging] Intento " + consecutiveFailures + " fallido - Reintentando en " + (retryInterval/1000) + " segundos...");
            System.out.println("[ReliableMessaging] GARANTÍA: Seguiremos intentando hasta que TODOS los votos lleguen");

            // Salir para que el run() maneje el sleep
            return;
        }

        // Si conseguimos un proxy, loggear recuperación
        if (consecutiveFailures > 0) {
            System.out.println("[ReliableMessaging] ¡SERVIDOR RECUPERADO! Después de " + consecutiveFailures + " intentos fallidos");
            consecutiveFailures = 0;
        }

        int successCount = 0;
        int errorCount = 0;
        int duplicateCount = 0;

        for (String[] vote : votes) {
            try {
                long startTime = System.currentTimeMillis();
                String ackId = votationProxy.sendVote(vote[0], vote[1]);
                long latency = System.currentTimeMillis() - startTime;

                successCount++;
                String voteKey = vote[0] + "|" + vote[1];
                ackManager.confirmACK(voteKey, ackId, latency);

                if (verboseLogging) {
                    System.out.println("[ReliableMessaging] Voto entregado: " + vote[0] + " -> " + vote[1] + " | ACK: " + ackId + " (" + latency + "ms)");
                }

            } catch (AlreadyVotedException e) {
                long latency = System.currentTimeMillis() - System.currentTimeMillis();
                String voteKey = vote[0] + "|" + vote[1];
                ackManager.confirmACK(voteKey, e.ackId, latency);

                duplicateCount++;
                if (verboseLogging) {
                    System.out.println("[ReliableMessaging] Voto duplicado procesado: " + vote[0] + " | ACK: " + e.ackId);
                }

            } catch (LocalException e) {
                System.out.println("[ReliableMessaging] Error de conexión enviando voto: " + e.getMessage());
                System.out.println("[ReliableMessaging] Posible failover en curso - reintentando todos los votos en próxima iteración");
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
            System.out.println("[ReliableMessaging] ¡ÉXITO TOTAL! Todos los votos entregados");
            System.out.println("[ReliableMessaging] Entregados: " + successCount + " nuevos, " + duplicateCount + " duplicados confirmados");
            // No logging de "volviendo a standby" aquí - lo maneja run()

        } else {
            System.out.println("[ReliableMessaging] Procesamiento parcial: " + successCount + " exitosos, " + duplicateCount + " duplicados");
            System.out.println("[ReliableMessaging] Reintentos pendientes: " + (votes.size() - successCount - duplicateCount) + " votos");
            System.out.println("[ReliableMessaging] GARANTÍA: Continuaremos hasta entregar TODOS los votos");
        }
    }

    private VotationPrx getActiveProxy() {
        try {
            QueryPrx query = QueryPrx.checkedCast(
                    communicator.stringToProxy("DemoIceGrid/Query")
            );
            if (query == null) {
                if (verboseLogging) {
                    System.out.println("[ReliableMessaging] No se pudo conectar al IceGrid Query");
                }
                return null;
            }

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
                    System.out.println("[ReliableMessaging] Servidor verificado y disponible");
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
        if (hasVotesPending) {
            System.out.println("[ReliableMessaging] ADVERTENCIA: Deteniendo worker con " + queue.size() + " votos pendientes");
            System.out.println("[ReliableMessaging] Los votos se procesarán en el próximo inicio");
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

    // Metodo para forzar activación cuando se agregan votos offline
    public void notifyNewVoteAdded() {
        if (!hasVotesPending && queue.size() > 0) {
            System.out.println("[ReliableMessaging] Nuevo voto offline detectado - Activando worker");
            this.interrupt(); // Despertar el thread para procesar inmediatamente
        }
    }
}
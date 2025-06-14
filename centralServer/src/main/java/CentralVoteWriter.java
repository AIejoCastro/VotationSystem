import java.util.concurrent.BlockingQueue;
import java.util.List;
import java.util.ArrayList;

/**
 * CentralVoteWriter - Version centralizada del VoteWriter 
 * Para batch processing optimizado en el servidor central
 */
public class CentralVoteWriter implements Runnable {
    private final BlockingQueue<CentralVoteCommand> queue;
    private final CentralVoteDAO dao;
    private final int BATCH_SIZE = 50;
    private final long BATCH_TIMEOUT = 100; // 100ms
    private final String threadName;

    public CentralVoteWriter(BlockingQueue<CentralVoteCommand> queue) {
        this.queue = queue;
        this.dao = new CentralVoteDAO();
        this.threadName = "CentralVoteWriter-" + Thread.currentThread().getId();
    }

    @Override
    public void run() {
        Thread.currentThread().setName(threadName);
        System.out.println("[" + threadName + "] Worker central iniciado para procesamiento batch");

        List<CentralVoteCommand> batch = new ArrayList<>(BATCH_SIZE);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // OPTIMIZACIÓN: Procesar en batches para mejor performance I/O
                collectBatch(batch);

                if (!batch.isEmpty()) {
                    processBatch(batch);
                    batch.clear();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[" + threadName + "] Worker central interrumpido");

                // Procesar votos restantes antes de terminar
                if (!batch.isEmpty()) {
                    try {
                        processBatch(batch);
                    } catch (Exception ex) {
                        System.err.println("[" + threadName + "] Error procesando batch final: " + ex.getMessage());
                    }
                }
                break;

            } catch (Exception e) {
                System.err.println("[" + threadName + "] Error en worker central: " + e.getMessage());
                e.printStackTrace();

                // Continuar procesando a pesar del error
                batch.clear();
            }
        }

        System.out.println("[" + threadName + "] Worker central terminado");
    }

    /**
     * Recolectar batch de votos con timeout
     */
    private void collectBatch(List<CentralVoteCommand> batch) throws InterruptedException {
        long startTime = System.currentTimeMillis();

        // Tomar al menos un elemento (bloquea si es necesario)
        CentralVoteCommand firstVote = queue.take();
        batch.add(firstVote);

        // Recolectar votos adicionales hasta BATCH_SIZE o timeout
        while (batch.size() < BATCH_SIZE &&
                (System.currentTimeMillis() - startTime) < BATCH_TIMEOUT) {

            CentralVoteCommand vote = queue.poll(
                    BATCH_TIMEOUT - (System.currentTimeMillis() - startTime),
                    java.util.concurrent.TimeUnit.MILLISECONDS
            );

            if (vote != null) {
                batch.add(vote);
            } else {
                break; // Timeout alcanzado
            }
        }
    }

    /**
     * Procesar batch de votos con manejo de errores
     */
    private void processBatch(List<CentralVoteCommand> batch) {
        long startTime = System.currentTimeMillis();

        try {
            dao.saveBatch(batch);

            long duration = System.currentTimeMillis() - startTime;

            if (batch.size() > 1) {
                System.out.println("[" + threadName + "] Batch central procesado: " +
                        batch.size() + " votos en " + duration + "ms");
            }

        } catch (Exception e) {
            System.err.println("[" + threadName + "] Error en batch central, procesando individualmente: " + e.getMessage());

            // Fallback: procesar votos individualmente
            for (CentralVoteCommand vote : batch) {
                try {
                    vote.persist(dao);
                } catch (Exception individualError) {
                    System.err.println("[" + threadName + "] Error individual en voto central " +
                            vote.toString() + ": " + individualError.getMessage());
                }
            }
        }
    }
}
package loadtester;

import Query.QueryStationPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;


public class Main {

    public static void main(String[] args) {
        int[] tandas = {100, 500, 1000, 2000, 2666};

        try (Communicator communicator = Util.initialize(args)) {
            ObjectPrx base = communicator.stringToProxy("QueryStation:default -h 10.147.17.101 -p 8888");
            QueryStationPrx proxy = QueryStationPrx.checkedCast(base);

            if (proxy == null) {
                System.err.println(" No se pudo conectar con el servidor.");
                return;
            }

            for (int total : tandas) {
                System.out.println("\n Iniciando tanda de " + total + " solicitudes...");

                Thread[] threads = new Thread[total];
                long start = System.currentTimeMillis();

                for (int i = 0; i < total; i++) {
                    final int id = i;
                    threads[i] = new Thread(() -> {
                        try {
                            String response = proxy.query("12345678");
                            if (response == null) {
                                System.out.println("[" + id + "] ");
                            } else {
                                System.out.println(response);
                                System.out.println("[" + id + "] ");
                            }
                        } catch (Exception e) {
                            System.out.println("[" + id + "]  Error: " + e.getMessage());
                        }
                    });
                    threads[i].start();
                }

                for (Thread t : threads) {
                    t.join();
                }

                long end = System.currentTimeMillis();
                System.out.println("Tanda de " + total + " completada en " + (end - start) + " ms");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

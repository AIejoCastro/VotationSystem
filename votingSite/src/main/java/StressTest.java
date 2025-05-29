import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Util;
import Demo.VotationPrx;

import java.util.concurrent.*;

public class StressTest {

    public static void main(String[] args) throws Exception {
        int totalVoters = 1000; // Número de votos simulados
        int threads = 50;       // Número de hilos concurrentes

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try (Communicator communicator = Util.initialize(args, "config.votingSite")) {
            System.out.println("Conectando a servidores de votación a través de IceGrid...");

            // Crear proxies para los 3 servidores según tu template.xml
            VotationPrx[] proxies = new VotationPrx[3];
            String[] serverIds = {"hello-1", "hello-2", "hello-3"};
            java.util.List<VotationPrx> availableProxies = new java.util.ArrayList<>();
            java.util.List<String> availableServerIds = new java.util.ArrayList<>();

            for (int i = 0; i < 3; i++) {
                try {
                    proxies[i] = VotationPrx.checkedCast(
                            communicator.stringToProxy(serverIds[i]));

                    if (proxies[i] != null) {
                        // Verificar que el servidor responde
                        proxies[i].ice_ping();
                        availableProxies.add(proxies[i]);
                        availableServerIds.add(serverIds[i]);
                        System.out.println("Conectado a servidor: " + serverIds[i]);
                    }
                } catch (Exception e) {
                    System.out.println("No se pudo conectar a " + serverIds[i] + ": " + e.getMessage());
                }
            }

            if (availableProxies.isEmpty()) {
                System.err.println("No se pudo conectar a ningún servidor.");
                System.err.println("Asegúrate de que:");
                System.err.println("1. IceGrid Registry esté ejecutándose");
                System.err.println("2. IceGrid Node esté ejecutándose");
                System.err.println("3. La aplicación esté desplegada: icegridadmin -e 'application add template.xml'");
                return;
            }

            System.out.println("Servidores disponibles: " + availableProxies.size() + " de 3");
            System.out.println("Iniciando stress test distribuido...");
            CountDownLatch latch = new CountDownLatch(totalVoters);
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < totalVoters; i++) {
                final int id = i;
                final VotationPrx selectedProxy = availableProxies.get(i % availableProxies.size());
                final String serverId = availableServerIds.get(i % availableServerIds.size());

                executor.submit(() -> {
                    try {
                        selectedProxy.sendVote("citizen" + id, "CandidateA");
                        if (id % 100 == 0) {
                            System.out.println("Procesados " + id + " votos... (último en " + serverId + ")");
                        }
                    } catch (Exception e) {
                        String errorMsg = e.getMessage();
                        if (errorMsg == null || errorMsg.isEmpty()) {
                            errorMsg = e.getClass().getSimpleName();
                        }
                        System.out.println("Error votando (ciudadano" + id + " en " + serverId + "): " + errorMsg);
                        if (id < 10) { // Mostrar stack trace de los primeros errores para debug
                            e.printStackTrace();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(); // Espera a que terminen todos
            executor.shutdown();

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            System.out.println("=== Stress Test Completado ===");
            System.out.println("Total votos: " + totalVoters);
            System.out.println("Servidores utilizados: " + availableProxies.size() + " de 3 disponibles");
            if (!availableProxies.isEmpty()) {
                System.out.println("Distribución: ~" + (totalVoters/availableProxies.size()) + " votos por servidor");
            }
            System.out.println("Tiempo total: " + duration + " ms");
            System.out.println("Votos por segundo: " + (totalVoters * 1000.0 / duration));
        }
    }
}
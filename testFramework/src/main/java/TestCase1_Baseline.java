import Proxy.*;
import java.util.concurrent.*;

public class TestCase1_Baseline {

    public static boolean runTest() {
        System.out.println("\nCASO 1: OPERACIÓN NORMAL");
        System.out.println("═══════════════════════════════════════════════");

        VotingMetrics.reset();

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize()) {
            communicator.getProperties().setProperty("Ice.Default.Locator", "DemoIceGrid/Locator:default -h localhost -p 4061");

            // Conectar al VotingSite
            VotingProxyPrx proxy = VotingProxyPrx.checkedCast(
                    communicator.stringToProxy("VotingProxy:default -h localhost -p 9999")
            );

            if (proxy == null) {
                System.err.println("No se pudo conectar al VotingSite");
                return false;
            }

            // Verificar estado del sistema
            String systemStatus = proxy.getSystemStatus();
            System.out.println("Estado del sistema: " + systemStatus);

            // Simular 100 votantes únicos
            VotingSimulator simulator = new VotingSimulator(20);

            long startTime = System.currentTimeMillis();
            simulator.simulateConcurrentVoting(100, proxy);
            long totalTime = System.currentTimeMillis() - startTime;

            simulator.shutdown();

            // Esperar procesamiento
            Thread.sleep(5000);

            // Analizar resultados
            VotingMetrics.TestResults results = VotingMetrics.getResults();
            results.printSummary();

            System.out.println("\nTiempo total de prueba: " + totalTime + " ms");
            System.out.println("Throughput: " + String.format("%.2f", (double) results.totalVotesSent / totalTime * 1000) + " votos/segundo");

            // Criterios de éxito
            boolean success = results.passesReliabilityTest() &&
                    results.totalVotesSent == 100 &&
                    results.totalACKsReceived == 100 &&
                    results.uniqueVotersCount == 100 &&
                    results.duplicatesDetected == 0;

            if (success) {
                System.out.println("\nCASO 1: EXITOSO - Baseline establecido correctamente");
            } else {
                System.out.println("\nCASO 1: FALLIDO - No se cumplieron los criterios");
            }

            return success;

        } catch (Exception e) {
            System.err.println("Error ejecutando Caso 1: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
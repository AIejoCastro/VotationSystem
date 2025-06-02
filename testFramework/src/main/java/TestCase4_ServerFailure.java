import Proxy.*;

import java.util.concurrent.CompletableFuture;

public class TestCase4_ServerFailure {

    public static boolean runTest() {
        System.out.println("\nCASO 4: FALLO DE SERVIDOR DEPARTAMENTAL");
        System.out.println("===============================================");

        VotingMetrics.reset();

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize()) {
            communicator.getProperties().setProperty("Ice.Default.Locator", "DemoIceGrid/Locator:default -h localhost -p 4061");

            VotingProxyPrx proxy = VotingProxyPrx.checkedCast(
                    communicator.stringToProxy("VotingProxy:default -h localhost -p 9999")
            );

            if (proxy == null) {
                System.err.println("ERROR: No se pudo conectar al VotingSite");
                return false;
            }

            VotingSimulator simulator = new VotingSimulator(20);

            System.out.println("Iniciando votacion distribuida con 60 votantes");
            System.out.println("Los votos se distribuiran entre multiples servidores via IceGrid");

            // Iniciar votacion continua
            CompletableFuture<Void> votingTask = CompletableFuture.runAsync(() -> {
                for (int i = 1; i <= 60; i++) {
                    String citizenId = String.format("distributed_citizen%05d", i);
                    String candidateId = "candidate" + String.format("%03d", (i % 4) + 1);
                    simulator.simulateVoter(citizenId, candidateId, proxy, 1000); // 1 segundo entre votos
                }
            });

            // Esperar a que se procesen algunos votos
            Thread.sleep(15000);

            VotingMetrics.TestResults midResults = VotingMetrics.getResults();
            System.out.println("Votos procesados hasta ahora: " + midResults.totalACKsReceived);

            System.out.println("\nSIMULANDO FALLO DE SERVIDOR - Terminar SimpleServer-1");
            System.out.println("INSTRUCCION MANUAL: Termine el proceso SimpleServer-1 ahora");
            System.out.println("(Use Ctrl+C en el terminal del servidor o kill del proceso)");
            System.out.println("Presione Enter cuando haya terminado el servidor...");
            System.in.read();

            System.out.println("Continuando votacion con servidores restantes...");
            System.out.println("IceGrid debe redirigir automaticamente al servidor disponible");

            // Esperar a que termine la votacion
            votingTask.join();

            // Esperar procesamiento final
            Thread.sleep(10000);

            simulator.shutdown();

            // Analisis de resultados
            VotingMetrics.TestResults finalResults = VotingMetrics.getResults();
            finalResults.printSummary();

            System.out.println("\nANALISIS DE FAILOVER:");
            System.out.println("Votos esperados:          60");
            System.out.println("Votos procesados:         " + finalResults.totalACKsReceived);
            System.out.println("Tasa de exito:            " + String.format("%.2f%%", finalResults.successRate));

            // Criterios de exito
            boolean mostVotesSucceeded = finalResults.totalACKsReceived >= 55; // Al menos 92% de exito
            boolean acceptableLatency = finalResults.avgLatency <= 5000.0; // Max 5 segundos durante failover
            boolean uniquenessIntact = finalResults.passesUniquenessTest();

            boolean success = mostVotesSucceeded && acceptableLatency && uniquenessIntact;

            if (success) {
                System.out.println("\nCASO 4: EXITOSO - Failover automatico funciono correctamente");
            } else {
                System.out.println("\nCASO 4: FALLIDO - Problemas con failover automatico");
            }

            return success;

        } catch (Exception e) {
            System.err.println("Error ejecutando Caso 4: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Metodo main para ejecutar directamente el test de failover
     */
    public static void main(String[] args) {
        System.out.println("EJECUTANDO DIRECTAMENTE CASO 4: FALLO DE SERVIDOR DEPARTAMENTAL");
        System.out.println("================================================================");

        long startTime = System.currentTimeMillis();
        boolean result = runTest();
        long totalTime = System.currentTimeMillis() - startTime;

        System.out.println("\nRESULTADO FINAL DEL TEST DE FAILOVER:");
        System.out.println("=====================================");
        System.out.println("Test de Failover: " + (result ? "EXITOSO" : "FALLIDO"));
        System.out.println("Tiempo total: " + (totalTime / 1000) + " segundos");

        if (result) {
            System.out.println("Failover automatico validado exitosamente");
            System.out.println("Sistema resiliente a fallos de servidor");
        } else {
            System.out.println("Se requieren mejoras en failover automatico");
        }

        System.out.println("=====================================");
        System.exit(result ? 0 : 1);
    }
}
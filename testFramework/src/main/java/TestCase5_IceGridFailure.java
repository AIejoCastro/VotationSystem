import Proxy.*;

public class TestCase5_IceGridFailure {

    public static boolean runTest() {
        System.out.println("\nCASO 5: FALLO COMPLETO DE ICEGRID");
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

            VotingSimulator simulator = new VotingSimulator(15);

            System.out.println("FALLO CATASTROFICO - Deteniendo IceGrid Registry completamente");
            System.out.println("INSTRUCCION MANUAL: Detenga el IceGrid Registry (icegridregistry)");
            System.out.println("TAMBIEN detenga todos los servidores departamentales");
            System.out.println("Presione Enter cuando haya detenido TODA la infraestructura...");
            System.in.read();

            System.out.println("Enviando 40 votos durante fallo catastrofico...");
            System.out.println("Todos los votos deben ir a cola offline del reliable messaging");

            long catastrophicFailureStart = System.currentTimeMillis();

            // Enviar votos durante fallo catastrofico
            for (int i = 1; i <= 40; i++) {
                String citizenId = String.format("catastrophic_citizen%05d", i);
                String candidateId = "candidate" + String.format("%03d", (i % 4) + 1);
                simulator.simulateVoter(citizenId, candidateId, proxy, 500);
            }

            Thread.sleep(5000);

            VotingMetrics.TestResults duringFailureResults = VotingMetrics.getResults();
            int pendingVotes = proxy.getPendingVotesCount();

            System.out.println("Durante fallo catastrofico:");
            System.out.println("Votos enviados:           " + duringFailureResults.totalVotesSent);
            System.out.println("ACKs inmediatos:          " + duringFailureResults.totalACKsReceived);
            System.out.println("Votos en cola offline:    " + pendingVotes);

            System.out.println("\nRESTAURANDO INFRAESTRUCTURA COMPLETA");
            System.out.println("INSTRUCCION MANUAL: Ejecute la siguiente secuencia:");
            System.out.println("1. Reinicie IceGrid Registry: icegridregistry --Ice.Config=config/grid.config");
            System.out.println("2. Reinicie IceGrid Node: icegridnode --Ice.Config=config/node.config");
            System.out.println("3. Deploy aplicacion: icegridadmin --Ice.Config=config/grid.config -e \"application add 'config/template.xml'\"");
            System.out.println("Presione Enter cuando toda la infraestructura este funcionando...");
            System.in.read();

            System.out.println("Esperando recuperacion automatica del reliable messaging...");

            // Monitorear recuperacion automatica
            int maxRecoveryTime = 180; // 3 minutos maximo
            int recoveryTime = 0;
            VotingMetrics.TestResults finalResults;

            do {
                Thread.sleep(10000);
                recoveryTime += 10;

                try {
                    int currentPendingVotes = proxy.getPendingVotesCount();
                    finalResults = VotingMetrics.getResults();

                    System.out.println("Recuperacion (" + recoveryTime + "s) - Pendientes: " + currentPendingVotes +
                            " | ACKs totales: " + finalResults.totalACKsReceived);

                    if (currentPendingVotes == 0) {
                        System.out.println("Recuperacion completa! Todos los votos procesados");
                        break;
                    }
                } catch (Exception e) {
                    System.out.println("Recuperacion (" + recoveryTime + "s) - Aun conectando...");
                }

            } while (recoveryTime < maxRecoveryTime);

            long totalRecoveryTime = System.currentTimeMillis() - catastrophicFailureStart;

            simulator.shutdown();

            // Analisis final
            finalResults = VotingMetrics.getResults();
            finalResults.printSummary();

            System.out.println("\nTiempo total de recuperacion: " + (totalRecoveryTime / 1000) + " segundos");
            System.out.println("Votos enviados durante fallo: 40");
            System.out.println("Votos finalmente procesados: " + finalResults.totalACKsReceived);

            // Criterios de exito para fallo catastrofico
            boolean completeRecovery = finalResults.totalACKsReceived >= 38; // Al menos 95% recuperado
            boolean reasonableRecoveryTime = totalRecoveryTime <= 300000; // Maximo 5 minutos
            boolean integrityMaintained = finalResults.passesUniquenessTest();

            boolean success = completeRecovery && reasonableRecoveryTime && integrityMaintained;

            if (success) {
                System.out.println("\nCASO 5: EXITOSO - Recuperacion catastrofica funciono correctamente");
            } else {
                System.out.println("\nCASO 5: FALLIDO - Problemas con recuperacion catastrofica");
            }

            return success;

        } catch (Exception e) {
            System.err.println("Error ejecutando Caso 5: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Metodo main para ejecutar directamente el test de fallo catastrofico
     */
    public static void main(String[] args) {
        System.out.println("EJECUTANDO DIRECTAMENTE CASO 5: FALLO COMPLETO DE ICEGRID");
        System.out.println("==========================================================");

        long startTime = System.currentTimeMillis();
        boolean result = runTest();
        long totalTime = System.currentTimeMillis() - startTime;

        System.out.println("\nRESULTADO FINAL DEL TEST CATASTROFICO:");
        System.out.println("======================================");
        System.out.println("Test Catastrofico: " + (result ? "EXITOSO" : "FALLIDO"));
        System.out.println("Tiempo total: " + (totalTime / 1000) + " segundos");

        if (result) {
            System.out.println("Recuperacion catastrofica validada exitosamente");
            System.out.println("Sistema resiliente a fallos completos");
        } else {
            System.out.println("Se requieren mejoras en recuperacion catastrofica");
        }

        System.out.println("======================================");
        System.exit(result ? 0 : 1);
    }
}
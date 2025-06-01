import Proxy.*;

public class TestCase5_IceGridFailure {

    public static boolean runTest() {
        System.out.println("\n🧪 CASO 5: FALLO COMPLETO DE ICEGRID");
        System.out.println("═══════════════════════════════════════════════");

        VotingMetrics.reset();

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize()) {
            communicator.getProperties().setProperty("Ice.Default.Locator", "DemoIceGrid/Locator:default -h localhost -p 4061");

            VotingProxyPrx proxy = VotingProxyPrx.checkedCast(
                    communicator.stringToProxy("VotingProxy:default -h localhost -p 9999")
            );

            if (proxy == null) {
                System.err.println("❌ No se pudo conectar al VotingSite");
                return false;
            }

            VotingSimulator simulator = new VotingSimulator(15);

            System.out.println("💥 FALLO CATASTRÓFICO - Deteniendo IceGrid Registry completamente");
            System.out.println("⚠️  INSTRUCCIÓN MANUAL: Detenga el IceGrid Registry (icegridregistry)");
            System.out.println("⚠️  TAMBIÉN detenga todos los servidores departamentales");
            System.out.println("⚠️  Presione Enter cuando haya detenido TODA la infraestructura...");
            System.in.read();

            System.out.println("📤 Enviando 40 votos durante fallo catastrófico...");
            System.out.println("🔄 Todos los votos deben ir a cola offline del reliable messaging");

            long catastrophicFailureStart = System.currentTimeMillis();

            // Enviar votos durante fallo catastrófico
            for (int i = 1; i <= 40; i++) {
                String citizenId = String.format("catastrophic_citizen%05d", i);
                String candidateId = "candidate" + String.format("%03d", (i % 4) + 1);
                simulator.simulateVoter(citizenId, candidateId, proxy, 500);
            }

            Thread.sleep(5000);

            VotingMetrics.TestResults duringFailureResults = VotingMetrics.getResults();
            int pendingVotes = proxy.getPendingVotesCount();

            System.out.println("📊 Durante fallo catastrófico:");
            System.out.println("Votos enviados:           " + duringFailureResults.totalVotesSent);
            System.out.println("ACKs inmediatos:          " + duringFailureResults.totalACKsReceived);
            System.out.println("Votos en cola offline:    " + pendingVotes);

            System.out.println("\n🔄 RESTAURANDO INFRAESTRUCTURA COMPLETA");
            System.out.println("⚠️  INSTRUCCIÓN MANUAL: Ejecute la siguiente secuencia:");
            System.out.println("⚠️  1. Reinicie IceGrid Registry: icegridregistry --Ice.Config=config/grid.config");
            System.out.println("⚠️  2. Reinicie IceGrid Node: icegridnode --Ice.Config=config/node.config");
            System.out.println("⚠️  3. Deploy aplicación: icegridadmin --Ice.Config=config/grid.config -e \"application add 'config/template.xml'\"");
            System.out.println("⚠️  Presione Enter cuando toda la infraestructura esté funcionando...");
            System.in.read();

            System.out.println("⏳ Esperando recuperación automática del reliable messaging...");

            // Monitorear recuperación automática
            int maxRecoveryTime = 180; // 3 minutos máximo
            int recoveryTime = 0;
            VotingMetrics.TestResults finalResults;

            do {
                Thread.sleep(10000);
                recoveryTime += 10;

                try {
                    int currentPendingVotes = proxy.getPendingVotesCount();
                    finalResults = VotingMetrics.getResults();

                    System.out.println("⏱️  Recuperación (" + recoveryTime + "s) - Pendientes: " + currentPendingVotes +
                            " | ACKs totales: " + finalResults.totalACKsReceived);

                    if (currentPendingVotes == 0) {
                        System.out.println("🎉 ¡Recuperación completa! Todos los votos procesados");
                        break;
                    }
                } catch (Exception e) {
                    System.out.println("⏱️  Recuperación (" + recoveryTime + "s) - Aún conectando...");
                }

            } while (recoveryTime < maxRecoveryTime);

            long totalRecoveryTime = System.currentTimeMillis() - catastrophicFailureStart;

            simulator.shutdown();

            // Análisis final
            finalResults = VotingMetrics.getResults();
            finalResults.printSummary();

            System.out.println("\n⏱️  Tiempo total de recuperación: " + (totalRecoveryTime / 1000) + " segundos");
            System.out.println("📊 Votos enviados durante fallo: 40");
            System.out.println("📊 Votos finalmente procesados: " + finalResults.totalACKsReceived);

            // Criterios de éxito para fallo catastrófico
            boolean completeRecovery = finalResults.totalACKsReceived >= 38; // Al menos 95% recuperado
            boolean reasonableRecoveryTime = totalRecoveryTime <= 300000; // Máximo 5 minutos
            boolean integrityMaintained = finalResults.passesUniquenessTest();

            boolean success = completeRecovery && reasonableRecoveryTime && integrityMaintained;

            if (success) {
                System.out.println("\n✅ CASO 5: EXITOSO - Recuperación catastrófica funcionó correctamente");
            } else {
                System.out.println("\n❌ CASO 5: FALLIDO - Problemas con recuperación catastrófica");
            }

            return success;

        } catch (Exception e) {
            System.err.println("❌ Error ejecutando Caso 5: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
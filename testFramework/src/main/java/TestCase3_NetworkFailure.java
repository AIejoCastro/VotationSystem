import Proxy.*;

public class TestCase3_NetworkFailure {

    public static boolean runTest() {
        System.out.println("\n🧪 CASO 3: FALLO DE RED TEMPORAL");
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

            System.out.println("📡 Fase 1: Enviar 15 votos en condiciones normales");

            // Fase 1: Votos normales (deben ser exitosos)
            for (int i = 1; i <= 15; i++) {
                String citizenId = String.format("phase1_citizen%05d", i);
                String candidateId = "candidate001";
                simulator.simulateVoter(citizenId, candidateId, proxy, 100);
            }

            Thread.sleep(3000);
            VotingMetrics.TestResults phase1Results = VotingMetrics.getResults();
            System.out.println("✅ Fase 1 completada: " + phase1Results.totalACKsReceived + " votos exitosos");

            System.out.println("\n🔌 SIMULANDO FALLO DE RED - Deteniendo servidores departamentales...");
            System.out.println("⚠️  INSTRUCCIÓN MANUAL: Detenga los servidores departamentales ahora");
            System.out.println("⚠️  Presione Enter cuando haya detenido los servidores...");
            System.in.read();

            System.out.println("📤 Fase 2: Enviar 15 votos durante fallo (deben ir a cola offline)");

            // Fase 2: Votos durante fallo (deben ir a reliable messaging)
            long failureStartTime = System.currentTimeMillis();
            for (int i = 1; i <= 15; i++) {
                String citizenId = String.format("phase2_citizen%05d", i);
                String candidateId = "candidate002";
                simulator.simulateVoter(citizenId, candidateId, proxy, 200);
            }

            Thread.sleep(5000);
            VotingMetrics.TestResults phase2Results = VotingMetrics.getResults();
            int phase2NewVotes = phase2Results.totalVotesSent - phase1Results.totalVotesSent;
            int phase2NewACKs = phase2Results.totalACKsReceived - phase1Results.totalACKsReceived;

            System.out.println("📊 Fase 2: " + phase2NewVotes + " votos enviados, " + phase2NewACKs + " ACKs recibidos");

            System.out.println("\n🔄 RESTAURANDO CONECTIVIDAD - Reinicie los servidores departamentales");
            System.out.println("⚠️  INSTRUCCIÓN MANUAL: Reinicie los servidores departamentales ahora");
            System.out.println("⚠️  Presione Enter cuando los servidores estén funcionando...");
            System.in.read();

            System.out.println("⏳ Esperando procesamiento automático de votos pendientes...");

            // Esperar hasta 2 minutos para que reliable messaging procese votos pendientes
            int maxWaitTime = 120; // 2 minutos
            int waitTime = 0;
            VotingMetrics.TestResults finalResults;

            do {
                Thread.sleep(5000);
                waitTime += 5;
                finalResults = VotingMetrics.getResults();

                int pendingVotes = proxy.getPendingVotesCount();
                System.out.println("⏱️  Esperando... (" + waitTime + "s) - Votos pendientes: " + pendingVotes);

                if (pendingVotes == 0) {
                    System.out.println("✅ Todos los votos pendientes han sido procesados");
                    break;
                }

            } while (waitTime < maxWaitTime);

            long recoveryTime = System.currentTimeMillis() - failureStartTime;

            simulator.shutdown();

            // Análisis final
            finalResults = VotingMetrics.getResults();
            finalResults.printSummary();

            System.out.println("\n⏱️  Tiempo total de recuperación: " + recoveryTime + " ms");
            System.out.println("📊 Votos totales esperados: 30");
            System.out.println("📊 ACKs finales recibidos: " + finalResults.totalACKsReceived);

            // Criterios de éxito
            boolean allVotesProcessed = finalResults.totalACKsReceived >= 28; // Al menos 93% de votos
            boolean recoveryTimeAcceptable = recoveryTime <= 180000; // Máximo 3 minutos
            boolean noVoteLoss = (finalResults.totalACKsReceived >= finalResults.totalVotesSent - 2); // Máximo 2 votos perdidos

            boolean success = allVotesProcessed && recoveryTimeAcceptable && noVoteLoss;

            if (success) {
                System.out.println("\n✅ CASO 3: EXITOSO - Reliable messaging funcionó correctamente");
            } else {
                System.out.println("\n❌ CASO 3: FALLIDO - Problemas con recuperación automática");
            }

            return success;

        } catch (Exception e) {
            System.err.println("❌ Error ejecutando Caso 3: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
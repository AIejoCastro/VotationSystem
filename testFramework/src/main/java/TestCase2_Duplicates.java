import Proxy.*;

public class TestCase2_Duplicates {

    public static boolean runTest() {
        System.out.println("\n🧪 CASO 2: DETECCIÓN DE DUPLICADOS");
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

            // Simular 50 ciudadanos con intentos múltiples de voto
            VotingSimulator simulator = new VotingSimulator(10);

            System.out.println("🔄 Simulando 50 ciudadanos con 3 intentos de voto cada uno...");

            long startTime = System.currentTimeMillis();
            simulator.simulateDuplicateVoting(50, proxy);
            long totalTime = System.currentTimeMillis() - startTime;

            simulator.shutdown();

            // Esperar procesamiento
            Thread.sleep(3000);

            // Analizar resultados
            VotingMetrics.TestResults results = VotingMetrics.getResults();
            results.printSummary();

            System.out.println("\n⏱️  Tiempo total de prueba: " + totalTime + " ms");

            // Criterios específicos para duplicados
            int expectedValidVotes = 50;      // Solo el primer voto de cada ciudadano
            int expectedDuplicates = 100;     // 2 duplicados por ciudadano (50 * 2)
            int expectedTotalAttempts = 150;   // 3 intentos por ciudadano (50 * 3)

            boolean duplicateDetectionWorking = results.duplicatesDetected >= 90; // Al menos 90% detectados
            boolean correctUniqueVoters = results.uniqueVotersCount == expectedValidVotes;
            boolean uniqueACKs = results.passesUniquenessTest();

            System.out.println("\n📊 ANÁLISIS DE DUPLICADOS:");
            System.out.println("Votos únicos esperados:   " + expectedValidVotes);
            System.out.println("Duplicados esperados:     ~" + expectedDuplicates);
            System.out.println("Duplicados detectados:    " + results.duplicatesDetected);
            System.out.println("Detección efectiva:       " + duplicateDetectionWorking);

            boolean success = duplicateDetectionWorking && correctUniqueVoters && uniqueACKs;

            if (success) {
                System.out.println("\n✅ CASO 2: EXITOSO - Detección de duplicados funciona correctamente");
            } else {
                System.out.println("\n❌ CASO 2: FALLIDO - Problemas con detección de duplicados");
            }

            return success;

        } catch (Exception e) {
            System.err.println("❌ Error ejecutando Caso 2: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
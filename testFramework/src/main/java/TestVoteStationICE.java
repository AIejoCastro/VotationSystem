//
// TestVoteStationICE - Cliente de prueba para automatización
// Demuestra el uso de la interfaz ICE VoteStation
//

import VotingStation.*;

public class TestVoteStationICE {

    public static void main(String[] args) {
        System.out.println("════════════════════════════════════════════════════════════");
        System.out.println("🧪 CLIENTE DE PRUEBA - INTERFAZ ICE VOTESTATION");
        System.out.println("   Automatización según especificación");
        System.out.println("════════════════════════════════════════════════════════════");

        if (args.length < 1) {
            System.out.println("❌ ERROR: Especifique el endpoint de la VoteStation");
            System.out.println("Uso: java TestVoteStationICE <endpoint>");
            System.out.println("Ejemplo: java TestVoteStationICE \"VoteStation:tcp -h localhost -p 10123\"");
            System.exit(1);
        }

        String endpoint = args[0];
        System.out.println("🔗 Conectando a VoteStation en: " + endpoint);

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize()) {

            // Crear proxy a la VoteStation
            com.zeroc.Ice.ObjectPrx baseProxy = communicator.stringToProxy(endpoint);
            VotingStation.VoteStationPrx voteStation = VotingStation.VoteStationPrx.checkedCast(baseProxy);

            if (voteStation == null) {
                System.err.println("❌ ERROR: No se pudo conectar a VoteStation");
                System.err.println("   Verifique que VotingMachine esté ejecutándose");
                System.exit(1);
            }

            System.out.println("✅ Conectado exitosamente a VoteStation");

            // PRUEBA 1: Obtener estado de la estación
            System.out.println("\n📊 PRUEBA 1: Estado de la estación");
            System.out.println("─".repeat(50));
            String status = voteStation.getStationStatus();
            System.out.println("Estado: " + status);

            // PRUEBA 2: Obtener lista de candidatos
            System.out.println("\n📋 PRUEBA 2: Lista de candidatos");
            System.out.println("─".repeat(50));
            String candidates = voteStation.getCandidateList();
            System.out.println(candidates);

            // PRUEBA 3: Votos de prueba según especificación
            System.out.println("\n🗳️  PRUEBA 3: Casos de votación automatizada");
            System.out.println("─".repeat(50));

            // Caso 1: Voto exitoso
            testVote(voteStation, "12345678", 1, "Primer voto (debe ser exitoso)");

            // Caso 2: Voto duplicado (mismo ciudadano)
            testVote(voteStation, "12345678", 2, "Segundo voto mismo ciudadano (debe retornar 2)");

            // Caso 3: Otro ciudadano, voto exitoso
            testVote(voteStation, "87654321", 3, "Otro ciudadano (debe ser exitoso)");

            // Caso 4: Voto duplicado del segundo ciudadano
            testVote(voteStation, "87654321", 1, "Segundo voto del otro ciudadano (debe retornar 2)");

            // Caso 5: Candidato inválido
            testVote(voteStation, "11111111", 6, "Candidato inválido (debe fallar)");

            // Caso 6: Documento inválido
            testVote(voteStation, "", 1, "Documento vacío (debe fallar)");

            // PRUEBA 4: Verificar votos registrados
            System.out.println("\n🔍 PRUEBA 4: Verificación de votos registrados");
            System.out.println("─".repeat(50));
            testHasVoted(voteStation, "12345678", "Primer ciudadano");
            testHasVoted(voteStation, "87654321", "Segundo ciudadano");
            testHasVoted(voteStation, "99999999", "Ciudadano que no votó");

            // PRUEBA 5: Estado final
            System.out.println("\n📊 PRUEBA 5: Estado final del sistema");
            System.out.println("─".repeat(50));
            String finalStatus = voteStation.getStationStatus();
            System.out.println("Estado final: " + finalStatus);

            System.out.println("\n✅ TODAS LAS PRUEBAS COMPLETADAS");
            System.out.println("════════════════════════════════════════════════════════════");

        } catch (Exception e) {
            System.err.println("❌ ERROR durante las pruebas: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Probar operación de voto y validar resultado
     */
    private static void testVote(VotingStation.VoteStationPrx voteStation, String document, int candidateId, String description) {
        try {
            System.out.println("\n🧪 Test: " + description);
            System.out.println("   Documento: " + document + ", Candidato: " + candidateId);

            int result = voteStation.vote(document, candidateId);

            String resultDescription = interpretResult(result);
            String statusIcon = getStatusIcon(result);

            System.out.println("   " + statusIcon + " Resultado: " + result + " - " + resultDescription);

            // Validar resultados esperados
            validateResult(result, description);

        } catch (Exception e) {
            System.out.println("   ❌ Excepción: " + e.getMessage());
        }
    }

    /**
     * Probar verificación de voto registrado
     */
    private static void testHasVoted(VotingStation.VoteStationPrx voteStation, String document, String description) {
        try {
            boolean hasVoted = voteStation.hasVoted(document);
            String statusIcon = hasVoted ? "✅" : "❌";
            String status = hasVoted ? "YA VOTÓ" : "NO HA VOTADO";

            System.out.println("   " + statusIcon + " " + description + " (" + document + "): " + status);

        } catch (Exception e) {
            System.out.println("   ❌ Error verificando voto: " + e.getMessage());
        }
    }

    /**
     * Interpretar código de resultado según especificación
     */
    private static String interpretResult(int result) {
        switch (result) {
            case 0: return "VOTO EXITOSO";
            case 2: return "CIUDADANO YA VOTÓ (DUPLICADO)";
            case 1: return "ERROR: Documento inválido";
            case 3: return "ERROR: Candidato inválido";
            case 4: return "ERROR: Candidato no encontrado";
            case 5: return "ERROR: Voto inválido";
            case 9: return "ERROR: Error interno del sistema";
            default: return "ERROR: Código desconocido (" + result + ")";
        }
    }

    /**
     * Obtener icono según resultado
     */
    private static String getStatusIcon(int result) {
        switch (result) {
            case 0: return "✅";
            case 2: return "🔄";
            default: return "❌";
        }
    }

    /**
     * Validar que el resultado sea el esperado
     */
    private static void validateResult(int result, String description) {
        boolean isExpectedResult = false;

        if (description.contains("debe ser exitoso") && result == 0) {
            isExpectedResult = true;
        } else if (description.contains("debe retornar 2") && result == 2) {
            isExpectedResult = true;
        } else if (description.contains("debe fallar") && result != 0) {
            isExpectedResult = true;
        }

        if (isExpectedResult) {
            System.out.println("   ✅ Validación: CORRECTO - Resultado esperado");
        } else {
            System.out.println("   ⚠️  Validación: INESPERADO - Revisar comportamiento");
        }
    }

    /**
     * Demostración de uso batch para automatización
     */
    public static void runBatchTest(VotingStation.VoteStationPrx voteStation) {
        System.out.println("\n🔄 PRUEBA BATCH: Múltiples votos automatizados");
        System.out.println("─".repeat(50));

        // Simular datos de votantes para pruebas masivas
        String[][] testData = {
                {"10000001", "1"}, // Ciudadano 1 -> Candidato 1
                {"10000002", "2"}, // Ciudadano 2 -> Candidato 2
                {"10000003", "3"}, // Ciudadano 3 -> Candidato 3
                {"10000004", "1"}, // Ciudadano 4 -> Candidato 1
                {"10000005", "5"}, // Ciudadano 5 -> Voto en blanco
                {"10000001", "2"}, // Ciudadano 1 otra vez (duplicado)
                {"10000002", "1"}, // Ciudadano 2 otra vez (duplicado)
        };

        int successCount = 0;
        int duplicateCount = 0;
        int errorCount = 0;

        for (int i = 0; i < testData.length; i++) {
            String document = testData[i][0];
            int candidateId = Integer.parseInt(testData[i][1]);

            try {
                int result = voteStation.vote(document, candidateId);

                switch (result) {
                    case 0:
                        successCount++;
                        System.out.println("   ✅ Voto " + (i+1) + ": " + document + " -> " + candidateId + " (EXITOSO)");
                        break;
                    case 2:
                        duplicateCount++;
                        System.out.println("   🔄 Voto " + (i+1) + ": " + document + " -> " + candidateId + " (DUPLICADO)");
                        break;
                    default:
                        errorCount++;
                        System.out.println("   ❌ Voto " + (i+1) + ": " + document + " -> " + candidateId + " (ERROR " + result + ")");
                        break;
                }

                // Pequeña pausa para simular comportamiento real
                Thread.sleep(100);

            } catch (Exception e) {
                errorCount++;
                System.out.println("   ❌ Voto " + (i+1) + ": Excepción - " + e.getMessage());
            }
        }

        System.out.println("\n📊 RESUMEN BATCH:");
        System.out.println("   ✅ Exitosos: " + successCount);
        System.out.println("   🔄 Duplicados: " + duplicateCount);
        System.out.println("   ❌ Errores: " + errorCount);
        System.out.println("   📋 Total procesados: " + testData.length);
    }

    /**
     * Prueba de stress con múltiples votantes
     */
    public static void runStressTest(VotingStation.VoteStationPrx voteStation, int voterCount) {
        System.out.println("\n⚡ PRUEBA DE STRESS: " + voterCount + " votantes");
        System.out.println("─".repeat(50));

        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int duplicateCount = 0;
        int errorCount = 0;

        for (int i = 1; i <= voterCount; i++) {
            String document = String.format("stress_%06d", i);
            int candidateId = (i % 5) + 1; // Rotar entre candidatos 1-5

            try {
                int result = voteStation.vote(document, candidateId);

                switch (result) {
                    case 0: successCount++; break;
                    case 2: duplicateCount++; break;
                    default: errorCount++; break;
                }

                // Log progreso cada 100 votos
                if (i % 100 == 0) {
                    System.out.println("   📈 Progreso: " + i + "/" + voterCount + " votos procesados");
                }

            } catch (Exception e) {
                errorCount++;
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        double throughput = voterCount / (totalTime / 1000.0);

        System.out.println("\n📊 RESULTADOS STRESS TEST:");
        System.out.println("   ⏱️  Tiempo total: " + totalTime + " ms");
        System.out.println("   🚀 Throughput: " + String.format("%.2f", throughput) + " votos/segundo");
        System.out.println("   ✅ Exitosos: " + successCount);
        System.out.println("   🔄 Duplicados: " + duplicateCount);
        System.out.println("   ❌ Errores: " + errorCount);
        System.out.println("   📋 Total: " + voterCount);
    }

    /**
     * Método principal extendido con opciones de prueba
     */
    public static void runExtendedTest(String endpoint) {
        System.out.println("🧪 EJECUTANDO PRUEBAS EXTENDIDAS...");

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize()) {
            com.zeroc.Ice.ObjectPrx baseProxy = communicator.stringToProxy(endpoint);
            VotingStation.VoteStationPrx voteStation = VotingStation.VoteStationPrx.checkedCast(baseProxy);

            if (voteStation == null) {
                System.err.println("❌ No se pudo conectar para pruebas extendidas");
                return;
            }

            // Ejecutar prueba batch
            runBatchTest(voteStation);

            // Ejecutar prueba de stress con 50 votantes
            runStressTest(voteStation, 50);

            System.out.println("\n✅ PRUEBAS EXTENDIDAS COMPLETADAS");

        } catch (Exception e) {
            System.err.println("❌ Error en pruebas extendidas: " + e.getMessage());
        }
    }
}
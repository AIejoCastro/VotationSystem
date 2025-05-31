//
// VotingMachine - Interface principal para votantes
//

import Proxy.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

public class VotingMachine {
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static VotingProxyPrx votingProxy;
    private static String machineId;

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println(" ️  SISTEMA DE VOTACIÓN ELECTRÓNICA");
        System.out.println("==============================================");

        int status = 0;
        java.util.List<String> extraArgs = new java.util.ArrayList<>();

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize(args, extraArgs)) {

            // Configurar conexión al VotingSite
            communicator.getProperties().setProperty("Ice.Default.Locator", "DemoIceGrid/Locator:default -h localhost -p 4061");

            if (!extraArgs.isEmpty()) {
                System.err.println("too many arguments");
                status = 1;
            } else {
                status = run(communicator);
            }
        } catch (Exception e) {
            System.err.println("Error crítico del sistema: " + e.getMessage());
            status = 1;
        }

        System.exit(status);
    }

    private static int run(com.zeroc.Ice.Communicator communicator) {
        // Generar ID único para esta máquina de votación
        machineId = "VM-" + System.currentTimeMillis() % 10000;

        System.out.println("🔧 Máquina de Votación ID: " + machineId);
        System.out.println("🔗 Conectando al sistema central...");

        // Conectar al VotingSite (proxy)
        try {
            votingProxy = VotingProxyPrx.checkedCast(
                    communicator.stringToProxy("VotingProxy:default -h localhost -p 9999")
            );

            if (votingProxy == null) {
                System.err.println("   ERROR: No se pudo conectar al sistema central de votación");
                System.err.println("   Verifique que VotingSite esté ejecutándose");
                return 1;
            }

            // Verificar conectividad
            String systemStatus = votingProxy.getSystemStatus();
            System.out.println("Conectado al sistema central");
            System.out.println("Estado del sistema: " + systemStatus);

        } catch (Exception e) {
            System.err.println("   ERROR: Fallo al conectar con sistema central: " + e.getMessage());
            System.err.println("   Verifique que VotingSite esté ejecutándose en puerto 9999");
            return 1;
        }

        // Iniciar interfaz de usuario
        return startVotingInterface();
    }

    private static int startVotingInterface() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("\n==============================================");
        System.out.println("BIENVENIDO AL SISTEMA DE VOTACIÓN");
        System.out.println("==============================================");

        showMainMenu();

        while (true) {
            System.out.print("\n➤ Seleccione una opción: ");
            String input = scanner.nextLine().trim();

            try {
                switch (input.toLowerCase()) {
                    case "1":
                    case "votar":
                        processVote(scanner);
                        break;

                    case "2":
                    case "estado":
                        showSystemStatus();
                        break;

                    case "3":
                    case "pendientes":
                        showPendingVotes();
                        break;

                    case "4":
                    case "ayuda":
                    case "?":
                        showMainMenu();
                        break;

                    case "5":
                    case "salir":
                    case "exit":
                        System.out.println("\nGracias por usar el sistema de votación");
                        System.out.println("Cerrando sesión de manera segura...");
                        return 0;

                    default:
                        System.out.println("Opción no válida. Digite '?' para ver el menú");
                        break;
                }
            } catch (Exception e) {
                System.err.println("Error procesando solicitud: " + e.getMessage());
            }
        }
    }

    private static void showMainMenu() {
        System.out.println("\nOPCIONES DISPONIBLES:");
        System.out.println("  1) Votar                    - Emitir su voto");
        System.out.println("  2) Estado del Sistema       - Ver estado actual");
        System.out.println("  3) Votos Pendientes         - Ver votos en cola");
        System.out.println("  4) Ayuda                    - Mostrar este menú");
        System.out.println("  5) Salir                    - Cerrar aplicación");
        System.out.println("─────────────────────────────────────────────────");
    }

    private static void processVote(Scanner scanner) {
        System.out.println("\nPROCESO DE VOTACIÓN");
        System.out.println("═══════════════════════════");

        // Solicitar cédula
        System.out.print("Ingrese su número de cédula: ");
        String citizenId = scanner.nextLine().trim();

        if (citizenId.isEmpty()) {
            System.out.println("La cédula no puede estar vacía");
            return;
        }

        // Validar formato básico de cédula
        if (!citizenId.matches("\\d{6,12}")) {
            System.out.println("Formato de cédula inválido. Use solo números (6-12 dígitos)");
            return;
        }

        // Mostrar candidatos disponibles
        showCandidates();

        // Solicitar voto
        System.out.print("Seleccione el número del candidato: ");
        String candidateInput = scanner.nextLine().trim();

        String candidateId = mapCandidateSelection(candidateInput);
        if (candidateId == null) {
            System.out.println("Selección de candidato inválida");
            return;
        }

        // Confirmar voto
        System.out.println("\nCONFIRMACIÓN DE VOTO");
        System.out.println("   Cédula: " + citizenId);
        System.out.println("   Candidato: " + getCandidateName(candidateId));
        System.out.print("¿Confirma su voto? (S/N): ");

        String confirmation = scanner.nextLine().trim().toLowerCase();
        if (!confirmation.equals("s") && !confirmation.equals("si") && !confirmation.equals("sí")) {
            System.out.println("Voto cancelado por el usuario");
            return;
        }

        // Enviar voto al sistema
        submitVoteToSystem(citizenId, candidateId);
    }

    private static void showCandidates() {
        System.out.println("\nCANDIDATOS DISPONIBLES:");
        System.out.println("─────────────────────────────");
        System.out.println("  1) Juan Pérez        - Partido Azul");
        System.out.println("  2) María García      - Partido Verde");
        System.out.println("  3) Carlos López      - Partido Rojo");
        System.out.println("  4) Ana Martínez      - Partido Amarillo");
        System.out.println("  5) VOTO EN BLANCO    - Sin preferencia");
        System.out.println("─────────────────────────────");
    }

    private static String mapCandidateSelection(String input) {
        switch (input) {
            case "1": return "candidate001";
            case "2": return "candidate002";
            case "3": return "candidate003";
            case "4": return "candidate004";
            case "5": return "blank";
            default: return null;
        }
    }

    private static String getCandidateName(String candidateId) {
        switch (candidateId) {
            case "candidate001": return "Juan Pérez (Partido Azul)";
            case "candidate002": return "María García (Partido Verde)";
            case "candidate003": return "Carlos López (Partido Rojo)";
            case "candidate004": return "Ana Martínez (Partido Amarillo)";
            case "blank": return "VOTO EN BLANCO";
            default: return "Candidato Desconocido";
        }
    }

    private static void submitVoteToSystem(String citizenId, String candidateId) {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        System.out.println("\nProcesando voto...");
        System.out.println("[" + timestamp + "] [" + machineId + "] Enviando voto al sistema central");

        try {
            long startTime = System.currentTimeMillis();
            String result = votingProxy.submitVote(citizenId, candidateId);
            long latency = System.currentTimeMillis() - startTime;

            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] VOTO REGISTRADO EXITOSAMENTE");
            System.out.println("[" + timestamp + "] ID de confirmación: " + result);
            System.out.println("[" + timestamp + "] Tiempo de procesamiento: " + latency + "ms");
            System.out.println("\n¡Su voto ha sido registrado correctamente!");
            System.out.println("Gracias por participar en el proceso democrático");

        } catch (VotingSystemUnavailableException e) {
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] SISTEMA TEMPORALMENTE NO DISPONIBLE");
            System.out.println("[" + timestamp + "] Razón: " + e.reason);
            System.out.println("[" + timestamp + "] Su voto ha sido guardado y será procesado automáticamente");
            System.out.println("\nSu voto está en cola y será registrado tan pronto el sistema esté disponible");

        } catch (InvalidVoteException e) {
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] VOTO INVÁLIDO");
            System.out.println("[" + timestamp + "] Razón: " + e.reason);
            System.out.println("\nNo se pudo procesar su voto. Verifique los datos e intente nuevamente");

        } catch (Exception e) {
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] ERROR DE COMUNICACIÓN");
            System.out.println("[" + timestamp + "] Error: " + e.getMessage());
            System.out.println("\nError de conectividad. Contacte al administrador del sistema");
        }
    }

    private static void showSystemStatus() {
        System.out.println("\nESTADO DEL SISTEMA");
        System.out.println("═══════════════════════");

        try {
            String status = votingProxy.getSystemStatus();
            System.out.println("🔧 Estado: " + status);

            int pendingVotes = votingProxy.getPendingVotesCount();
            System.out.println("Votos en cola: " + pendingVotes);

            if (pendingVotes > 0) {
                System.out.println("El sistema está procesando votos pendientes automáticamente");
            } else {
                System.out.println("Todos los votos han sido procesados exitosamente");
            }

        } catch (Exception e) {
            System.out.println("Error obteniendo estado del sistema: " + e.getMessage());
        }
    }

    private static void showPendingVotes() {
        System.out.println("\nVOTOS PENDIENTES");
        System.out.println("═══════════════════════");

        try {
            int pendingCount = votingProxy.getPendingVotesCount();

            if (pendingCount == 0) {
                System.out.println("No hay votos pendientes");
                System.out.println("Todos los votos han sido procesados exitosamente");
            } else {
                System.out.println("Votos en cola de procesamiento: " + pendingCount);
                System.out.println("El sistema está trabajando para procesar estos votos");
                System.out.println("Los votos serán procesados automáticamente cuando el sistema esté disponible");
            }

        } catch (Exception e) {
            System.out.println("Error consultando votos pendientes: " + e.getMessage());
        }
    }
}
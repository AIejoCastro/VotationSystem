//
// CentralServer - Servidor Central Único con acceso exclusivo a base de datos
//

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class CentralServer {
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static void main(String[] args) {
        System.out.println("████████████████████████████████████████████████████████████");
        System.out.println("█              SERVIDOR CENTRAL DE VOTACIÓN                 █");
        System.out.println("█          Acceso Exclusivo a Base de Datos                 █");
        System.out.println("████████████████████████████████████████████████████████████");

        int status = 0;
        java.util.List<String> extraArgs = new java.util.ArrayList<>();

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize(args, "config.centralServer", extraArgs)) {

            // Configurar propiedades básicas
            communicator.getProperties().setProperty("Ice.Default.Package", "com.zeroc.demos.IceGrid.central");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[CentralServer] Shutdown graceful iniciado...");

                // Shutdown de componentes críticos
                try {
                    CentralVoteManager.getInstance().shutdown();
                    CentralACKManager.getInstance().shutdown();
                    System.out.println("[CentralServer] Componentes terminados correctamente");
                } catch (Exception e) {
                    System.err.println("[CentralServer] Error en shutdown: " + e.getMessage());
                }

                communicator.destroy();
                System.out.println("[CentralServer] Servidor central terminado");
            }));

            if (!extraArgs.isEmpty()) {
                System.err.println("Argumentos adicionales no soportados");
                status = 1;
            } else {
                status = run(communicator);
            }
        } catch (Exception e) {
            System.err.println("Error crítico en CentralServer: " + e.getMessage());
            e.printStackTrace();
            status = 1;
        }

        System.exit(status);
    }

    private static int run(com.zeroc.Ice.Communicator communicator) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [CentralServer] Iniciando servidor central...");

        try {
            // Crear adaptador para el servidor central
            com.zeroc.Ice.ObjectAdapter adapter = communicator.createObjectAdapter("CentralVotation");

            // Obtener identidad del servidor central
            com.zeroc.Ice.Properties properties = communicator.getProperties();
            com.zeroc.Ice.Identity serverId = com.zeroc.Ice.Util.stringToIdentity(
                    properties.getProperty("Identity")
            );

            // Crear e instalar el servant principal
            String serverName = properties.getProperty("Ice.ProgramName");
            if (serverName == null || serverName.isEmpty()) {
                serverName = "CentralServer-1";
            }
            CentralVotationI centralServant = new CentralVotationI(serverName);
            adapter.add(centralServant, serverId);

            // Activar adaptador
            adapter.activate();

            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] [CentralServer] ✅ Servidor central activo");
            System.out.println("[" + timestamp + "] [CentralServer] 🗄️  Base de datos inicializada");
            System.out.println("[" + timestamp + "] [CentralServer] 🔧 ACK Manager optimizado activo");
            System.out.println("[" + timestamp + "] [CentralServer] 📊 Vote Manager con partitioning activo");
            System.out.println("[" + timestamp + "] [CentralServer] 🌐 Esperando conexiones de servidores departamentales...");

            // Mostrar estadísticas iniciales
            centralServant.printServerStatus();

            // Comando administrativo simple
            showAdminCommands();

            // Loop administrativo básico
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in)
            );

            String command;
            while (true) {
                try {
                    System.out.print("\n[CentralServer-Admin] >> ");
                    System.out.flush();
                    command = reader.readLine();

                    if (command == null || "exit".equals(command.toLowerCase().trim())) {
                        break;
                    }

                    processAdminCommand(command.trim(), centralServant);

                } catch (Exception e) {
                    System.err.println("Error procesando comando: " + e.getMessage());
                }
            }

            System.out.println("[CentralServer] Iniciando shutdown...");
            communicator.waitForShutdown();

        } catch (Exception e) {
            System.err.println("[CentralServer] Error en ejecución: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }

        return 0;
    }

    private static void showAdminCommands() {
        System.out.println("\n" + "═".repeat(60));
        System.out.println("COMANDOS ADMINISTRATIVOS DISPONIBLES:");
        System.out.println("  status    - Estado del servidor y estadísticas");
        System.out.println("  votes     - Resumen de votos procesados");
        System.out.println("  acks      - Estado del ACK Manager");
        System.out.println("  debug     - Información detallada de debug");
        System.out.println("  clear     - Limpiar estado (SOLO TESTING)");
        System.out.println("  help      - Mostrar este menú");
        System.out.println("  exit      - Cerrar servidor central");
        System.out.println("═".repeat(60));
    }

    private static void processAdminCommand(String command, CentralVotationI servant) {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        switch (command.toLowerCase()) {
            case "status":
                servant.printServerStatus();
                break;

            case "votes":
                servant.printVotesSummary();
                break;

            case "acks":
                servant.printACKStatus();
                break;

            case "debug":
                servant.printDetailedDebugInfo();
                break;

            case "clear":
                System.out.print("¿Está seguro de limpiar todo el estado? (yes/no): ");
                try {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(System.in)
                    );
                    String confirmation = reader.readLine();
                    if ("yes".equals(confirmation.toLowerCase())) {
                        servant.clearStateForTesting();
                        System.out.println("[" + timestamp + "] Estado limpiado completamente");
                    } else {
                        System.out.println("[" + timestamp + "] Operación cancelada");
                    }
                } catch (Exception e) {
                    System.err.println("Error en confirmación: " + e.getMessage());
                }
                break;

            case "help":
            case "?":
                showAdminCommands();
                break;

            case "":
                // Comando vacío, ignorar
                break;

            default:
                System.out.println("Comando desconocido: " + command);
                System.out.println("Use 'help' para ver comandos disponibles");
                break;
        }
    }
}
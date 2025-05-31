//
// VotingSite - Ahora actúa como middleware/proxy
//

import Demo.*;
import Proxy.*;

public class VotingSite {
    public static void main(String[] args) {
        int status = 0;
        java.util.List<String> extraArgs = new java.util.ArrayList<>();

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize(args, "config.votingSite", extraArgs)) {

            try {
                communicator.getProperties().load("reliableMessaging/src/main/resources/config.reliableMessaging");
                System.out.println("[VotingSite] Configuración ReliableMessaging cargada correctamente");
            } catch (Exception e) {
                System.out.println("[VotingSite] Usando configuración por defecto para ReliableMessaging");
                communicator.getProperties().setProperty("Ice.Default.Locator", "DemoIceGrid/Locator:default -h localhost -p 4061");
            }

            if (!extraArgs.isEmpty()) {
                System.err.println("too many arguments");
                status = 1;
            } else {
                status = run(communicator);
            }
        }

        System.exit(status);
    }

    private static int run(com.zeroc.Ice.Communicator communicator) {
        System.out.println("==============================================");
        System.out.println("🔗 VOTING SITE - SISTEMA MIDDLEWARE");
        System.out.println("==============================================");

        // Inicializar reliable messaging
        ReliableMessagingService messagingService = ReliableMessagingService.getInstance();
        messagingService.initialize(communicator);

        // Conectar a IceGrid
        com.zeroc.IceGrid.QueryPrx query =
                com.zeroc.IceGrid.QueryPrx.checkedCast(communicator.stringToProxy("DemoIceGrid/Query"));

        if (query == null) {
            System.err.println("[VotingSite] No se pudo conectar al IceGrid Query. ¿Está ejecutándose el registry?");
            return 1;
        }

        // Obtener proxy inicial a servidores de votación
        VotationPrx votationProxy = null;
        try {
            votationProxy = VotationPrx.checkedCast(communicator.stringToProxy("votation"));
        } catch (com.zeroc.Ice.NotRegisteredException ex) {
            System.out.println("[VotingSite] Buscando servidor Votation a través de IceGrid...");
            votationProxy = VotationPrx.checkedCast(query.findObjectByType("::Demo::Votation"));
        }

        if (votationProxy == null) {
            System.out.println("[VotingSite] ⚠️  No hay servidores Votation disponibles en este momento");
            System.out.println("[VotingSite] ⚠️  El sistema operará en modo degradado con reliable messaging");
        } else {
            System.out.println("[VotingSite] ✅ Conectado a servidores de votación");
        }

        // Crear adaptador para el proxy de VotingMachine
        com.zeroc.Ice.ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints(
                "VotingProxyAdapter", "default -p 9999"
        );

        // Crear e instalar el servant del proxy
        VotingProxyI proxyServant = new VotingProxyI(votationProxy, messagingService, query);
        adapter.add(proxyServant, com.zeroc.Ice.Util.stringToIdentity("VotingProxy"));

        adapter.activate();

        System.out.println("[VotingSite] 🚀 Servidor proxy iniciado en puerto 9999");
        System.out.println("[VotingSite] 📡 Esperando conexiones de VotingMachine...");
        System.out.println("[VotingSite] 🔧 Reliable Messaging activo para garantizar entrega");

        showAdminMenu();

        // Interfaz administrativa simplificada
        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        String line = null;

        do {
            try {
                System.out.print("\n[VotingSite-Admin] ==> ");
                System.out.flush();
                line = in.readLine();
                if (line == null) break;

                switch (line.toLowerCase()) {
                    case "status":
                        messagingService.printStatus();
                        break;

                    case "history":
                        messagingService.printACKHistory();
                        break;

                    case "pending":
                        System.out.println("Votos pendientes: " + messagingService.getPendingVotesCount());
                        break;

                    case "servers":
                        showAvailableServers(query);
                        break;

                    case "help":
                    case "?":
                        showAdminMenu();
                        break;

                    case "exit":
                    case "quit":
                        line = "exit";
                        break;

                    default:
                        System.out.println("Comando desconocido. Use 'help' para ver opciones");
                        break;
                }

            } catch (Exception ex) {
                System.out.println("Error: " + ex.getMessage());
            }
        } while (!"exit".equals(line));

        System.out.println("[VotingSite] Cerrando sistema...");
        messagingService.shutdown();
        return 0;
    }

    private static void showAdminMenu() {
        System.out.println("\n📋 COMANDOS ADMINISTRATIVOS:");
        System.out.println("  status    - Estado del reliable messaging");
        System.out.println("  history   - Historial de votos y ACKs");
        System.out.println("  pending   - Votos pendientes en cola");
        System.out.println("  servers   - Servidores disponibles en IceGrid");
        System.out.println("  help      - Mostrar este menú");
        System.out.println("  exit      - Cerrar sistema");
        System.out.println("─────────────────────────────────────────");
    }

    private static void showAvailableServers(com.zeroc.IceGrid.QueryPrx query) {
        try {
            System.out.println("\n🔍 Consultando servidores disponibles en IceGrid...");
            VotationPrx proxy = VotationPrx.checkedCast(query.findObjectByType("::Demo::Votation"));
            if (proxy != null) {
                System.out.println("✅ Servidor encontrado en IceGrid");
                try {
                    proxy.ice_ping();
                    System.out.println("✅ Estado: DISPONIBLE");
                } catch (Exception e) {
                    System.out.println("❌ Estado: NO RESPONDE");
                }
            } else {
                System.out.println("❌ No hay servidores Votation disponibles");
            }
        } catch (Exception e) {
            System.out.println("❌ Error consultando servidores: " + e.getMessage());
        }
    }
}
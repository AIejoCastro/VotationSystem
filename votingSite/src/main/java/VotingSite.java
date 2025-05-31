//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

import Demo.*;

public class VotingSite {
    public static void main(String[] args) {
        int status = 0;
        java.util.List<String> extraArgs = new java.util.ArrayList<>();

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize(args, "config.votingSite", extraArgs)) {

            // Cargar configuración del reliable messaging
            try {
                communicator.getProperties().load("reliableMessaging/src/main/resources/config.reliableMessaging");
                System.out.println("Configuración ReliableMessaging cargada correctamente");
            } catch (Exception e) {
                System.out.println("Usando configuración por defecto para ReliableMessaging");
                // Configurar locator manualmente si no se pudo cargar el archivo
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
        // Inicializar reliable messaging
        ReliableMessagingService messagingService = ReliableMessagingService.getInstance();
        messagingService.initialize(communicator);

        VotationPrx hello = null;
        com.zeroc.IceGrid.QueryPrx query =
                com.zeroc.IceGrid.QueryPrx.checkedCast(communicator.stringToProxy("DemoIceGrid/Query"));

        if (query == null) {
            System.err.println("No se pudo conectar al IceGrid Query. ¿Está ejecutándose el registry?");
            return 1;
        }

        try {
            // Intentar conectar directamente por nombre
            hello = VotationPrx.checkedCast(communicator.stringToProxy("votation"));
        } catch (com.zeroc.Ice.NotRegisteredException ex) {
            // Si falla, buscar a través de IceGrid
            System.out.println("Buscando servidor Votation a través de IceGrid...");
            hello = VotationPrx.checkedCast(query.findObjectByType("::Demo::Votation"));
        }

        if (hello == null) {
            System.err.println("No se encontró ningún servidor `::Demo::Votation` en IceGrid");
            System.err.println("Verifique que los servidores departamentales estén ejecutándose");
            return 1;
        }

        System.out.println("Conectado a servidor Votation disponible");
        menu();

        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        String line = null;

        do {
            try {
                System.out.print("==> ");
                System.out.flush();
                line = in.readLine();
                if (line == null) break;

                if (line.equals("t")) {
                    hello.sayHello();
                } else if (line.equals("s")) {
                    hello.shutdown();
                } else if (line.startsWith("v")) {
                    String[] parts = line.split(" ");
                    if (parts.length == 3) {
                        try {
                            hello.sendVote(parts[1], parts[2]);
                            System.out.println("Voto enviado correctamente.");
                        } catch (AlreadyVotedException e) {
                            System.out.println("Este ciudadano ya ha votado.");
                        } catch (com.zeroc.Ice.LocalException e) {
                            System.out.println("Servidor no disponible. Voto guardado localmente para reintento automático.");
                            System.out.println("IceGrid buscará otro servidor disponible automáticamente.");
                            messagingService.storeOfflineVote(parts[1], parts[2]);
                        }
                    } else {
                        System.out.println("Formato: v <citizenId> <candidateId>");
                    }
                } else if (line.equals("p")) {
                    System.out.println("Votos pendientes: " + messagingService.getPendingVotesCount());
                } else if (line.equals("status")) {
                    messagingService.printStatus();
                } else if (line.equals("servers")) {
                    // Mostrar servidores disponibles en IceGrid
                    showAvailableServers(query);
                } else if (line.equals("x")) {
                    // Salir
                } else if (line.equals("?")) {
                    menu();
                } else {
                    System.out.println("Comando desconocido: " + line);
                    menu();
                }

                // Actualizar proxy para obtener balanceo de carga de IceGrid
                try {
                    VotationPrx newProxy = VotationPrx.checkedCast(query.findObjectByType("::Demo::Votation"));
                    if (newProxy != null) {
                        hello = newProxy;
                    }
                } catch (Exception e) {
                    // Mantener el proxy actual si hay error
                }

            } catch (Exception ex) {
                System.out.println("Error: " + ex.getMessage());
            }
        } while (!line.equals("x"));

        messagingService.shutdown();
        return 0;
    }

    private static void showAvailableServers(com.zeroc.IceGrid.QueryPrx query) {
        try {
            System.out.println("Consultando servidores disponibles en IceGrid...");
            VotationPrx proxy = VotationPrx.checkedCast(query.findObjectByType("::Demo::Votation"));
            if (proxy != null) {
                System.out.println("Servidor encontrado en IceGrid");
                try {
                    proxy.ice_ping();
                    System.out.println("Estado: DISPONIBLE");
                } catch (Exception e) {
                    System.out.println("Estado: NO RESPONDE");
                }
            } else {
                System.out.println("No hay servidores Votation disponibles");
            }
        } catch (Exception e) {
            System.out.println("Error consultando servidores: " + e.getMessage());
        }
    }

    private static void menu() {
        System.out.println(
                "usage:\n" +
                        "t: send greeting\n" +
                        "v <citizenId> <candidateId>: send vote\n" +
                        "p: show pending votes count\n" +
                        "status: show reliable messaging status\n" +
                        "servers: show available servers in IceGrid\n" +
                        "s: shutdown server\n" +
                        "x: exit\n" +
                        "?: help\n"
        );
    }
}
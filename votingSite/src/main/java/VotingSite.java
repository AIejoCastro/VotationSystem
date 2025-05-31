//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

import Demo.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class VotingSite {
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static void main(String[] args) {
        int status = 0;
        java.util.List<String> extraArgs = new java.util.ArrayList<>();

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize(args, "config.votingSite", extraArgs)) {

            try {
                communicator.getProperties().load("reliableMessaging/src/main/resources/config.reliableMessaging");
                System.out.println("Configuración ReliableMessaging cargada correctamente");
            } catch (Exception e) {
                System.out.println("Usando configuración por defecto para ReliableMessaging");
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
            hello = VotationPrx.checkedCast(communicator.stringToProxy("votation"));
        } catch (com.zeroc.Ice.NotRegisteredException ex) {
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
                        sendVoteWithACK(hello, parts[1], parts[2], messagingService);
                    } else {
                        System.out.println("Formato: v <citizenId> <candidateId>");
                    }
                } else if (line.equals("p")) {
                    System.out.println("Votos pendientes: " + messagingService.getPendingVotesCount());
                } else if (line.equals("status")) {
                    messagingService.printStatus();
                } else if (line.equals("acks")) {
                    messagingService.printStatus(); // Incluye info de ACKs
                } else if (line.equals("history")) {
                    messagingService.printACKHistory();
                } else if (line.equals("x")) {
                    // Salir
                } else if (line.equals("?")) {
                    menu();
                } else {
                    System.out.println("Comando desconocido: " + line);
                    menu();
                }

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

    private static void sendVoteWithACK(VotationPrx votationProxy, String citizenId, String candidateId,
                                        ReliableMessagingService messagingService) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] Enviando voto: " + citizenId + " -> " + candidateId);

        long startTime = System.currentTimeMillis();
        String voteKey = citizenId + "|" + candidateId;

        try {
            String ackId = votationProxy.sendVote(citizenId, candidateId);
            long latency = System.currentTimeMillis() - startTime;

            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] ACK RECIBIDO: " + ackId + " (" + latency + "ms)");
            System.out.println("[" + timestamp + "] Voto confirmado exitosamente");

            messagingService.confirmVoteACK(voteKey, ackId, latency);

        } catch (AlreadyVotedException e) {
            long latency = System.currentTimeMillis() - startTime;
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] Ciudadano ya votó - ACK duplicado: " + e.ackId + " (" + latency + "ms)");

            messagingService.confirmVoteACK(voteKey, e.ackId, latency);

        } catch (com.zeroc.Ice.LocalException e) {
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] Servidor no disponible - SIN ACK");
            System.out.println("[" + timestamp + "] Voto guardado para reintento automático");

            String offlineVoteKey = messagingService.storeOfflineVoteWithACK(citizenId, candidateId);
            messagingService.timeoutVote(offlineVoteKey);
        }
    }

    private static void menu() {
        System.out.println(
                "usage:\n" +
                        "t: send greeting\n" +
                        "v <citizenId> <candidateId>: send vote\n" +
                        "p: show pending votes count\n" +
                        "status: show reliable messaging status\n" +
                        "acks: show ACK status\n" +
                        "history: show vote history with ACKs\n" +
                        "s: shutdown server\n" +
                        "x: exit\n" +
                        "?: help\n"
        );
    }
}
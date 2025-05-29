//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

import Demo.*;

public class User {
    public static void main(String[] args) {
        int status = 0;
        java.util.List<String> extraArgs = new java.util.ArrayList<>();

        try (com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize(args, "config.user", extraArgs)) {
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
        VotationPrx hello = null;
        com.zeroc.IceGrid.QueryPrx query =
                com.zeroc.IceGrid.QueryPrx.checkedCast(communicator.stringToProxy("DemoIceGrid/Query"));
        try {
            hello = VotationPrx.checkedCast(communicator.stringToProxy("votation"));
        } catch (com.zeroc.Ice.NotRegisteredException ex) {
            hello = VotationPrx.checkedCast(query.findObjectByType("::Demo::Votation"));
        }
        if (hello == null) {
            System.err.println("couldn't find a `::Demo::Votation` object");
            return 1;
        }

        OfflineVoteQueue offlineQueue = new OfflineVoteQueue();
        VoteRetryWorker retryWorker = new VoteRetryWorker(communicator);
        retryWorker.start();

        menu();

        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));

        String line = null;
        do {
            try {
                System.out.print("==> ");
                System.out.flush();
                line = in.readLine();
                if (line == null) {
                    break;
                }

                if (line.equals("t")) {
                    hello.sayHello();
                } else if (line.equals("s")) {
                    hello.shutdown();
                } else if (line.startsWith("v")) {
                    System.out.println("Formato: v <citizenId> <candidateId>");
                    String[] parts = line.split(" ");
                    if (parts.length == 3) {
                        try {
                            hello.sendVote(parts[1], parts[2]);
                            System.out.println("Voto enviado correctamente.");
                        } catch (AlreadyVotedException e) {
                            System.out.println("Este ciudadano ya ha votado.");
                        } catch (com.zeroc.Ice.LocalException e) {
                            System.out.println("Servidor no disponible. Voto guardado localmente.");
                            offlineQueue.enqueue(parts[1], parts[2]);
                        }
                    } else {
                        System.out.println("Formato inválido. Use: v <citizenId> <candidateId>");
                    }
                } else if (line.equals("x")) {
                    // Salir
                } else if (line.equals("?")) {
                    menu();
                } else {
                    System.out.println("Comando desconocido `" + line + "`");
                    menu();
                }

                // Actualiza referencia del proxy por si cambió de servidor
                hello = VotationPrx.checkedCast(query.findObjectByType("::Demo::Votation"));

            } catch (java.io.IOException ex) {
                ex.printStackTrace();
            } catch (com.zeroc.Ice.LocalException ex) {
                System.out.println("Error de red: " + ex.getMessage());
            }
        } while (!line.equals("x"));

        return 0;
    }

    private static void menu() {
        System.out.println(
                "usage:\n" +
                        "t: send greeting\n" +
                        "v <citizenId> <candidateId>: send vote\n" +
                        "s: shutdown server\n" +
                        "x: exit\n" +
                        "?: help\n"
        );
    }
}

//
// VotingMachine - Versión Simplificada CON notificaciones push
//

import Proxy.*;
import Central.*;
import CandidateNotification.*;
import VotingStation.*;
import com.zeroc.Ice.Current;

import java.util.*;

public class VotingMachine {
    private static VotingProxyPrx votingProxy;
    private static CentralVotationPrx centralProxy;
    private static String machineId;
    private static com.zeroc.Ice.Communicator communicator;
    private static VoteStationI voteStationServant;

    // Cache de candidatos
    private static volatile List<CandidateData> currentCandidates = new ArrayList<>();
    private static final Object candidatesLock = new Object();

    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════");
        System.out.println("🗳️  ESTACIÓN DE VOTACIÓN");
        System.out.println("═══════════════════════════════════");

        try {
            communicator = com.zeroc.Ice.Util.initialize(args);
            communicator.getProperties().setProperty("Ice.Default.Locator", "DemoIceGrid-grpmcc/Locator:default -h 10.147.17.101 -p 4071");

            if (initializeSystem()) {
                startVotingInterface();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            cleanup();
            if (communicator != null) {
                communicator.destroy();
            }
        }
    }

    private static boolean initializeSystem() {
        machineId = "VS-" + System.currentTimeMillis() % 10000;

        try {
            // Conectar al sistema de votación
            votingProxy = VotingProxyPrx.checkedCast(
                    communicator.stringToProxy("VotingProxy:default -h 10.147.17.102 -p 9911"));

            if (votingProxy == null) {
                System.err.println("❌ No se pudo conectar al sistema");
                return false;
            }

            // Conectar al servidor central para candidatos Y notificaciones
            connectToCentralServer();

            // Inicializar servicio ICE
            initializeICEService();

            System.out.println("✅ Sistema inicializado - ID: " + machineId);
            return true;

        } catch (Exception e) {
            System.err.println("❌ Error conectando: " + e.getMessage());
            return false;
        }
    }

    private static void connectToCentralServer() {
        try {
            System.out.println("📡 Conectando al servidor central...");

            centralProxy = CentralVotationPrx.checkedCast(
                    communicator.stringToProxy("CentralVotation:default -h 10.147.17.101 -p 8899"));

            if (centralProxy != null) {
                // Crear adaptador para callbacks
                com.zeroc.Ice.ObjectAdapter callbackAdapter = communicator.createObjectAdapterWithEndpoints(
                        "VotingMachineCallback", "tcp");

                // Crear servant para callbacks
                VotingMachineCallbackI callbackServant = new VotingMachineCallbackI();
                com.zeroc.Ice.Identity callbackId = com.zeroc.Ice.Util.stringToIdentity("callback-" + machineId);
                callbackAdapter.add(callbackServant, callbackId);
                callbackAdapter.activate();

                // Crear proxy del callback
                VotingMachineCallbackPrx callbackProxy = VotingMachineCallbackPrx.checkedCast(
                        callbackAdapter.createProxy(callbackId));

                // Registrarse para notificaciones
                centralProxy.registerVotingMachine(machineId, callbackProxy);
                System.out.println("✅ Registrado para notificaciones automáticas");

                // Obtener candidatos actuales
                loadCandidates();
            } else {
                System.out.println("⚠️  CentralServer no disponible");
                useDefaultCandidates();
            }

        } catch (Exception e) {
            System.out.println("⚠️  Error con CentralServer: " + e.getMessage());
            useDefaultCandidates();
        }
    }

    // Callback servant para recibir notificaciones
    public static class VotingMachineCallbackI implements VotingMachineCallback {
        @Override
        public void onCandidatesUpdated(CandidateUpdateNotification notification, com.zeroc.Ice.Current current) {
            System.out.println("\n🔔 CANDIDATOS ACTUALIZADOS");
            System.out.println("Nuevos candidatos: " + notification.totalCandidates);

            synchronized (candidatesLock) {
                currentCandidates = Arrays.asList(notification.candidates);
            }

            // Actualizar también el servicio ICE
            if (voteStationServant != null) {
                voteStationServant.updateCandidates(Arrays.asList(notification.candidates), notification.updateTimestamp);
            }

            System.out.println("✅ Candidatos actualizados automáticamente");
        }
    }

    private static void initializeICEService() {
        try {
            com.zeroc.Ice.ObjectAdapter iceAdapter = communicator.createObjectAdapterWithEndpoints(
                    "VoteStationAdapter", "tcp -p " + (10000 + (int)(System.currentTimeMillis() % 1000)));

            voteStationServant = new VoteStationI(votingProxy, centralProxy, machineId);
            com.zeroc.Ice.Identity iceIdentity = com.zeroc.Ice.Util.stringToIdentity("VoteStation-" + machineId);
            iceAdapter.add(voteStationServant, iceIdentity);
            iceAdapter.activate();

            String endpoint = iceAdapter.getEndpoints()[0].toString();
            System.out.println("🔧 Servicio ICE: VoteStation-" + machineId + " @ " + endpoint);
        } catch (Exception e) {
            System.err.println("⚠️  Servicio ICE no disponible: " + e.getMessage());
        }
    }

    private static void startVotingInterface() {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("\n" + "─".repeat(40));
            System.out.println("1) Votar");
            System.out.println("2) Ver candidatos");
            System.out.println("3) Salir");
            System.out.print("Opción: ");

            String option = scanner.nextLine().trim();

            switch (option) {
                case "1":
                    processVote(scanner);
                    break;
                case "2":
                    showCandidates();
                    break;
                case "3":
                    System.out.println("👋 Cerrando...");
                    return;
                default:
                    System.out.println("❌ Opción inválida");
            }
        }
    }

    private static void processVote(Scanner scanner) {
        System.out.println("\n🗳️  VOTACIÓN");
        System.out.println("─".repeat(30));

        // Capturar documento
        System.out.print("Documento: ");
        String document = scanner.nextLine().trim();

        if (document.isEmpty()) {
            System.out.println("❌ Documento no puede estar vacío");
            return;
        }

        // Mostrar candidatos
        showCandidatesForVoting();

        // Capturar selección
        int maxCandidates;
        synchronized (candidatesLock) {
            maxCandidates = currentCandidates.isEmpty() ? 5 : currentCandidates.size();
        }
        System.out.print("Candidato (1-" + maxCandidates + "): ");

        String candidateInput = scanner.nextLine().trim();

        int candidateId;
        try {
            candidateId = Integer.parseInt(candidateInput);
            if (candidateId < 1 || candidateId > maxCandidates) {
                System.out.println("❌ Debe ser 1-" + maxCandidates);
                return;
            }
        } catch (NumberFormatException e) {
            System.out.println("❌ Debe ser un número");
            return;
        }

        // Procesar voto
        int result = voteStationServant.vote(document, candidateId, new MockCurrent());
        showResult(result);
    }

    private static void showResult(int result) {
        System.out.println("\n" + "═".repeat(30));
        switch (result) {
            case 0:
                System.out.println("✅ VOTO EXITOSO (Código: 0)");
                break;
            case 2:
                System.out.println("⚠️  YA VOTÓ (Código: 2)");
                break;
            case 3:
                System.out.println("❌ NO REGISTRADO (Código: 3)");
                break;
            default:
                System.out.println("❌ ERROR (Código: " + result + ")");
        }
        System.out.println("═".repeat(30));
    }

    private static void showCandidatesForVoting() {
        System.out.println("\nCandidatos:");
        synchronized (candidatesLock) {
            if (currentCandidates.isEmpty()) {
                showDefaultCandidates();
                return;
            }

            int pos = 1;
            for (CandidateData c : currentCandidates) {
                if (!"blank".equals(c.candidateId)) {
                    System.out.println("  " + pos + ") " + c.fullName + " - " + c.partyName);
                    pos++;
                }
            }
            System.out.println("  " + pos + ") VOTO EN BLANCO");
        }
    }

    private static void showCandidates() {
        System.out.println("\n📋 CANDIDATOS DISPONIBLES");
        showCandidatesForVoting();
    }

    private static void showDefaultCandidates() {
        System.out.println("  1) Juan Pérez - Partido Azul");
        System.out.println("  2) María García - Partido Verde");
        System.out.println("  3) Carlos López - Partido Rojo");
        System.out.println("  4) Ana Martínez - Partido Amarillo");
        System.out.println("  5) VOTO EN BLANCO");
    }

    private static void loadCandidates() {
        try {
            CandidateListResponse response = centralProxy.getCurrentCandidates();
            synchronized (candidatesLock) {
                currentCandidates = Arrays.asList(response.candidates);
            }
            System.out.println("📋 Candidatos cargados: " + currentCandidates.size());
        } catch (Exception e) {
            System.out.println("⚠️  Usando candidatos por defecto");
            useDefaultCandidates();
        }
    }

    private static void useDefaultCandidates() {
        synchronized (candidatesLock) {
            currentCandidates.clear();
        }
    }

    // Limpieza al cerrar
    private static void cleanup() {
        try {
            if (centralProxy != null) {
                centralProxy.unregisterVotingMachine(machineId);
                System.out.println("✅ Desregistrado del CentralServer");
            }
        } catch (Exception e) {
            // Ignorar errores de limpieza
        }
    }

    // Clase mock para Current
    private static class MockCurrent extends Current {
        public com.zeroc.Ice.ObjectAdapter adapter = null;
        public com.zeroc.Ice.Connection con = null;
        public com.zeroc.Ice.Identity id = new com.zeroc.Ice.Identity();
        public String facet = "";
        public String operation = "vote";
        public com.zeroc.Ice.OperationMode mode = com.zeroc.Ice.OperationMode.Normal;
        public java.util.Map<String, String> ctx = new java.util.HashMap<>();
        public int requestId = 1;
        public com.zeroc.Ice.EncodingVersion encoding = com.zeroc.Ice.Util.currentEncoding();
    }
}
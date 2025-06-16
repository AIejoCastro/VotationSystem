//
// VotingMachine - Interface CLI + Servicio ICE VoteStation
// Cumple especificación: CLI básica + interfaz ICE para automatización
//

import Proxy.*;
import Central.*;
import CandidateNotification.*;
import VotingStation.*;
import com.zeroc.Ice.Current;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class VotingMachine {
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static VotingProxyPrx votingProxy;
    private static CentralVotationPrx centralProxy;
    private static String machineId;
    private static com.zeroc.Ice.Communicator communicator;
    private static VoteStationI voteStationServant;

    // Cache local de candidatos
    private static volatile List<CandidateData> currentCandidates = new ArrayList<>();
    private static volatile long lastUpdateTimestamp = 0;
    private static final Object candidatesLock = new Object();

    public static void main(String[] args) {
        System.out.println("══════════════════════════════════════════════════════════");
        System.out.println("🗳️  ESTACIÓN DE VOTACIÓN ELECTRÓNICA - v3.0");
        System.out.println("   Interfaz CLI + Servicio ICE para Automatización");
        System.out.println("══════════════════════════════════════════════════════════");

        int status = 0;
        java.util.List<String> extraArgs = new java.util.ArrayList<>();

        try {
            communicator = com.zeroc.Ice.Util.initialize(args, extraArgs);
            communicator.getProperties().setProperty("Ice.Default.Locator", "DemoIceGrid/Locator:default -h localhost -p 4061");

            if (!extraArgs.isEmpty()) {
                System.err.println("Argumentos adicionales no soportados");
                status = 1;
            } else {
                status = runVoteStation();
            }
        } catch (Exception e) {
            System.err.println("Error crítico del sistema: " + e.getMessage());
            e.printStackTrace();
            status = 1;
        } finally {
            if (communicator != null) {
                communicator.destroy();
            }
        }

        System.exit(status);
    }

    private static int runVoteStation() {
        // Generar ID único para esta estación
        machineId = "VS-" + System.currentTimeMillis() % 10000;

        System.out.println("🆔 Estación de Votación ID: " + machineId);
        System.out.println("🔗 Conectando al sistema de votación...");

        // Conectar al VotingSite (proxy principal)
        if (!connectToVotingSystem()) {
            return 1;
        }

        // Conectar al CentralServer para notificaciones
        connectToCentralServer();

        // Inicializar servicio ICE
        if (!initializeICEService()) {
            return 1;
        }

        // Iniciar interfaz CLI
        return startCLIInterface();
    }

    /**
     * Conectar al sistema de votación principal
     */
    private static boolean connectToVotingSystem() {
        try {
            votingProxy = VotingProxyPrx.checkedCast(
                    communicator.stringToProxy("VotingProxy:default -h localhost -p 9999")
            );

            if (votingProxy == null) {
                System.err.println("❌ ERROR: No se pudo conectar al sistema de votación");
                System.err.println("   Verifique que VotingSite esté ejecutándose en puerto 9999");
                return false;
            }

            // Verificar conectividad
            String systemStatus = votingProxy.getSystemStatus();
            System.out.println("✅ Conectado al sistema de votación");
            System.out.println("📊 Estado del sistema: " + systemStatus);

            return true;

        } catch (Exception e) {
            System.err.println("❌ ERROR: Fallo al conectar con sistema de votación: " + e.getMessage());
            return false;
        }
    }

    /**
     * Conectar al CentralServer para notificaciones
     */
    private static void connectToCentralServer() {
        try {
            System.out.println("📡 Conectando al servidor central para notificaciones...");

            centralProxy = CentralVotationPrx.checkedCast(
                    communicator.stringToProxy("CentralVotation:default -h localhost -p 8888"));

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
                System.out.println("✅ Registrado con CentralServer para notificaciones automáticas");

                // Obtener candidatos actuales
                fetchCurrentCandidates();

            } else {
                System.out.println("⚠️  No se pudo conectar al CentralServer - funcionando sin notificaciones");
                initializeDefaultCandidates();
            }

        } catch (Exception e) {
            System.err.println("⚠️  Error conectando al CentralServer: " + e.getMessage());
            System.err.println("   Continuando sin notificaciones automáticas");
            initializeDefaultCandidates();
        }
    }

    /**
     * Inicializar servicio ICE VoteStation
     */
    private static boolean initializeICEService() {
        try {
            System.out.println("🔧 Inicializando servicio ICE VoteStation...");

            // Crear adaptador para el servicio ICE
            com.zeroc.Ice.ObjectAdapter iceAdapter = communicator.createObjectAdapterWithEndpoints(
                    "VoteStationAdapter", "tcp -p " + (10000 + (int)(System.currentTimeMillis() % 1000)));

            // Crear servant VoteStation
            voteStationServant = new VoteStationI(votingProxy, centralProxy, machineId);

            // Registrar servant
            com.zeroc.Ice.Identity iceIdentity = com.zeroc.Ice.Util.stringToIdentity("VoteStation-" + machineId);
            iceAdapter.add(voteStationServant, iceIdentity);

            // Activar adaptador
            iceAdapter.activate();

            String endpoint = iceAdapter.getEndpoints()[0].toString();
            System.out.println("✅ Servicio ICE VoteStation disponible en: " + endpoint);
            System.out.println("🔍 Identidad ICE: VoteStation-" + machineId);
            System.out.println("📝 Para pruebas automatizadas, usar:");
            System.out.println("   VotingStation.VoteStationPrx:identity VoteStation-" + machineId + " @ " + endpoint);

            return true;

        } catch (Exception e) {
            System.err.println("❌ ERROR: No se pudo inicializar servicio ICE: " + e.getMessage());
            return false;
        }
    }

    /**
     * Interfaz CLI básica según especificación
     */
    private static int startCLIInterface() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("\n══════════════════════════════════════════════════════════");
        System.out.println("ESTACIÓN DE VOTACIÓN - INTERFAZ CLI");
        System.out.println("══════════════════════════════════════════════════════════");

        showWelcomeMessage();

        while (true) {
            System.out.println("\n" + "─".repeat(60));
            System.out.println("OPCIONES DISPONIBLES:");
            System.out.println("  1) Votar                    - Emitir voto");
            System.out.println("  2) Ver Candidatos           - Lista de candidatos");
            System.out.println("  3) Estado del Sistema       - Información del sistema");
            System.out.println("  4) Ayuda                    - Mostrar información");
            System.out.println("  5) Salir                    - Cerrar estación");
            System.out.println("─".repeat(60));

            System.out.print("➤ Seleccione una opción (1-5): ");
            String input = scanner.nextLine().trim();

            try {
                switch (input) {
                    case "1":
                        processVoteCLI(scanner);
                        break;

                    case "2":
                        showCandidatesCLI();
                        break;

                    case "3":
                        showSystemStatusCLI();
                        break;

                    case "4":
                        showHelpCLI();
                        break;

                    case "5":
                        System.out.println("\n👋 Cerrando estación de votación...");
                        cleanup();
                        return 0;

                    default:
                        System.out.println("❌ Opción inválida. Seleccione un número del 1 al 5.");
                        break;
                }
            } catch (Exception e) {
                System.err.println("❌ Error procesando solicitud: " + e.getMessage());
            }
        }
    }

    /**
     * Procesar voto a través de CLI - ESPECIFICACIÓN CUMPLIDA
     */
    private static void processVoteCLI(Scanner scanner) {
        System.out.println("\n🗳️  PROCESO DE VOTACIÓN");
        System.out.println("═".repeat(50));

        // REQUERIMIENTO: Capturar documento de identidad
        System.out.print("📋 Ingrese su documento de identidad: ");
        String document = scanner.nextLine().trim();

        if (document.isEmpty()) {
            System.out.println("❌ El documento de identidad no puede estar vacío");
            return;
        }

        // Validación básica del documento
        if (!document.matches("\\d{6,12}")) {
            System.out.println("❌ Formato de documento inválido. Use solo números (6-12 dígitos)");
            return;
        }

        // REQUERIMIENTO: Mostrar candidatos y capturar selección
        showCandidatesForVoting();

        System.out.print("\n🎯 Seleccione el número del candidato (1-5): ");
        String candidateInput = scanner.nextLine().trim();

        int candidateId;
        try {
            candidateId = Integer.parseInt(candidateInput);
            if (candidateId < 1 || candidateId > 5) {
                System.out.println("❌ Selección inválida. Debe ser un número entre 1 y 5");
                return;
            }
        } catch (NumberFormatException e) {
            System.out.println("❌ Debe ingresar un número válido");
            return;
        }

        // Confirmar voto
        String candidateName = getCandidateNameByPosition(candidateId);
        System.out.println("\n✅ CONFIRMACIÓN DE VOTO");
        System.out.println("   📋 Documento: " + document);
        System.out.println("   🎯 Candidato: " + candidateName);
        System.out.print("🤔 ¿Confirma su voto? (S/N): ");

        String confirmation = scanner.nextLine().trim().toLowerCase();
        if (!confirmation.equals("s") && !confirmation.equals("si") && !confirmation.equals("sí")) {
            System.out.println("❌ Voto cancelado por el usuario");
            return;
        }

        // PROCESAR VOTO usando el servicio ICE interno
        int result = voteStationServant.vote(document, candidateId, new MockCurrent());

        // Mostrar resultado según especificación
        handleVoteResult(result, document, candidateName);
    }

    /**
     * Manejar resultado del voto según códigos de la especificación
     */
    private static void handleVoteResult(int result, String document, String candidateName) {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        switch (result) {
            case 0:
                System.out.println("\n🎉 VOTO REGISTRADO EXITOSAMENTE");
                System.out.println("═".repeat(50));
                System.out.println("[" + timestamp + "] ✅ Su voto ha sido procesado correctamente");
                System.out.println("🙏 ¡Gracias por participar en el proceso democrático!");
                break;

            case 2:
                System.out.println("\n⚠️  VOTO DUPLICADO DETECTADO");
                System.out.println("═".repeat(50));
                System.out.println("[" + timestamp + "] El ciudadano " + document + " ya emitió su voto");
                System.out.println("📊 Cada ciudadano puede votar una sola vez");
                break;

            default:
                System.out.println("\n❌ ERROR PROCESANDO VOTO");
                System.out.println("═".repeat(50));
                System.out.println("[" + timestamp + "] Código de error: " + result);
                System.out.println("🔧 Contacte al administrador del sistema");
                break;
        }
    }

    /**
     * Mostrar candidatos para proceso de votación
     */
    private static void showCandidatesForVoting() {
        System.out.println("\n📋 CANDIDATOS DISPONIBLES");
        System.out.println("═".repeat(50));

        synchronized (candidatesLock) {
            if (currentCandidates.isEmpty()) {
                showDefaultCandidatesForVoting();
                return;
            }

            int position = 1;
            for (CandidateData candidate : currentCandidates) {
                if (!"blank".equals(candidate.candidateId)) {
                    System.out.printf("  %d) %-25s - %s%n",
                            position,
                            candidate.fullName,
                            candidate.partyName);
                    position++;
                }
            }

            System.out.printf("  %d) VOTO EN BLANCO        - Sin preferencia%n", position);
        }

        System.out.println("─".repeat(50));
    }

    /**
     * Candidatos por defecto para votación
     */
    private static void showDefaultCandidatesForVoting() {
        System.out.println("  1) Juan Pérez            - Partido Azul");
        System.out.println("  2) María García          - Partido Verde");
        System.out.println("  3) Carlos López          - Partido Rojo");
        System.out.println("  4) Ana Martínez          - Partido Amarillo");
        System.out.println("  5) VOTO EN BLANCO        - Sin preferencia");
    }

    /**
     * Obtener nombre de candidato por posición
     */
    private static String getCandidateNameByPosition(int position) {
        synchronized (candidatesLock) {
            if (currentCandidates.isEmpty()) {
                return getDefaultCandidateName(position);
            }

            int currentPos = 1;
            for (CandidateData candidate : currentCandidates) {
                if (!"blank".equals(candidate.candidateId)) {
                    if (currentPos == position) {
                        return candidate.fullName + " (" + candidate.partyName + ")";
                    }
                    currentPos++;
                }
            }

            // Si llegamos aquí, es voto en blanco
            if (position == currentPos) {
                return "VOTO EN BLANCO";
            }

            return "Candidato " + position; // Fallback
        }
    }

    /**
     * Nombres de candidatos por defecto
     */
    private static String getDefaultCandidateName(int position) {
        switch (position) {
            case 1: return "Juan Pérez (Partido Azul)";
            case 2: return "María García (Partido Verde)";
            case 3: return "Carlos López (Partido Rojo)";
            case 4: return "Ana Martínez (Partido Amarillo)";
            case 5: return "VOTO EN BLANCO";
            default: return "Candidato " + position;
        }
    }

    /**
     * Mostrar candidatos en CLI
     */
    private static void showCandidatesCLI() {
        System.out.println("\n📋 LISTA COMPLETA DE CANDIDATOS");
        System.out.println("═".repeat(60));

        synchronized (candidatesLock) {
            if (currentCandidates.isEmpty()) {
                showDefaultCandidatesCLI();
                return;
            }

            System.out.println("🕒 Última actualización: " + new Date(lastUpdateTimestamp));
            System.out.println("─".repeat(60));

            int position = 1;
            for (CandidateData candidate : currentCandidates) {
                if (!"blank".equals(candidate.candidateId)) {
                    System.out.printf("  %s %d) %-25s%n", candidate.photo, position, candidate.fullName);
                    System.out.printf("     🏛️  Partido: %s%n", candidate.partyName);

                    if (!candidate.biography.isEmpty() && !"Sin biografía disponible".equals(candidate.biography)) {
                        System.out.printf("     💭 %s%n", candidate.biography);
                    }

                    System.out.println();
                    position++;
                }
            }

            System.out.printf("  📊 %d) VOTO EN BLANCO%n", position);
            System.out.println("     🗳️  Opción para ciudadanos sin preferencia específica");
        }

        System.out.println("═".repeat(60));
    }

    /**
     * Candidatos por defecto para CLI
     */
    private static void showDefaultCandidatesCLI() {
        System.out.println("  👨‍💼 1) Juan Pérez");
        System.out.println("     🏛️  Partido: Partido Azul");
        System.out.println("     💭 Candidato con experiencia en administración pública");
        System.out.println();

        System.out.println("  👩‍💼 2) María García");
        System.out.println("     🏛️  Partido: Partido Verde");
        System.out.println("     💭 Activista ambiental y ex-alcaldesa");
        System.out.println();

        System.out.println("  👨‍🏫 3) Carlos López");
        System.out.println("     🏛️  Partido: Partido Rojo");
        System.out.println("     💭 Profesor universitario y líder sindical");
        System.out.println();

        System.out.println("  👩‍⚖️ 4) Ana Martínez");
        System.out.println("     🏛️  Partido: Partido Amarillo");
        System.out.println("     💭 Abogada constitucionalista");
        System.out.println();

        System.out.println("  📊 5) VOTO EN BLANCO");
        System.out.println("     🗳️  Opción para ciudadanos sin preferencia específica");
    }

    /**
     * Mostrar estado del sistema en CLI
     */
    private static void showSystemStatusCLI() {
        System.out.println("\n🔧 ESTADO DEL SISTEMA");
        System.out.println("═".repeat(50));

        try {
            // Estado del sistema de votación
            String systemStatus = votingProxy.getSystemStatus();
            System.out.println("🔧 Sistema de votación: " + systemStatus);

            int pendingVotes = votingProxy.getPendingVotesCount();
            System.out.println("📊 Votos en cola: " + pendingVotes);

            // Estado de la estación
            System.out.println("🆔 Estación ID: " + machineId);

            // Estado de notificaciones
            synchronized (candidatesLock) {
                if (lastUpdateTimestamp > 0) {
                    System.out.println("📡 Notificaciones: ✅ ACTIVAS");
                    System.out.println("🕒 Última actualización: " + new Date(lastUpdateTimestamp));
                    System.out.println("📋 Candidatos cargados: " + currentCandidates.size());
                } else {
                    System.out.println("📡 Notificaciones: ⚠️  DESCONECTADAS");
                    System.out.println("📋 Usando candidatos por defecto");
                }
            }

            // Estado del servicio ICE
            if (voteStationServant != null) {
                System.out.println("🔧 Servicio ICE: ✅ ACTIVO");
                System.out.println("📝 Interfaz: VoteStation-" + machineId);
            } else {
                System.out.println("🔧 Servicio ICE: ❌ INACTIVO");
            }

            if (pendingVotes > 0) {
                System.out.println("\n⚡ Procesando votos pendientes automáticamente...");
            } else {
                System.out.println("\n✅ Sistema operando normalmente");
            }

        } catch (Exception e) {
            System.out.println("❌ Error consultando estado: " + e.getMessage());
        }

        System.out.println("═".repeat(50));
    }

    /**
     * Mostrar ayuda en CLI
     */
    private static void showHelpCLI() {
        System.out.println("\n📖 AYUDA - ESTACIÓN DE VOTACIÓN");
        System.out.println("═".repeat(60));
        System.out.println();
        System.out.println("📋 CÓMO VOTAR:");
        System.out.println("   1. Seleccione la opción 'Votar' del menú principal");
        System.out.println("   2. Ingrese su documento de identidad (solo números)");
        System.out.println("   3. Seleccione el candidato de su preferencia (1-5)");
        System.out.println("   4. Confirme su selección");
        System.out.println();
        System.out.println("🔧 SERVICIOS DISPONIBLES:");
        System.out.println("   • Interfaz CLI para votación manual");
        System.out.println("   • Servicio ICE para automatización de pruebas");
        System.out.println("   • Notificaciones automáticas de candidatos");
        System.out.println("   • Recuperación automática de fallos");
        System.out.println();
        System.out.println("📡 CÓDIGOS DE RESULTADO (Servicio ICE):");
        System.out.println("   • 0: Voto exitoso");
        System.out.println("   • 2: Ciudadano ya votó (duplicado)");
        System.out.println("   • Otros: Errores del sistema");
        System.out.println();
        System.out.println("🆔 IDENTIFICACIÓN:");
        System.out.println("   • Estación: " + machineId);
        System.out.println("   • Servicio ICE: VoteStation-" + machineId);
        System.out.println();
        System.out.println("❓ Para soporte técnico, contacte al administrador");
        System.out.println("═".repeat(60));
    }

    /**
     * Mostrar mensaje de bienvenida
     */
    private static void showWelcomeMessage() {
        System.out.println();
        System.out.println("🗳️  Bienvenido al Sistema de Votación Electrónica");
        System.out.println();
        System.out.println("📋 INFORMACIÓN IMPORTANTE:");
        System.out.println("   • Cada ciudadano puede votar UNA SOLA VEZ");
        System.out.println("   • Su voto es secreto y seguro");
        System.out.println("   • El sistema detecta automáticamente votos duplicados");
        System.out.println("   • En caso de fallos, su voto se guarda automáticamente");
        System.out.println();
        System.out.println("🔧 FUNCIONALIDADES:");
        System.out.println("   • Interfaz CLI para votación interactiva");
        System.out.println("   • Servicio ICE para pruebas automatizadas");
        System.out.println("   • Actualizaciones automáticas de candidatos");
        System.out.println();
    }

    // ============================================================================
    // CALLBACKS Y NOTIFICACIONES
    // ============================================================================

    /**
     * Callback servant para recibir notificaciones de candidatos
     */
    public static class VotingMachineCallbackI implements VotingMachineCallback {

        @Override
        public void onCandidatesUpdated(CandidateUpdateNotification notification, com.zeroc.Ice.Current current) {
            String timestamp = LocalDateTime.now().format(timeFormatter);

            System.out.println("\n[" + timestamp + "] 🔔 ACTUALIZACIÓN DE CANDIDATOS RECIBIDA");
            System.out.println("═".repeat(70));
            System.out.println("Candidatos actualizados: " + notification.totalCandidates);
            System.out.println("Timestamp: " + new Date(notification.updateTimestamp));

            synchronized (candidatesLock) {
                currentCandidates = Arrays.asList(notification.candidates);
                lastUpdateTimestamp = notification.updateTimestamp;
            }

            // Actualizar también el servicio ICE
            if (voteStationServant != null) {
                voteStationServant.updateCandidates(Arrays.asList(notification.candidates), notification.updateTimestamp);
            }

            System.out.println("✅ Candidatos actualizados en estación " + machineId);
            System.out.println("═".repeat(70));
        }
    }

    // ============================================================================
    // MÉTODOS DE UTILIDAD
    // ============================================================================

    /**
     * Obtener candidatos del CentralServer
     */
    private static void fetchCurrentCandidates() {
        try {
            if (centralProxy != null) {
                CandidateListResponse response = centralProxy.getCurrentCandidates();

                synchronized (candidatesLock) {
                    currentCandidates = Arrays.asList(response.candidates);
                    lastUpdateTimestamp = response.updateTimestamp;
                }

                System.out.println("📋 Candidatos obtenidos: " + currentCandidates.size());
            }
        } catch (Exception e) {
            System.err.println("⚠️  Error obteniendo candidatos: " + e.getMessage());
            initializeDefaultCandidates();
        }
    }

    /**
     * Inicializar candidatos por defecto
     */
    private static void initializeDefaultCandidates() {
        // Los candidatos por defecto se manejan dinámicamente en los métodos de visualización
        synchronized (candidatesLock) {
            currentCandidates.clear();
            lastUpdateTimestamp = 0;
        }
        System.out.println("📋 Usando candidatos por defecto");
    }

    /**
     * Limpieza al cerrar
     */
    private static void cleanup() {
        try {
            if (centralProxy != null) {
                centralProxy.unregisterVotingMachine(machineId);
                System.out.println("✅ Desregistrado del CentralServer");
            }
        } catch (Exception e) {
            // Ignorar errores de limpieza
        }

        System.out.println("🔒 Estación cerrada de manera segura");
    }

    /**
     * Mock Current para uso interno
     */
    private static class MockCurrent extends Current {
        public com.zeroc.Ice.ObjectAdapter adapter = null;
        public com.zeroc.Ice.Connection con = null;
        public com.zeroc.Ice.Identity id = new com.zeroc.Ice.Identity();
        public String facet = "";
        public String operation = "vote";
        public com.zeroc.Ice.OperationMode mode = com.zeroc.Ice.OperationMode.Normal;
        public java.util.Map<String, String> ctx = new java.util.HashMap<String, String>();
        public int requestId = 1;
        public com.zeroc.Ice.EncodingVersion encoding = com.zeroc.Ice.Util.currentEncoding();
    }
}
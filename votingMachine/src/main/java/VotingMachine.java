//
// VotingMachineEnhanced - Interface principal para votantes con notificaciones automáticas
//

import Proxy.*;
import Central.*;
import CandidateNotification.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class VotingMachine {
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static VotingProxyPrx votingProxy;
    private static CentralVotationPrx centralProxy;
    private static String machineId;
    private static volatile boolean candidatesNeedRefresh = false;
    private static com.zeroc.Ice.Communicator communicator;

    // Cache local de candidatos
    private static volatile List<CandidateData> currentCandidates = new ArrayList<>();
    private static volatile long lastUpdateTimestamp = 0;
    private static final Object candidatesLock = new Object();

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("🗳️  SISTEMA DE VOTACIÓN ELECTRÓNICA - v2.0");
        System.out.println("   Con Actualizaciones Automáticas");
        System.out.println("==============================================");

        int status = 0;
        java.util.List<String> extraArgs = new java.util.ArrayList<>();

        try {
            communicator = com.zeroc.Ice.Util.initialize(args, extraArgs);
            // Configurar conexión al VotingSite
            communicator.getProperties().setProperty("Ice.Default.Locator", "DemoIceGrid/Locator:default -h localhost -p 4061");

            if (!extraArgs.isEmpty()) {
                System.err.println("too many arguments");
                status = 1;
            } else {
                status = runEnhanced();
            }
        } catch (Exception e) {
            System.err.println("Error crítico del sistema: " + e.getMessage());
            status = 1;
        } finally {
            if (communicator != null) {
                communicator.destroy();
            }
        }

        System.exit(status);
    }

    private static int runEnhanced() {
        // Generar ID único para esta máquina de votación
        machineId = "VM-" + System.currentTimeMillis() % 10000;

        System.out.println("🆔 Máquina de Votación ID: " + machineId);
        System.out.println("🔗 Conectando al sistema central...");

        // Conectar al VotingSite (proxy)
        try {
            votingProxy = VotingProxyPrx.checkedCast(
                    communicator.stringToProxy("VotingProxy:default -h localhost -p 9999")
            );

            if (votingProxy == null) {
                System.err.println("❌ ERROR: No se pudo conectar al sistema central de votación");
                System.err.println("   Verifique que VotingSite esté ejecutándose");
                return 1;
            }

            // Verificar conectividad
            String systemStatus = votingProxy.getSystemStatus();
            System.out.println("✅ Conectado al sistema central");
            System.out.println("📊 Estado del sistema: " + systemStatus);

            // Conectar al CentralServer para notificaciones
            initializeNotifications();

        } catch (Exception e) {
            System.err.println("❌ ERROR: Fallo al conectar con sistema central: " + e.getMessage());
            System.err.println("   Verifique que VotingSite esté ejecutándose en puerto 9999");
            return 1;
        }

        // Iniciar interfaz de usuario mejorada
        return startEnhancedVotingInterface();
    }

    /**
     * Inicializar sistema de notificaciones con CentralServer
     */
    private static void initializeNotifications() {
        try {
            System.out.println("📡 Inicializando sistema de notificaciones automáticas...");

            // Conectar directamente al CentralServer
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
                System.out.println("✅ Registrado con CentralServer para notificaciones push");

                // Obtener candidatos actuales
                fetchCurrentCandidatesFromCentral();

            } else {
                System.out.println("⚠️  No se pudo conectar al CentralServer - usando polling");
            }

        } catch (Exception e) {
            System.err.println("⚠️  Error inicializando notificaciones: " + e.getMessage());
            System.err.println("   Continuando sin notificaciones automáticas");
        }
    }

    /**
     * Obtener candidatos del CentralServer
     */
    private static void fetchCurrentCandidatesFromCentral() {
        try {
            if (centralProxy != null) {
                CandidateListResponse response = centralProxy.getCurrentCandidates();

                synchronized (candidatesLock) {
                    currentCandidates = Arrays.asList(response.candidates);
                    lastUpdateTimestamp = response.updateTimestamp;
                }

                System.out.println("📋 Candidatos obtenidos del CentralServer: " + currentCandidates.size());
            }
        } catch (Exception e) {
            System.err.println("⚠️  Error obteniendo candidatos del CentralServer: " + e.getMessage());
        }
    }

    /**
     * Callback servant para recibir notificaciones
     */
    public static class VotingMachineCallbackI implements VotingMachineCallback {

        @Override
        public void onCandidatesUpdated(CandidateUpdateNotification notification, com.zeroc.Ice.Current current) {
            String timestamp = LocalDateTime.now().format(timeFormatter);

            System.out.println("\n[" + timestamp + "] 🔔 ACTUALIZACIÓN DE CANDIDATOS RECIBIDA");
            System.out.println("═══════════════════════════════════════════════════════════");
            System.out.println("Candidatos actualizados: " + notification.totalCandidates);
            System.out.println("Timestamp de actualización: " + new Date(notification.updateTimestamp));

            synchronized (candidatesLock) {
                currentCandidates = Arrays.asList(notification.candidates);
                lastUpdateTimestamp = notification.updateTimestamp;
            }

            System.out.println("✅ Candidatos actualizados en la máquina de votación");
            System.out.println("═══════════════════════════════════════════════════════════");

            // Mostrar candidatos actualizados
            showUpdatedCandidates();
        }
    }

    private static int startEnhancedVotingInterface() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("\n==============================================");
        System.out.println("BIENVENIDO AL SISTEMA DE VOTACIÓN v2.0");
        System.out.println("Con Actualizaciones Automáticas de Candidatos");
        System.out.println("==============================================");

        showEnhancedMainMenu();

        while (true) {
            System.out.print("\n➤ Seleccione una opción: ");
            String input = scanner.nextLine().trim();

            try {
                switch (input.toLowerCase()) {
                    case "1":
                    case "votar":
                        processEnhancedVote(scanner);
                        break;

                    case "2":
                    case "candidatos":
                        showCurrentCandidates();
                        break;

                    case "3":
                    case "estado":
                        showSystemStatus();
                        break;

                    case "4":
                    case "pendientes":
                        showPendingVotes();
                        break;

                    case "5":
                    case "refresh":
                        refreshCandidates();
                        break;

                    case "6":
                    case "ayuda":
                    case "?":
                        showEnhancedMainMenu();
                        break;

                    case "7":
                    case "salir":
                    case "exit":
                        System.out.println("\n👋 Gracias por usar el sistema de votación");
                        System.out.println("🔒 Cerrando sesión de manera segura...");

                        // Desregistrarse del CentralServer
                        try {
                            if (centralProxy != null) {
                                centralProxy.unregisterVotingMachine(machineId);
                                System.out.println("✅ Desregistrado del CentralServer");
                            }
                        } catch (Exception e) {
                            // Ignorar errores de desregistro
                        }

                        return 0;

                    default:
                        System.out.println("❌ Opción no válida. Digite '?' para ver el menú");
                        break;
                }
            } catch (Exception e) {
                System.err.println("❌ Error procesando solicitud: " + e.getMessage());
            }
        }
    }

    private static void showEnhancedMainMenu() {
        System.out.println("\n📋 OPCIONES DISPONIBLES:");
        System.out.println("  1) Votar                    - Emitir su voto");
        System.out.println("  2) Ver Candidatos           - Lista actualizada de candidatos");
        System.out.println("  3) Estado del Sistema       - Ver estado actual");
        System.out.println("  4) Votos Pendientes         - Ver votos en cola");
        System.out.println("  5) Actualizar Candidatos    - Forzar actualización manual");
        System.out.println("  6) Ayuda                    - Mostrar este menú");
        System.out.println("  7) Salir                    - Cerrar aplicación");
        System.out.println("─────────────────────────────────────────────────");

        if (candidatesNeedRefresh) {
            System.out.println("🔔 NOTA: Hay actualizaciones pendientes de candidatos");
        }
    }

    /**
     * Mostrar candidatos actuales desde el sistema de notificaciones
     */
    private static void showCurrentCandidates() {
        System.out.println("\n📋 CANDIDATOS ACTUALES");
        System.out.println("═══════════════════════════════");

        List<CandidateData> candidates;
        long lastUpdate;

        synchronized (candidatesLock) {
            if (currentCandidates.isEmpty()) {
                System.out.println("⚠️  Cargando candidatos del servidor...");
                fetchCurrentCandidatesFromCentral();

                if (currentCandidates.isEmpty()) {
                    System.out.println("❌ No se pudieron obtener los candidatos");
                    return;
                }
            }

            candidates = new ArrayList<>(currentCandidates);
            lastUpdate = lastUpdateTimestamp;
        }

        System.out.println("🕒 Última actualización: " + new Date(lastUpdate));
        System.out.println("─────────────────────────────────");

        int displayPosition = 1;
        for (CandidateData candidate : candidates) {
            if (!"blank".equals(candidate.candidateId)) {
                System.out.printf("  %s %d) %-25s - %s%n",
                        candidate.photo,
                        displayPosition,
                        candidate.fullName,
                        candidate.partyName);

                if (!candidate.biography.isEmpty() && !"Sin biografía disponible".equals(candidate.biography)) {
                    System.out.printf("     💭 %s%n", candidate.biography);
                }
                displayPosition++;
            }
        }

        System.out.println("  📊 " + displayPosition + ") VOTO EN BLANCO    - Sin preferencia");
        System.out.println("─────────────────────────────────");
        System.out.println("✅ Total de opciones: " + displayPosition);
    }

    /**
     * Mostrar candidatos actualizados en consola (para notificaciones)
     */
    private static void showUpdatedCandidates() {
        List<CandidateData> candidates;

        synchronized (candidatesLock) {
            candidates = new ArrayList<>(currentCandidates);
        }

        if (candidates.isEmpty()) {
            System.out.println("⚠️  No hay candidatos disponibles");
            return;
        }

        System.out.println("\n📋 CANDIDATOS ACTUALIZADOS:");
        System.out.println("─────────────────────────────────────────────────────");

        for (CandidateData candidate : candidates) {
            if (!"blank".equals(candidate.candidateId)) {
                System.out.printf("   %s %d) %-20s - %s%n",
                        candidate.photo,
                        candidate.position,
                        candidate.fullName,
                        candidate.partyName);
            }
        }

        System.out.println("   📊 5) VOTO EN BLANCO    - Sin preferencia");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("ℹ️  Los candidatos han sido actualizados automáticamente");
        System.out.println("   Puede continuar votando con la nueva lista");
    }

    /**
     * Proceso de votación con candidatos actualizados
     */
    private static void processEnhancedVote(Scanner scanner) {
        System.out.println("\n🗳️  PROCESO DE VOTACIÓN");
        System.out.println("═══════════════════════════");

        // Verificar que tenemos candidatos actualizados
        synchronized (candidatesLock) {
            if (currentCandidates.isEmpty()) {
                System.out.println("📡 Obteniendo candidatos actualizados...");
                fetchCurrentCandidatesFromCentral();

                if (currentCandidates.isEmpty()) {
                    System.err.println("❌ No se pudieron obtener los candidatos. Intente más tarde.");
                    return;
                }
            }
        }

        // Solicitar cédula
        System.out.print("📋 Ingrese su número de cédula: ");
        String citizenId = scanner.nextLine().trim();

        if (citizenId.isEmpty()) {
            System.out.println("❌ La cédula no puede estar vacía");
            return;
        }

        // Validar formato básico de cédula
        if (!citizenId.matches("\\d{6,12}")) {
            System.out.println("❌ Formato de cédula inválido. Use solo números (6-12 dígitos)");
            return;
        }

        // Mostrar candidatos actualizados
        showCurrentCandidates();

        // Solicitar voto
        System.out.print("\n🎯 Seleccione el número del candidato: ");
        String candidateInput = scanner.nextLine().trim();

        String candidateId = mapCandidateSelectionEnhanced(candidateInput);
        if (candidateId == null) {
            System.out.println("❌ Selección de candidato inválida");
            return;
        }

        // Confirmar voto con información del candidato
        String candidateName = getCandidateNameEnhanced(candidateId);
        System.out.println("\n✅ CONFIRMACIÓN DE VOTO");
        System.out.println("   📋 Cédula: " + citizenId);
        System.out.println("   🎯 Candidato: " + candidateName);
        System.out.print("🤔 ¿Confirma su voto? (S/N): ");

        String confirmation = scanner.nextLine().trim().toLowerCase();
        if (!confirmation.equals("s") && !confirmation.equals("si") && !confirmation.equals("sí")) {
            System.out.println("❌ Voto cancelado por el usuario");
            return;
        }

        // Enviar voto al sistema
        submitVoteToSystemEnhanced(citizenId, candidateId);
    }

    /**
     * Mapeo de selección con candidatos dinámicos
     */
    private static String mapCandidateSelectionEnhanced(String input) {
        try {
            int selection = Integer.parseInt(input);

            List<CandidateData> candidates;
            synchronized (candidatesLock) {
                candidates = new ArrayList<>(currentCandidates);
            }

            // Filtrar candidatos activos (no blank)
            List<CandidateData> activeCandidates = new ArrayList<>();
            for (CandidateData candidate : candidates) {
                if (!"blank".equals(candidate.candidateId)) {
                    activeCandidates.add(candidate);
                }
            }

            // Ordenar por posición
            activeCandidates.sort((a, b) -> Integer.compare(a.position, b.position));

            if (selection >= 1 && selection <= activeCandidates.size()) {
                return activeCandidates.get(selection - 1).candidateId;
            } else if (selection == activeCandidates.size() + 1) {
                return "blank"; // Voto en blanco
            } else {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Obtener nombre de candidato desde datos actualizados
     */
    private static String getCandidateNameEnhanced(String candidateId) {
        List<CandidateData> candidates;
        synchronized (candidatesLock) {
            candidates = new ArrayList<>(currentCandidates);
        }

        for (CandidateData candidate : candidates) {
            if (candidate.candidateId.equals(candidateId)) {
                if ("blank".equals(candidateId)) {
                    return "VOTO EN BLANCO";
                } else {
                    return candidate.fullName + " (" + candidate.partyName + ")";
                }
            }
        }

        // Fallback
        switch (candidateId) {
            case "blank": return "VOTO EN BLANCO";
            default: return "Candidato: " + candidateId;
        }
    }

    /**
     * Envío de voto con mejor logging
     */
    private static void submitVoteToSystemEnhanced(String citizenId, String candidateId) {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        System.out.println("\n⚡ Procesando voto...");
        System.out.println("[" + timestamp + "] [" + machineId + "] 📡 Enviando voto al sistema central");

        try {
            long startTime = System.currentTimeMillis();
            String result = votingProxy.submitVote(citizenId, candidateId);
            long latency = System.currentTimeMillis() - startTime;

            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("\n🎉 VOTO REGISTRADO EXITOSAMENTE");
            System.out.println("═══════════════════════════════════════");
            System.out.println("[" + timestamp + "] ✅ ID de confirmación: " + result);
            System.out.println("[" + timestamp + "] ⚡ Tiempo de procesamiento: " + latency + "ms");
            System.out.println("\n🙏 ¡Su voto ha sido registrado correctamente!");
            System.out.println("🏛️  Gracias por participar en el proceso democrático");

        } catch (VotingSystemUnavailableException e) {
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("\n⚠️  SISTEMA TEMPORALMENTE NO DISPONIBLE");
            System.out.println("═══════════════════════════════════════");
            System.out.println("[" + timestamp + "] 📋 Razón: " + e.reason);
            System.out.println("[" + timestamp + "] 💾 Su voto ha sido guardado y será procesado automáticamente");
            System.out.println("\n✅ Su voto está en cola y será registrado tan pronto el sistema esté disponible");

        } catch (InvalidVoteException e) {
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("\n❌ VOTO INVÁLIDO");
            System.out.println("═══════════════════════════════════════");
            System.out.println("[" + timestamp + "] 📋 Razón: " + e.reason);
            System.out.println("\n🔄 No se pudo procesar su voto. Verifique los datos e intente nuevamente");

        } catch (Exception e) {
            timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("\n🔌 ERROR DE COMUNICACIÓN");
            System.out.println("═══════════════════════════════════════");
            System.out.println("[" + timestamp + "] ⚠️  Error: " + e.getMessage());
            System.out.println("\n🔧 Error de conectividad. Contacte al administrador del sistema");
        }
    }

    /**
     * Actualización manual de candidatos
     */
    private static void refreshCandidates() {
        System.out.println("\n🔄 Actualizando candidatos...");

        try {
            System.out.println("📡 Consultando candidatos al servidor central...");
            fetchCurrentCandidatesFromCentral();

            candidatesNeedRefresh = false;
            System.out.println("✅ Candidatos actualizados exitosamente");

        } catch (Exception e) {
            System.err.println("❌ Error actualizando candidatos: " + e.getMessage());
            System.err.println("   Los candidatos se actualizarán automáticamente cuando sea posible");
        }
    }

    private static void showSystemStatus() {
        System.out.println("\n🔧 ESTADO DEL SISTEMA");
        System.out.println("═══════════════════════");

        try {
            String status = votingProxy.getSystemStatus();
            System.out.println("🔧 Estado: " + status);

            int pendingVotes = votingProxy.getPendingVotesCount();
            System.out.println("📊 Votos en cola: " + pendingVotes);

            // Estado de notificaciones
            synchronized (candidatesLock) {
                if (lastUpdateTimestamp > 0) {
                    System.out.println("📡 Notificaciones: ✅ ACTIVAS");
                    System.out.println("🕒 Última actualización: " + new Date(lastUpdateTimestamp));
                } else {
                    System.out.println("📡 Notificaciones: ⚠️  DESCONECTADAS");
                }
            }

            if (pendingVotes > 0) {
                System.out.println("\n⚡ El sistema está procesando votos pendientes automáticamente");
            } else {
                System.out.println("\n✅ Todos los votos han sido procesados exitosamente");
            }

        } catch (Exception e) {
            System.out.println("❌ Error obteniendo estado del sistema: " + e.getMessage());
        }
    }

    private static void showPendingVotes() {
        System.out.println("\n📊 VOTOS PENDIENTES");
        System.out.println("═══════════════════════");

        try {
            int pendingCount = votingProxy.getPendingVotesCount();

            if (pendingCount == 0) {
                System.out.println("✅ No hay votos pendientes");
                System.out.println("🎯 Todos los votos han sido procesados exitosamente");
            } else {
                System.out.println("📋 Votos en cola de procesamiento: " + pendingCount);
                System.out.println("⚡ El sistema está trabajando para procesar estos votos");
                System.out.println("🔄 Los votos serán procesados automáticamente cuando el sistema esté disponible");
            }

        } catch (Exception e) {
            System.out.println("❌ Error consultando votos pendientes: " + e.getMessage());
        }
    }
}
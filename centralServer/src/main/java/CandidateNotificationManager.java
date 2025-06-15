import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import CandidateNotification.*;

/**
 * CandidateNotificationManager - Sistema de notificaciones push para actualización de candidatos
 * Notifica a todas las VotingMachine conectadas cuando se actualizan los candidatos
 */
public class CandidateNotificationManager {
    private static final CandidateNotificationManager instance = new CandidateNotificationManager();
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Registry de VotingMachines conectadas
    private final ConcurrentHashMap<String, VotingMachineCallbackPrx> connectedMachines = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<CandidateUpdateListener> listeners = new CopyOnWriteArrayList<>();

    // Cache de candidatos para envío rápido
    private volatile List<CandidateData> currentCandidates = new ArrayList<>();
    private volatile long lastUpdateTimestamp = System.currentTimeMillis();

    private CandidateNotificationManager() {
        System.out.println("[CandidateNotificationManager] Sistema de notificaciones inicializado");
    }

    public static CandidateNotificationManager getInstance() {
        return instance;
    }

    /**
     * Registrar una VotingMachine para recibir notificaciones
     */
    public synchronized void registerVotingMachine(String machineId, VotingMachineCallbackPrx callback) {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        try {
            // Verificar que la máquina esté activa
            callback.ice_ping();

            connectedMachines.put(machineId, callback);
            System.out.println("[" + timestamp + "] [CandidateNotificationManager] VotingMachine registrada: " + machineId);
            System.out.println("[" + timestamp + "] [CandidateNotificationManager] Total máquinas conectadas: " + connectedMachines.size());

            // Enviar candidatos actuales inmediatamente
            sendCandidatesToMachine(machineId, callback);

        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [CandidateNotificationManager] Error registrando máquina " + machineId + ": " + e.getMessage());
        }
    }

    /**
     * Desregistrar VotingMachine
     */
    public synchronized void unregisterVotingMachine(String machineId) {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        VotingMachineCallbackPrx removed = connectedMachines.remove(machineId);
        if (removed != null) {
            System.out.println("[" + timestamp + "] [CandidateNotificationManager] VotingMachine desregistrada: " + machineId);
            System.out.println("[" + timestamp + "] [CandidateNotificationManager] Total máquinas conectadas: " + connectedMachines.size());
        }
    }

    /**
     * Notificar actualización de candidatos a todas las máquinas
     */
    public void notifyCandidateUpdate(CandidateManager candidateManager) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [CandidateNotificationManager] 🔄 Iniciando notificación de actualización de candidatos");

        // Preparar datos de candidatos
        currentCandidates = prepareCandidateData(candidateManager);
        lastUpdateTimestamp = System.currentTimeMillis();

        System.out.println("[" + timestamp + "] [CandidateNotificationManager] Candidatos preparados: " + currentCandidates.size());
        System.out.println("[" + timestamp + "] [CandidateNotificationManager] Notificando a " + connectedMachines.size() + " máquinas...");

        // Notificar a todas las máquinas conectadas
        int successCount = 0;
        int failureCount = 0;
        List<String> failedMachines = new ArrayList<>();

        for (Map.Entry<String, VotingMachineCallbackPrx> entry : connectedMachines.entrySet()) {
            String machineId = entry.getKey();
            VotingMachineCallbackPrx callback = entry.getValue();

            try {
                sendCandidatesToMachine(machineId, callback);
                successCount++;
                System.out.println("[" + timestamp + "] [CandidateNotificationManager] ✅ Notificado a: " + machineId);

            } catch (Exception e) {
                failureCount++;
                failedMachines.add(machineId);
                System.err.println("[" + timestamp + "] [CandidateNotificationManager] ❌ Error notificando a " + machineId + ": " + e.getMessage());
            }
        }

        // Limpiar máquinas que fallaron (probablemente desconectadas)
        for (String failedMachine : failedMachines) {
            unregisterVotingMachine(failedMachine);
        }

        // Notificar a listeners internos
        notifyListeners();

        System.out.println("[" + timestamp + "] [CandidateNotificationManager] 📊 Notificación completada:");
        System.out.println("   ✅ Exitosas: " + successCount);
        System.out.println("   ❌ Fallidas: " + failureCount);
        System.out.println("   🔗 Máquinas activas: " + connectedMachines.size());
    }

    /**
     * Enviar candidatos a una máquina específica
     */
    private void sendCandidatesToMachine(String machineId, VotingMachineCallbackPrx callback) throws Exception {
        CandidateUpdateNotification notification = new CandidateUpdateNotification();
        notification.updateTimestamp = lastUpdateTimestamp;
        notification.candidates = currentCandidates.toArray(new CandidateData[0]);
        notification.totalCandidates = currentCandidates.size();

        // Envío síncrono (removemos el método async que causaba conflicto)
        callback.onCandidatesUpdated(notification);
    }

    /**
     * Preparar datos de candidatos para envío
     */
    private List<CandidateData> prepareCandidateData(CandidateManager candidateManager) {
        List<CandidateData> candidateList = new ArrayList<>();

        for (CandidateManager.Candidate candidate : candidateManager.getActiveCandidates()) {
            CandidateManager.PoliticalParty party = candidateManager.getParty(candidate.partyId);

            CandidateData info = new CandidateData();
            info.candidateId = candidate.id;
            info.firstName = candidate.firstName;
            info.lastName = candidate.lastName;
            info.fullName = candidate.fullName;
            info.position = candidate.position;
            info.photo = candidate.photo;
            info.biography = candidate.biography;
            info.isActive = candidate.isActive;

            if (party != null) {
                info.partyId = party.id;
                info.partyName = party.name;
                info.partyColor = party.color;
                info.partyIdeology = party.ideology;
                info.partyLogo = party.logo;
            }

            candidateList.add(info);
        }

        // Agregar opción de voto en blanco
        CandidateData blankVote = new CandidateData();
        blankVote.candidateId = "blank";
        blankVote.firstName = "VOTO";
        blankVote.lastName = "EN BLANCO";
        blankVote.fullName = "VOTO EN BLANCO";
        blankVote.position = 999;
        blankVote.photo = "📊";
        blankVote.biography = "Opción para votantes que no desean elegir candidato específico";
        blankVote.isActive = true;
        blankVote.partyId = "blank";
        blankVote.partyName = "Voto en Blanco";
        blankVote.partyColor = "#CCCCCC";
        blankVote.partyIdeology = "Ninguna";
        blankVote.partyLogo = "📊";

        candidateList.add(blankVote);

        return candidateList;
    }

    /**
     * Agregar listener para eventos de actualización
     */
    public void addUpdateListener(CandidateUpdateListener listener) {
        listeners.add(listener);
    }

    /**
     * Notificar a listeners internos
     */
    private void notifyListeners() {
        for (CandidateUpdateListener listener : listeners) {
            try {
                listener.onCandidatesUpdated(currentCandidates);
            } catch (Exception e) {
                System.err.println("[CandidateNotificationManager] Error notificando listener: " + e.getMessage());
            }
        }
    }

    /**
     * Obtener estadísticas de conectividad
     */
    public void printConnectionStatus() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [CandidateNotificationManager] === ESTADO DE CONECTIVIDAD ===");
        System.out.println("Máquinas conectadas: " + connectedMachines.size());
        System.out.println("Última actualización: " + new Date(lastUpdateTimestamp));
        System.out.println("Candidatos en cache: " + currentCandidates.size());

        if (!connectedMachines.isEmpty()) {
            System.out.println("\nMáquinas activas:");
            for (String machineId : connectedMachines.keySet()) {
                System.out.println("  📱 " + machineId);
            }
        }
        System.out.println("═══════════════════════════════════════════════");
    }

    /**
     * Verificar conectividad de máquinas registradas
     */
    public void healthCheck() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [CandidateNotificationManager] Ejecutando health check...");

        List<String> disconnectedMachines = new ArrayList<>();

        for (Map.Entry<String, VotingMachineCallbackPrx> entry : connectedMachines.entrySet()) {
            String machineId = entry.getKey();
            VotingMachineCallbackPrx callback = entry.getValue();

            try {
                callback.ice_ping();
                System.out.println("[" + timestamp + "] [CandidateNotificationManager] ✅ " + machineId + " - CONECTADA");
            } catch (Exception e) {
                System.out.println("[" + timestamp + "] [CandidateNotificationManager] ❌ " + machineId + " - DESCONECTADA");
                disconnectedMachines.add(machineId);
            }
        }

        // Limpiar máquinas desconectadas
        for (String machineId : disconnectedMachines) {
            unregisterVotingMachine(machineId);
        }

        System.out.println("[" + timestamp + "] [CandidateNotificationManager] Health check completado. Máquinas activas: " + connectedMachines.size());
    }

    public interface CandidateUpdateListener {
        void onCandidatesUpdated(List<CandidateData> candidates);
    }
}
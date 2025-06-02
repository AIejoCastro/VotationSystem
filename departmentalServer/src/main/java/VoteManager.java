import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

public class VoteManager {
    private static final VoteManager instance = new VoteManager();

    // CORREGIDO: Sincronización mejorada para evitar race conditions
    private final Map<String, String> citizenVotes = new ConcurrentHashMap<>(); // ciudadano -> candidato
    private final BlockingQueue<VoteCommand> queue = new LinkedBlockingQueue<>();

    private VoteManager() {
        for (int i = 0; i < 4; i++) { // 4 hilos para consumir
            new Thread(new VoteWriter(queue)).start();
        }
    }

    public static VoteManager getInstance() {
        return instance;
    }

    /**
     * CORREGIDO: Recibe un voto y valida duplicados con sincronización estricta
     * @param citizenId ID del ciudadano
     * @param candidateId ID del candidato
     * @return VoteResult con información del resultado
     */
    public synchronized VoteResult receiveVote(String citizenId, String candidateId) {
        // CRÍTICO: Este método debe ser completamente sincronizado para evitar race conditions

        // Verificar si el ciudadano ya votó
        String existingVote = citizenVotes.get(citizenId);

        if (existingVote != null) {
            // Ciudadano ya votó - retornar información del voto original
            System.out.println("[VoteManager] Ciudadano " + citizenId + " ya votó por " + existingVote +
                    ", rechazando nuevo intento por " + candidateId);

            if (existingVote.equals(candidateId)) {
                return new VoteResult(false, true, existingVote, "Voto duplicado idéntico");
            } else {
                return new VoteResult(false, true, existingVote, "Ciudadano ya votó por candidato diferente");
            }
        }

        // Nuevo voto válido - registrar ATÓMICAMENTE
        citizenVotes.put(citizenId, candidateId);
        queue.add(new VoteCommand(citizenId, candidateId));

        System.out.println("[VoteManager] Nuevo voto válido registrado: " + citizenId + " -> " + candidateId);
        return new VoteResult(true, false, candidateId, "Voto registrado exitosamente");
    }

    /**
     * Obtener el voto existente de un ciudadano
     */
    public String getExistingVote(String citizenId) {
        return citizenVotes.get(citizenId);
    }

    /**
     * NUEVO: Verificar si un ciudadano ya votó (para debugging)
     */
    public boolean hasVoted(String citizenId) {
        return citizenVotes.containsKey(citizenId);
    }

    /**
     * NUEVO: Obtener todos los ciudadanos que han votado (para debugging)
     */
    public Set<String> getAllVoters() {
        return citizenVotes.keySet();
    }

    /**
     * Obtener estadísticas de votación
     */
    public VotingStats getStats() {
        return new VotingStats(citizenVotes.size(), queue.size());
    }

    /**
     * NUEVO: Imprimir estado interno para debugging
     */
    public void printDebugInfo() {
        System.out.println("[VoteManager] === DEBUG INFO ===");
        System.out.println("Total ciudadanos registrados: " + citizenVotes.size());
        System.out.println("Votos en cola de escritura: " + queue.size());

        if (citizenVotes.size() <= 50) { // Solo mostrar si no son demasiados
            System.out.println("Ciudadanos registrados:");
            citizenVotes.forEach((citizen, candidate) ->
                    System.out.println("  " + citizen + " -> " + candidate));
        }
        System.out.println("================================");
    }

    /**
     * Resultado de procesamiento de voto
     */
    public static class VoteResult {
        public final boolean success;
        public final boolean isDuplicate;
        public final String candidateId;
        public final String message;

        public VoteResult(boolean success, boolean isDuplicate, String candidateId, String message) {
            this.success = success;
            this.isDuplicate = isDuplicate;
            this.candidateId = candidateId;
            this.message = message;
        }

        @Override
        public String toString() {
            return String.format("VoteResult{success=%s, isDuplicate=%s, candidateId='%s', message='%s'}",
                    success, isDuplicate, candidateId, message);
        }
    }

    /**
     * Estadísticas de votación
     */
    public static class VotingStats {
        public final int totalVoters;
        public final int pendingVotes;

        public VotingStats(int totalVoters, int pendingVotes) {
            this.totalVoters = totalVoters;
            this.pendingVotes = pendingVotes;
        }

        @Override
        public String toString() {
            return String.format("VotingStats{totalVoters=%d, pendingVotes=%d}", totalVoters, pendingVotes);
        }
    }
}
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

public class VoteManager {
    private static final VoteManager instance = new VoteManager();

    // Cambio: Almacenar voto completo (ciudadano -> candidato)
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
     * Recibe un voto y valida duplicados
     * @param citizenId ID del ciudadano
     * @param candidateId ID del candidato
     * @return VoteResult con información del resultado
     */
    public VoteResult receiveVote(String citizenId, String candidateId) {
        // Verificar si el ciudadano ya votó
        String existingVote = citizenVotes.get(citizenId);

        if (existingVote != null) {
            // Ciudadano ya votó - verificar si es el mismo candidato
            if (existingVote.equals(candidateId)) {
                return new VoteResult(false, true, existingVote, "Voto duplicado idéntico");
            } else {
                return new VoteResult(false, true, existingVote, "Ciudadano ya votó por candidato diferente");
            }
        }

        // Nuevo voto válido - registrar
        citizenVotes.put(citizenId, candidateId);
        queue.add(new VoteCommand(citizenId, candidateId));

        return new VoteResult(true, false, candidateId, "Voto registrado exitosamente");
    }

    /**
     * Obtener el voto existente de un ciudadano
     */
    public String getExistingVote(String citizenId) {
        return citizenVotes.get(citizenId);
    }

    /**
     * Obtener estadísticas de votación
     */
    public VotingStats getStats() {
        return new VotingStats(citizenVotes.size(), queue.size());
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
    }
}
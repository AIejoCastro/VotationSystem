/**
 * VoteCommand actualizado para compatibilidad con batch processing
 */
public class VoteCommand {
    private final String citizenId;
    private final String candidateId;
    private final long timestamp;

    public VoteCommand(String citizenId, String candidateId) {
        this.citizenId = citizenId;
        this.candidateId = candidateId;
        this.timestamp = System.currentTimeMillis();
    }

    public void persist(VoteDAO dao) {
        dao.save(citizenId, candidateId);
    }

    // NUEVOS: Getters para batch processing
    public String getCitizenId() {
        return citizenId;
    }

    public String getCandidateId() {
        return candidateId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("VoteCommand{citizen='%s', candidate='%s', timestamp=%d}",
                citizenId, candidateId, timestamp);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        VoteCommand that = (VoteCommand) obj;
        return citizenId.equals(that.citizenId) && candidateId.equals(that.candidateId);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(citizenId, candidateId);
    }
}
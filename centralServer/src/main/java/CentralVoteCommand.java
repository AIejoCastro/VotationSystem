/**
 * CentralVoteCommand - Version centralizada del VoteCommand
 * Para batch processing en el servidor central
 */
public class CentralVoteCommand {
    private final String citizenId;
    private final String candidateId;
    private final long timestamp;

    public CentralVoteCommand(String citizenId, String candidateId) {
        this.citizenId = citizenId;
        this.candidateId = candidateId;
        this.timestamp = System.currentTimeMillis();
    }

    public void persist(CentralVoteDAO dao) {
        dao.save(citizenId, candidateId);
    }

    // Getters para batch processing
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
        return String.format("CentralVoteCommand{citizen='%s', candidate='%s', timestamp=%d}",
                citizenId, candidateId, timestamp);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        CentralVoteCommand that = (CentralVoteCommand) obj;
        return citizenId.equals(that.citizenId) && candidateId.equals(that.candidateId);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(citizenId, candidateId);
    }
}
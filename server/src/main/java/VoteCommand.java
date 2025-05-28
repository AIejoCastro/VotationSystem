public class VoteCommand {
    private final String citizenId;
    private final String candidateId;

    public VoteCommand(String citizenId, String candidateId) {
        this.citizenId = citizenId;
        this.candidateId = candidateId;
    }

    public void persist(VoteDAO dao) {
        dao.save(citizenId, candidateId);
    }
}
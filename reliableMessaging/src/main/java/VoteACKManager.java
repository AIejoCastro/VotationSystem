import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class VoteACKManager {
    private final File ackFile;
    private final ConcurrentHashMap<String, ACKRecord> pendingACKs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ACKRecord> confirmedACKs = new ConcurrentHashMap<>();
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static class ACKRecord {
        public final String voteKey;
        public final String citizenId;
        public final String candidateId;
        public final String timestamp;
        public String ackId;
        public String status;
        public long latencyMs;

        public ACKRecord(String citizenId, String candidateId) {
            this.voteKey = citizenId + "|" + candidateId;
            this.citizenId = citizenId;
            this.candidateId = candidateId;
            this.timestamp = LocalDateTime.now().format(timeFormatter);
            this.status = "PENDING";
            this.latencyMs = 0;
        }
    }

    public VoteACKManager(String ackFilePath) {
        this.ackFile = new File(ackFilePath);
        File parentDir = ackFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        loadACKsFromFile();
    }

    public synchronized String addPendingVote(String citizenId, String candidateId) {
        String voteKey = citizenId + "|" + candidateId;
        ACKRecord record = new ACKRecord(citizenId, candidateId);
        pendingACKs.put(voteKey, record);
        saveACKToFile(record);

        System.out.println("[ACK] Voto pendiente registrado: " + voteKey);
        return voteKey;
    }

    public synchronized void confirmACK(String voteKey, String ackId, long latencyMs) {
        ACKRecord record = pendingACKs.remove(voteKey);
        if (record != null) {
            record.ackId = ackId;
            record.status = "CONFIRMED";
            record.latencyMs = latencyMs;
            confirmedACKs.put(voteKey, record);
            saveACKToFile(record);

            System.out.println("[ACK] Confirmado: " + voteKey + " -> " + ackId + " (" + latencyMs + "ms)");
        }
    }

    public synchronized void timeoutVote(String voteKey) {
        ACKRecord record = pendingACKs.get(voteKey);
        if (record != null) {
            record.status = "TIMEOUT";
            saveACKToFile(record);
            System.out.println("[ACK] Timeout: " + voteKey);
        }
    }

    public synchronized void printACKStatus() {
        int pending = pendingACKs.size();
        int confirmed = confirmedACKs.size();
        long avgLatency = confirmedACKs.values().stream()
                .mapToLong(r -> r.latencyMs)
                .sum() / Math.max(1, confirmed);

        System.out.println("\n=== Estado del ACK ===");
        System.out.println("Votos confirmados: " + confirmed);
        System.out.println("ACKs pendientes: " + pending);
        System.out.println("Latencia promedio: " + avgLatency + "ms");

        if (pending > 0) {
            System.out.println("\nACKs pendientes:");
            pendingACKs.values().forEach(r ->
                    System.out.println("  " + r.citizenId + " -> " + r.candidateId + " (" + r.timestamp + ")")
            );
        }
        System.out.println("===================\n");
    }

    public synchronized void printVoteHistory() {
        System.out.println("\n=== Historial de Votos ===");

        // Mostrar confirmados
        if (!confirmedACKs.isEmpty()) {
            System.out.println("CONFIRMADOS:");
            confirmedACKs.values().forEach(r ->
                    System.out.println("  [" + r.timestamp + "] " + r.citizenId + " -> " + r.candidateId +
                            " | ACK: " + r.ackId + " | " + r.latencyMs + "ms")
            );
        }

        // Mostrar pendientes
        if (!pendingACKs.isEmpty()) {
            System.out.println("PENDIENTES:");
            pendingACKs.values().forEach(r ->
                    System.out.println("  [" + r.timestamp + "] " + r.citizenId + " -> " + r.candidateId + " | ESPERANDO ACK")
            );
        }

        System.out.println("========================\n");
    }

    private void saveACKToFile(ACKRecord record) {
        try (FileWriter fw = new FileWriter(ackFile, true)) {
            fw.write(record.timestamp + "|" + record.status + "|" + record.citizenId + "|" +
                    record.candidateId + "|" + (record.ackId != null ? record.ackId : "null") +
                    "|" + record.latencyMs + "\n");
        } catch (IOException e) {
            System.err.println("[ACK] Error guardando en archivo: " + e.getMessage());
        }
    }

    private void loadACKsFromFile() {
        if (!ackFile.exists()) return;

        try (BufferedReader br = new BufferedReader(new FileReader(ackFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length >= 6) {
                    String status = parts[1];
                    String citizenId = parts[2];
                    String candidateId = parts[3];
                    String ackId = parts[4].equals("null") ? null : parts[4];
                    long latency = Long.parseLong(parts[5]);

                    ACKRecord record = new ACKRecord(citizenId, candidateId);
                    record.ackId = ackId;
                    record.status = status;
                    record.latencyMs = latency;

                    if ("CONFIRMED".equals(status)) {
                        confirmedACKs.put(record.voteKey, record);
                    } else if ("PENDING".equals(status)) {
                        pendingACKs.put(record.voteKey, record);
                    }
                }
            }
            System.out.println("[ACK] Cargados " + (pendingACKs.size() + confirmedACKs.size()) + " registros del archivo");
        } catch (IOException e) {
            System.err.println("[ACK] Error cargando archivo: " + e.getMessage());
        }
    }
}
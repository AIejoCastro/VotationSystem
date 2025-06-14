import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * DepartmentalVoteACKManager - Manejo de ACKs para comunicación Departamental → Central
 * Rastrea votos enviados al CentralServer y sus confirmaciones
 */
public class DepartmentalVoteACKManager {
    private final File ackFile;
    private final ConcurrentHashMap<String, ACKRecord> pendingACKs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ACKRecord> confirmedACKs = new ConcurrentHashMap<>();
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static class ACKRecord {
        public final String voteKey;
        public final String citizenId;
        public final String candidateId;
        public final String departmentalServerId;
        public final String timestamp;
        public String ackId;
        public String status;
        public long latencyMs;

        public ACKRecord(String citizenId, String candidateId, String departmentalServerId) {
            this.voteKey = citizenId + "|" + candidateId + "|" + departmentalServerId;
            this.citizenId = citizenId;
            this.candidateId = candidateId;
            this.departmentalServerId = departmentalServerId;
            this.timestamp = LocalDateTime.now().format(timeFormatter);
            this.status = "PENDING";
            this.latencyMs = 0;
        }
    }

    public DepartmentalVoteACKManager(String ackFilePath) {
        this.ackFile = new File(ackFilePath);
        File parentDir = ackFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        loadACKsFromFile();
    }

    public synchronized String addPendingVote(String citizenId, String candidateId, String departmentalServerId) {
        String voteKey = citizenId + "|" + candidateId + "|" + departmentalServerId;
        ACKRecord record = new ACKRecord(citizenId, candidateId, departmentalServerId);
        pendingACKs.put(voteKey, record);
        saveACKToFile(record);

        System.out.println("[DepartmentalACK] Voto pendiente registrado: " + voteKey);
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

            System.out.println("[DepartmentalACK] Confirmado: " + voteKey + " -> " + ackId + " (" + latencyMs + "ms)");
        }
    }

    public synchronized void timeoutVote(String voteKey) {
        ACKRecord record = pendingACKs.get(voteKey);
        if (record != null) {
            record.status = "TIMEOUT";
            saveACKToFile(record);
            System.out.println("[DepartmentalACK] Timeout: " + voteKey);
        }
    }

    public synchronized void failVote(String voteKey, String reason) {
        ACKRecord record = pendingACKs.get(voteKey);
        if (record != null) {
            record.status = "FAILED";
            record.ackId = "FAILED: " + reason;
            saveACKToFile(record);
            System.out.println("[DepartmentalACK] Fallo: " + voteKey + " - " + reason);
        }
    }

    public synchronized void printACKStatus() {
        int pending = pendingACKs.size();
        int confirmed = confirmedACKs.size();
        long avgLatency = confirmedACKs.values().stream()
                .mapToLong(r -> r.latencyMs)
                .sum() / Math.max(1, confirmed);

        System.out.println("\n=== Estado del ACK Departamental ===");
        System.out.println("Votos confirmados por Central: " + confirmed);
        System.out.println("ACKs pendientes desde Central: " + pending);
        System.out.println("Latencia promedio Central:     " + avgLatency + "ms");

        if (pending > 0) {
            System.out.println("\nACKs pendientes desde CentralServer:");
            pendingACKs.values().forEach(r ->
                    System.out.println("  " + r.citizenId + " -> " + r.candidateId +
                            " (desde " + r.departmentalServerId + ") [" + r.timestamp + "]")
            );
        }
        System.out.println("====================================\n");
    }

    public synchronized void printVoteHistory() {
        System.out.println("\n=== Historial de Votos Departamental → Central ===");

        // Mostrar confirmados
        if (!confirmedACKs.isEmpty()) {
            System.out.println("CONFIRMADOS POR CENTRAL:");
            confirmedACKs.values().forEach(r ->
                    System.out.println("  [" + r.timestamp + "] " + r.citizenId + " -> " + r.candidateId +
                            " (desde " + r.departmentalServerId + ") | ACK: " + r.ackId + " | " + r.latencyMs + "ms")
            );
        }

        // Mostrar pendientes
        if (!pendingACKs.isEmpty()) {
            System.out.println("PENDIENTES HACIA CENTRAL:");
            pendingACKs.values().forEach(r ->
                    System.out.println("  [" + r.timestamp + "] " + r.citizenId + " -> " + r.candidateId +
                            " (desde " + r.departmentalServerId + ") | ESPERANDO ACK DE CENTRAL")
            );
        }

        System.out.println("================================================\n");
    }

    private void saveACKToFile(ACKRecord record) {
        try (FileWriter fw = new FileWriter(ackFile, true)) {
            fw.write(record.timestamp + "|" + record.status + "|" + record.citizenId + "|" +
                    record.candidateId + "|" + record.departmentalServerId + "|" +
                    (record.ackId != null ? record.ackId : "null") + "|" + record.latencyMs + "\n");
        } catch (IOException e) {
            System.err.println("[DepartmentalACK] Error guardando en archivo: " + e.getMessage());
        }
    }

    private void loadACKsFromFile() {
        if (!ackFile.exists()) return;

        try (BufferedReader br = new BufferedReader(new FileReader(ackFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length >= 7) {
                    String status = parts[1];
                    String citizenId = parts[2];
                    String candidateId = parts[3];
                    String departmentalServerId = parts[4];
                    String ackId = parts[5].equals("null") ? null : parts[5];
                    long latency = Long.parseLong(parts[6]);

                    ACKRecord record = new ACKRecord(citizenId, candidateId, departmentalServerId);
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
            System.out.println("[DepartmentalACK] Cargados " + (pendingACKs.size() + confirmedACKs.size()) +
                    " registros del archivo");
        } catch (IOException e) {
            System.err.println("[DepartmentalACK] Error cargando archivo: " + e.getMessage());
        }
    }

    public synchronized int getPendingCount() {
        return pendingACKs.size();
    }

    public synchronized int getConfirmedCount() {
        return confirmedACKs.size();
    }
}
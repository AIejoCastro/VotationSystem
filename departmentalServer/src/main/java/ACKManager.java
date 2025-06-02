import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Gestor centralizado de ACKs para garantizar unicidad entre todos los servidores departamentales
 */
public class ACKManager {
    private static final ACKManager instance = new ACKManager();
    private final File ackStateFile;
    private final ConcurrentHashMap<String, String> citizenACKs = new ConcurrentHashMap<>(); // citizenId -> ACK único
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private ACKManager() {
        // Archivo compartido para persistir ACKs entre servidores
        this.ackStateFile = new File("config/db/citizen-acks.csv");
        File parentDir = ackStateFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        loadACKsFromFile();
    }

    public static ACKManager getInstance() {
        return instance;
    }

    /**
     * Obtiene o genera un ACK único para un ciudadano con sincronización en tiempo real
     * @param citizenId ID del ciudadano
     * @param serverName Nombre del servidor que procesa el voto
     * @return ACK único para este ciudadano
     */
    public synchronized String getOrCreateACK(String citizenId, String serverName) {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        // PASO 1: Recargar estado desde archivo antes de verificar (sincronización en tiempo real)
        reloadFromFile();

        // PASO 2: Verificar si el ciudadano ya tiene un ACK asignado
        String existingACK = citizenACKs.get(citizenId);
        if (existingACK != null) {
            System.out.println("[" + timestamp + "] [ACKManager] [" + serverName + "] Ciudadano " + citizenId +
                    " ya tiene ACK: " + existingACK);
            return existingACK;
        }

        // PASO 3: Verificar nuevamente después de recargar para evitar race conditions entre servidores
        reloadFromFile();
        existingACK = citizenACKs.get(citizenId);
        if (existingACK != null) {
            System.out.println("[" + timestamp + "] [ACKManager] [" + serverName + "] ACK encontrado tras reload: " + existingACK);
            return existingACK;
        }

        // PASO 4: Generar nuevo ACK único para el ciudadano
        String newACK = "ACK-UNIQUE-" + serverName + "-" + UUID.randomUUID().toString().substring(0, 8);

        // PASO 5: Usar file locking para operación atómica entre procesos
        if (tryLockAndCreateACK(citizenId, newACK)) {
            System.out.println("[" + timestamp + "] [ACKManager] [" + serverName + "] Nuevo ACK creado: " + newACK);
            return newACK;
        } else {
            // Otro servidor creó el ACK mientras intentábamos - recargar y retornar el existente
            reloadFromFile();
            String finalACK = citizenACKs.get(citizenId);
            System.out.println("[" + timestamp + "] [ACKManager] [" + serverName + "] Otro servidor creó ACK: " + finalACK);
            return finalACK != null ? finalACK : newACK; // Fallback
        }
    }

    /**
     * Intenta crear un ACK de forma atómica usando file locking
     */
    private boolean tryLockAndCreateACK(String citizenId, String ackId) {
        File lockFile = new File(ackStateFile.getParent(), "ack-lock.tmp");

        try {
            // Crear archivo de lock atómicamente
            if (lockFile.createNewFile()) {
                try {
                    // Verificar una vez más que no existe (double-check con lock)
                    reloadFromFile();
                    if (citizenACKs.containsKey(citizenId)) {
                        return false; // Ya existe
                    }

                    // Crear el ACK atómicamente
                    citizenACKs.put(citizenId, ackId);
                    saveACKToFile(citizenId, ackId);
                    return true;

                } finally {
                    // Liberar lock
                    lockFile.delete();
                }
            } else {
                // Otro proceso tiene el lock - esperar un poco y fallar
                Thread.sleep(50);
                return false;
            }
        } catch (Exception e) {
            System.err.println("[ACKManager] Error en file locking: " + e.getMessage());
            // Fallback a operación normal
            citizenACKs.put(citizenId, ackId);
            saveACKToFile(citizenId, ackId);
            return true;
        }
    }

    /**
     * Recargar ACKs desde archivo (sincronización en tiempo real)
     */
    private void reloadFromFile() {
        if (!ackStateFile.exists()) {
            return;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(ackStateFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    String citizenId = parts[1];
                    String ackId = parts[2];

                    // Solo actualizar si no tenemos este ciudadano o si es más reciente
                    if (!citizenACKs.containsKey(citizenId)) {
                        citizenACKs.put(citizenId, ackId);
                    }
                }
            }
        } catch (IOException e) {
            // Error de lectura no crítico
        }
    }

    /**
     * Verificar si un ciudadano ya tiene un ACK asignado
     */
    public boolean hasACK(String citizenId) {
        return citizenACKs.containsKey(citizenId);
    }

    /**
     * Obtener el ACK de un ciudadano (si existe)
     */
    public String getACK(String citizenId) {
        return citizenACKs.get(citizenId);
    }

    /**
     * Obtener estadísticas del ACKManager
     */
    public ACKStats getStats() {
        return new ACKStats(citizenACKs.size());
    }

    /**
     * Guardar ACK en archivo para persistencia con timestamp
     */
    private void saveACKToFile(String citizenId, String ackId) {
        try (FileWriter fw = new FileWriter(ackStateFile, true)) {
            String timestamp = LocalDateTime.now().format(timeFormatter);
            fw.write(timestamp + "," + citizenId + "," + ackId + "\n");
            fw.flush(); // Forzar escritura inmediata
        } catch (IOException e) {
            System.err.println("[ACKManager] Error guardando ACK: " + e.getMessage());
        }
    }

    /**
     * Cargar ACKs desde archivo al inicializar
     */
    private void loadACKsFromFile() {
        if (!ackStateFile.exists()) {
            System.out.println("[ACKManager] No hay archivo de ACKs previo - empezando limpio");
            return;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(ackStateFile))) {
            String line;
            int loadedCount = 0;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    String citizenId = parts[1];
                    String ackId = parts[2];

                    // Solo mantener el ACK más reciente para cada ciudadano
                    citizenACKs.put(citizenId, ackId);
                    loadedCount++;
                }
            }
            System.out.println("[ACKManager] Cargados " + loadedCount + " ACKs del archivo");
            System.out.println("[ACKManager] ACKs únicos activos: " + citizenACKs.size());
        } catch (IOException e) {
            System.err.println("[ACKManager] Error cargando ACKs: " + e.getMessage());
        }
    }

    /**
     * Imprimir estado para debugging
     */
    public void printDebugInfo() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [ACKManager] === DEBUG INFO ===");
        System.out.println("Total ciudadanos con ACK: " + citizenACKs.size());

        if (citizenACKs.size() <= 20) {
            System.out.println("Ciudadanos con ACKs:");
            citizenACKs.forEach((citizen, ack) ->
                    System.out.println("  " + citizen + " -> " + ack));
        }
        System.out.println("==========================");
    }

    /**
     * Limpiar estado para testing
     */
    public synchronized void clearForTesting() {
        citizenACKs.clear();
        if (ackStateFile.exists()) {
            ackStateFile.delete();
        }
        System.out.println("[ACKManager] Estado limpiado para testing");
    }

    /**
     * Clase para estadísticas del ACKManager
     */
    public static class ACKStats {
        public final int totalACKs;

        public ACKStats(int totalACKs) {
            this.totalACKs = totalACKs;
        }

        @Override
        public String toString() {
            return "ACKStats{totalACKs=" + totalACKs + "}";
        }
    }
}
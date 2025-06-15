import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * CandidateManager - Gestor centralizado de candidatos y partidos políticos
 * Carga información desde archivo Excel y mantiene persistencia
 */
public class CandidateManager {
    private static final CandidateManager instance = new CandidateManager();
    private final Map<String, Candidate> candidates = new ConcurrentHashMap<>();
    private final Map<String, PoliticalParty> parties = new ConcurrentHashMap<>();
    private final File candidatesFile = new File("config/db/candidates.csv");
    private final File partiesFile = new File("config/db/parties.csv");
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static class PoliticalParty {
        public final String id;
        public final String name;
        public final String color;
        public final String ideology;
        public final String logo;

        public PoliticalParty(String id, String name, String color, String ideology, String logo) {
            this.id = id;
            this.name = name;
            this.color = color != null ? color : "#CCCCCC";
            this.ideology = ideology != null ? ideology : "No especificada";
            this.logo = logo != null ? logo : "📋";
        }

        @Override
        public String toString() {
            return String.format("%s (%s)", name, ideology);
        }
    }

    public static class Candidate {
        public final String id;
        public final String firstName;
        public final String lastName;
        public final String fullName;
        public final String partyId;
        public final int position;
        public final String photo;
        public final String biography;
        public final boolean isActive;

        public Candidate(String id, String firstName, String lastName, String partyId,
                         int position, String photo, String biography, boolean isActive) {
            this.id = id;
            this.firstName = firstName;
            this.lastName = lastName;
            this.fullName = firstName + " " + lastName;
            this.partyId = partyId;
            this.position = position;
            this.photo = photo != null ? photo : "👤";
            this.biography = biography != null ? biography : "Sin biografía disponible";
            this.isActive = isActive;
        }

        @Override
        public String toString() {
            return String.format("%s (%s)", fullName, id);
        }
    }

    private CandidateManager() {
        File parentDir = candidatesFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // Inicializar candidatos por defecto si no existen archivos
        initializeDefaultCandidates();

        // Cargar datos existentes
        loadExistingData();

        System.out.println("[CandidateManager] Inicializado con " + candidates.size() +
                " candidatos y " + parties.size() + " partidos");
    }

    public static CandidateManager getInstance() {
        return instance;
    }

    /**
     * Cargar candidatos desde archivo Excel
     */
    public boolean loadCandidatesFromExcel(String excelFilePath) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [CandidateManager] Cargando candidatos desde Excel: " + excelFilePath);

        try {
            File excelFile = new File(excelFilePath);
            if (!excelFile.exists()) {
                System.err.println("[CandidateManager] Archivo Excel no encontrado: " + excelFilePath);
                return false;
            }

            // Usar Apache POI para leer Excel
            return parseExcelFile(excelFile);

        } catch (Exception e) {
            System.err.println("[CandidateManager] Error cargando Excel: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Parsear archivo Excel usando Apache POI
     */
    private boolean parseExcelFile(File excelFile) {
        try {
            // Por ahora implementamos lectura básica
            // TODO: Implementar con Apache POI cuando esté disponible
            System.out.println("[CandidateManager] ⚠️  Apache POI no disponible, usando formato CSV como alternativa");
            System.out.println("[CandidateManager] Para cargar desde Excel, convierta el archivo a CSV primero");

            // Intentar cargar como CSV si tiene extensión .csv
            if (excelFile.getName().toLowerCase().endsWith(".csv")) {
                return loadCandidatesFromCSV(excelFile.getAbsolutePath());
            }

            return false;

        } catch (Exception e) {
            System.err.println("[CandidateManager] Error parseando Excel: " + e.getMessage());
            return false;
        }
    }


    /**
     * Cargar candidatos desde archivo CSV (alternativa temporal)
     */
    public boolean loadCandidatesFromCSV(String csvFilePath) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [CandidateManager] Cargando candidatos desde CSV: " + csvFilePath);

        try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
            String line;
            boolean isFirstLine = true;
            int candidatesLoaded = 0;
            int partiesLoaded = 0;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.trim().isEmpty()) continue;

                // CORRECCIÓN: Detectar y saltar encabezados automáticamente
                if (isFirstLine) {
                    isFirstLine = false;

                    // Verificar si la primera línea contiene encabezados
                    String lowerLine = line.toLowerCase();
                    if (lowerLine.contains("candidateid") || lowerLine.contains("candidate_id") ||
                            lowerLine.contains("firstname") || lowerLine.contains("lastname") ||
                            lowerLine.contains("position") || lowerLine.contains("party")) {

                        System.out.println("[CandidateManager] Detectados encabezados en línea 1, saltando...");
                        System.out.println("[CandidateManager] Encabezados: " + line);
                        continue; // Saltar la línea de encabezados
                    } else {
                        System.out.println("[CandidateManager] No se detectaron encabezados, procesando línea 1 como datos");
                        // Procesar esta línea como datos (no hacer continue)
                    }
                }

                String[] parts = line.split(",");

                // MEJORA: Validación más robusta del formato
                if (parts.length < 6) {
                    System.err.println("[CandidateManager] Línea " + lineNumber + " inválida (faltan columnas): " + line);
                    System.err.println("[CandidateManager] Se esperan al menos 6 columnas: candidateId,firstName,lastName,partyId,partyName,position");
                    continue;
                }

                try {
                    // Limpiar espacios y comillas de todos los campos
                    String candidateId = cleanField(parts[0]);
                    String firstName = cleanField(parts[1]);
                    String lastName = cleanField(parts[2]);
                    String partyId = cleanField(parts[3]);
                    String partyName = cleanField(parts[4]);

                    // CORRECCIÓN: Manejo seguro de conversión a entero
                    String positionStr = cleanField(parts[5]);
                    int position;
                    try {
                        position = Integer.parseInt(positionStr);
                    } catch (NumberFormatException e) {
                        System.err.println("[CandidateManager] Error en línea " + lineNumber +
                                ": No se pudo convertir posición '" + positionStr + "' a número");
                        System.err.println("[CandidateManager] Línea completa: " + line);
                        continue;
                    }

                    // Campos opcionales con valores por defecto
                    String photo = parts.length > 6 ? cleanField(parts[6]) : "👤";
                    String biography = parts.length > 7 ? cleanField(parts[7]) : "Sin biografía disponible";
                    boolean isActive = parts.length > 8 ? Boolean.parseBoolean(cleanField(parts[8])) : true;

                    // Validar que los campos obligatorios no estén vacíos
                    if (candidateId.isEmpty() || firstName.isEmpty() || lastName.isEmpty() ||
                            partyId.isEmpty() || partyName.isEmpty()) {
                        System.err.println("[CandidateManager] Línea " + lineNumber +
                                " tiene campos obligatorios vacíos, saltando...");
                        continue;
                    }

                    // Crear/actualizar partido
                    if (!parties.containsKey(partyId)) {
                        PoliticalParty party = new PoliticalParty(partyId, partyName, null, null, null);
                        parties.put(partyId, party);
                        partiesLoaded++;
                        System.out.println("[CandidateManager] Nuevo partido creado: " + partyName + " (" + partyId + ")");
                    }

                    // Crear/actualizar candidato
                    Candidate candidate = new Candidate(candidateId, firstName, lastName,
                            partyId, position, photo, biography, isActive);
                    candidates.put(candidateId, candidate);
                    candidatesLoaded++;

                    System.out.println("[CandidateManager] Candidato cargado: " +
                            firstName + " " + lastName + " (" + partyName + ") - Posición " + position);

                } catch (Exception e) {
                    System.err.println("[CandidateManager] Error procesando línea " + lineNumber + ": " + e.getMessage());
                    System.err.println("[CandidateManager] Línea problemática: " + line);
                    System.err.println("[CandidateManager] Continuando con siguiente línea...");
                    continue;
                }
            }

            // Persistir cambios
            saveCandidatesToFile();
            savePartiesToFile();

            System.out.println("[" + timestamp + "] [CandidateManager] ✅ Carga completada:");
            System.out.println("   Candidatos cargados: " + candidatesLoaded);
            System.out.println("   Partidos cargados: " + partiesLoaded);
            System.out.println("   Total candidatos activos: " + getActiveCandidates().size());

            return candidatesLoaded > 0; // Éxito si se cargó al menos un candidato

        } catch (Exception e) {
            System.err.println("[CandidateManager] Error cargando CSV: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Limpiar campo CSV (remover espacios y comillas)
     */
    private String cleanField(String field) {
        if (field == null) return "";
        return field.trim().replaceAll("^\"|\"$", ""); // Remover comillas del inicio y final
    }

    /**
     * Obtener candidato por ID
     */
    public Candidate getCandidate(String candidateId) {
        return candidates.get(candidateId);
    }

    /**
     * Obtener partido por ID
     */
    public PoliticalParty getParty(String partyId) {
        return parties.get(partyId);
    }

    /**
     * Obtener todos los candidatos activos
     */
    public List<Candidate> getActiveCandidates() {
        return candidates.values().stream()
                .filter(c -> c.isActive)
                .sorted((a, b) -> Integer.compare(a.position, b.position))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Obtener todos los partidos
     */
    public List<PoliticalParty> getAllParties() {
        return new ArrayList<>(parties.values());
    }

    /**
     * Formatear nombre de candidato para mostrar
     */
    public String formatCandidateName(String candidateId) {
        Candidate candidate = getCandidate(candidateId);
        if (candidate != null) {
            PoliticalParty party = getParty(candidate.partyId);
            String partyName = party != null ? party.name : "Partido Desconocido";
            return candidate.fullName + " (" + partyName + ")";
        }

        // Fallback a nombres por defecto
        switch (candidateId) {
            case "candidate001": return "Juan Pérez (Partido Azul)";
            case "candidate002": return "María García (Partido Verde)";
            case "candidate003": return "Carlos López (Partido Rojo)";
            case "candidate004": return "Ana Martínez (Partido Amarillo)";
            case "blank": return "VOTO EN BLANCO";
            default: return candidateId;
        }
    }

    /**
     * Mostrar información completa de candidatos
     */
    public void printCandidatesInfo() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [CandidateManager] === INFORMACIÓN DE CANDIDATOS ===");

        if (candidates.isEmpty()) {
            System.out.println("📊 No hay candidatos registrados");
            System.out.println("═".repeat(70));
            return;
        }

        System.out.println("🗳️  CANDIDATOS REGISTRADOS:");
        System.out.println("   " + "─".repeat(80));

        List<Candidate> activeCandidates = getActiveCandidates();

        for (Candidate candidate : activeCandidates) {
            PoliticalParty party = getParty(candidate.partyId);
            String partyName = party != null ? party.name : "Partido Desconocido";
            String status = candidate.isActive ? "✅ ACTIVO" : "❌ INACTIVO";

            System.out.println(String.format("   %s %d. %-25s | %-20s | %s",
                    candidate.photo, candidate.position, candidate.fullName, partyName, status));
        }

        System.out.println("   " + "─".repeat(80));
        System.out.println("📊 RESUMEN:");
        System.out.println("   Total candidatos: " + candidates.size());
        System.out.println("   Candidatos activos: " + activeCandidates.size());
        System.out.println("   Partidos políticos: " + parties.size());

        System.out.println("\n🏛️  PARTIDOS POLÍTICOS:");
        for (PoliticalParty party : getAllParties()) {
            long candidateCount = candidates.values().stream()
                    .filter(c -> c.partyId.equals(party.id) && c.isActive)
                    .count();
            System.out.println("   " + party.logo + " " + party.name + " (" + candidateCount + " candidatos)");
        }

        System.out.println("═".repeat(70));
    }

    /**
     * Inicializar candidatos por defecto
     */
    private void initializeDefaultCandidates() {
        // Partidos por defecto
        parties.put("party001", new PoliticalParty("party001", "Partido Azul", "#0066CC", "Centro-derecha", "🔵"));
        parties.put("party002", new PoliticalParty("party002", "Partido Verde", "#00AA44", "Ecologista", "🟢"));
        parties.put("party003", new PoliticalParty("party003", "Partido Rojo", "#CC0000", "Izquierda", "🔴"));
        parties.put("party004", new PoliticalParty("party004", "Partido Amarillo", "#FFAA00", "Liberal", "🟡"));

        // Candidatos por defecto
        candidates.put("candidate001", new Candidate("candidate001", "Juan", "Pérez", "party001", 1, "👨‍💼", "Candidato con experiencia en administración pública", true));
        candidates.put("candidate002", new Candidate("candidate002", "María", "García", "party002", 2, "👩‍💼", "Activista ambiental y ex-alcaldesa", true));
        candidates.put("candidate003", new Candidate("candidate003", "Carlos", "López", "party003", 3, "👨‍🏫", "Profesor universitario y líder sindical", true));
        candidates.put("candidate004", new Candidate("candidate004", "Ana", "Martínez", "party004", 4, "👩‍⚖️", "Abogada constitucionalista", true));
    }

    /**
     * Cargar datos existentes desde archivos
     */
    private void loadExistingData() {
        loadPartiesFromFile();
        loadCandidatesFromFile();
    }

    /**
     * Guardar candidatos a archivo
     */
    private void saveCandidatesToFile() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(candidatesFile))) {
            writer.println("candidateId,firstName,lastName,partyId,position,photo,biography,isActive");
            for (Candidate candidate : candidates.values()) {
                writer.println(String.format("%s,%s,%s,%s,%d,%s,\"%s\",%s",
                        candidate.id, candidate.firstName, candidate.lastName, candidate.partyId,
                        candidate.position, candidate.photo, candidate.biography, candidate.isActive));
            }
        } catch (IOException e) {
            System.err.println("[CandidateManager] Error guardando candidatos: " + e.getMessage());
        }
    }

    /**
     * Guardar partidos a archivo
     */
    private void savePartiesToFile() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(partiesFile))) {
            writer.println("partyId,name,color,ideology,logo");
            for (PoliticalParty party : parties.values()) {
                writer.println(String.format("%s,\"%s\",%s,\"%s\",%s",
                        party.id, party.name, party.color, party.ideology, party.logo));
            }
        } catch (IOException e) {
            System.err.println("[CandidateManager] Error guardando partidos: " + e.getMessage());
        }
    }

    /**
     * Cargar candidatos desde archivo
     */
    private void loadCandidatesFromFile() {
        if (!candidatesFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(candidatesFile))) {
            String line;
            boolean isFirstLine = true;
            while ((line = reader.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    continue; // Saltar encabezados
                }
                if (line.trim().isEmpty()) continue;

                String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"); // Split considerando comillas
                if (parts.length >= 8) {
                    String id = parts[0].trim();
                    String firstName = parts[1].trim();
                    String lastName = parts[2].trim();
                    String partyId = parts[3].trim();
                    int position = Integer.parseInt(parts[4].trim());
                    String photo = parts[5].trim();
                    String biography = parts[6].trim().replaceAll("^\"|\"$", ""); // Remover comillas
                    boolean isActive = Boolean.parseBoolean(parts[7].trim());

                    candidates.put(id, new Candidate(id, firstName, lastName, partyId, position, photo, biography, isActive));
                }
            }
        } catch (IOException e) {
            System.err.println("[CandidateManager] Error cargando candidatos: " + e.getMessage());
        }
    }

    /**
     * Cargar partidos desde archivo
     */
    private void loadPartiesFromFile() {
        if (!partiesFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(partiesFile))) {
            String line;
            boolean isFirstLine = true;
            while ((line = reader.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    continue; // Saltar encabezados
                }
                if (line.trim().isEmpty()) continue;

                String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                if (parts.length >= 5) {
                    String id = parts[0].trim();
                    String name = parts[1].trim().replaceAll("^\"|\"$", "");
                    String color = parts[2].trim();
                    String ideology = parts[3].trim().replaceAll("^\"|\"$", "");
                    String logo = parts[4].trim();

                    parties.put(id, new PoliticalParty(id, name, color, ideology, logo));
                }
            }
        } catch (IOException e) {
            System.err.println("[CandidateManager] Error cargando partidos: " + e.getMessage());
        }
    }
}
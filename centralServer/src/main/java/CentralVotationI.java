//
// CentralVotationI - Implementación del servidor central con toda la lógica de base de datos
//

import Central.*;
import CandidateNotification.*;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class CentralVotationI implements CentralVotation {
    private final String serverName;
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Componentes centralizados (movidos desde DepartmentalServer)
    private final CentralVoteManager voteManager;
    private final CentralACKManager ackManager;
    private final CandidateManager candidateManager;
    private final CitizenDAO citizenDAO;

    public CentralVotationI(String serverName) {
        this.serverName = serverName;
        this.voteManager = CentralVoteManager.getInstance();
        this.ackManager = CentralACKManager.getInstance();
        this.candidateManager = CandidateManager.getInstance();
        this.citizenDAO = new CitizenDAO();

        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] Servidor central inicializado");
        System.out.println("[" + timestamp + "] [" + serverName + "] Componentes optimizados para alta carga activos");

        // Mostrar estado inicial después de cargar persistencia
        printInitialLoadStatus();
    }

    /**
     * Mostrar estado inicial después de cargar datos persistentes
     */
    private void printInitialLoadStatus() {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        try {
            CentralVoteManager.VotingStats voteStats = voteManager.getStats();
            CentralACKManager.ACKStats ackStats = ackManager.getStats();

            System.out.println("\n[" + timestamp + "] [" + serverName + "] === ESTADO INICIAL DESPUÉS DE CARGA ===");
            System.out.println("📊 DATOS CARGADOS DESDE ARCHIVOS:");
            System.out.println("   Votantes únicos cargados:    " + voteStats.totalVoters);
            System.out.println("   Votos totales cargados:      " + voteStats.totalProcessed);
            System.out.println("   ACKs cargados:               " + ackStats.totalACKs);
            System.out.println("   Candidatos registrados:      " + candidateManager.getActiveCandidates().size());
            System.out.println("   Partidos políticos:          " + candidateManager.getAllParties().size());

            if (voteStats.totalVoters > 0) {
                // Mostrar algunos resultados si hay votos
                java.util.Map<String, Integer> votesByCandidate = voteManager.getVotesByCandidate();
                System.out.println();
                System.out.println("🗳️  ESTADO ACTUAL DE VOTACIÓN:");

                votesByCandidate.entrySet().stream()
                        .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                        .limit(3) // Mostrar top 3
                        .forEach(entry -> {
                            String candidateName = formatCandidateName(entry.getKey());
                            System.out.println("   " + candidateName + ": " + entry.getValue() + " votos");
                        });

                System.out.println("   (Use comando 'results' para ver detalles completos)");
            }

            System.out.println("\n🚀 SERVIDOR LISTO PARA RECIBIR NUEVOS VOTOS");
            System.out.println("═".repeat(60));

        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] Error mostrando estado inicial: " + e.getMessage());
        }
    }

    @Override
    public String processVote(String citizenId, String candidateId, String departmentalServerId, com.zeroc.Ice.Current current)
            throws AlreadyVotedCentralException, CitizenNotRegisteredException, CentralServerUnavailableException {

        String timestamp = LocalDateTime.now().format(timeFormatter);
        String clientInfo = current.con != null ? current.con.toString() : "unknown";

        System.out.println("[" + timestamp + "] [" + serverName + "] Voto recibido desde " + departmentalServerId);
        System.out.println("[" + timestamp + "] [" + serverName + "] Procesando: " + citizenId + " -> " + candidateId);

        if (citizenId == null || citizenId.trim().isEmpty() || candidateId == null || candidateId.trim().isEmpty()) {
            throw new CentralServerUnavailableException("Parámetros inválidos", System.currentTimeMillis());
        }

        String cleanCitizenId = citizenId.trim();
        String cleanCandidateId = candidateId.trim();

        try {
            // PASO 1: NUEVA VALIDACIÓN - Verificar que el ciudadano esté registrado
            boolean citizenExists = citizenDAO.validateCitizen(cleanCitizenId);

            if (!citizenExists) {
                timestamp = LocalDateTime.now().format(timeFormatter);
                System.out.println("[" + timestamp + "] [" + serverName + "] ❌ Ciudadano NO registrado: " + cleanCitizenId);

                CitizenNotRegisteredException ex = new CitizenNotRegisteredException();
                ex.citizenId = cleanCitizenId;
                ex.message = "Ciudadano no está registrado en la base de datos electoral";
                throw ex;
            }

            System.out.println("[" + timestamp + "] [" + serverName + "] ✅ Ciudadano validado: " + cleanCitizenId);

            // PASO 2: Verificación de voto duplicado con ACKManager
            String existingACK = ackManager.getACK(cleanCitizenId);

            if (existingACK != null) {
                String existingVote = voteManager.getExistingVote(cleanCitizenId);

                System.out.println("[" + timestamp + "] [" + serverName + "] Duplicado detectado: " +
                        cleanCitizenId + " ya votó por " + existingVote + " (ACK: " + existingACK + ")");

                AlreadyVotedCentralException ex = new AlreadyVotedCentralException();
                ex.ackId = existingACK;
                ex.citizenId = cleanCitizenId;
                ex.existingCandidate = existingVote != null ? existingVote : "unknown";
                throw ex;
            }

            // PASO 3: Procesar voto válido con VoteManager
            CentralVoteManager.VoteResult result = voteManager.receiveVote(cleanCitizenId, cleanCandidateId);

            if (result.success) {
                // VOTO VÁLIDO - generar ACK único
                String ackId = ackManager.getOrCreateACK(cleanCitizenId, serverName + "-" + departmentalServerId);

                System.out.println("[" + timestamp + "] [" + serverName + "] ✅ Voto procesado exitosamente - ACK: " + ackId);
                return ackId;

            } else {
                // VOTO DUPLICADO - obtener ACK existente
                String ackId = ackManager.getOrCreateACK(cleanCitizenId, serverName + "-" + departmentalServerId);
                String existingVote = voteManager.getExistingVote(cleanCitizenId);

                AlreadyVotedCentralException ex = new AlreadyVotedCentralException();
                ex.ackId = ackId;
                ex.citizenId = cleanCitizenId;
                ex.existingCandidate = existingVote != null ? existingVote : candidateId;
                throw ex;
            }

        } catch (AlreadyVotedCentralException | CitizenNotRegisteredException e) {
            throw e; // Re-lanzar excepciones específicas
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] Error procesando voto: " + e.getMessage());
            throw new CentralServerUnavailableException("Error interno del servidor central: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public String getExistingACK(String citizenId, com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        try {
            return ackManager.getACK(citizenId != null ? citizenId.trim() : null);
        } catch (Exception e) {
            throw new CentralServerUnavailableException("Error consultando ACK: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public boolean hasVoted(String citizenId, com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        try {
            return voteManager.hasVoted(citizenId != null ? citizenId.trim() : null);
        } catch (Exception e) {
            throw new CentralServerUnavailableException("Error verificando voto: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public String getVoteForCitizen(String citizenId, com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        try {
            return voteManager.getExistingVote(citizenId != null ? citizenId.trim() : null);
        } catch (Exception e) {
            throw new CentralServerUnavailableException("Error consultando voto: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public int getTotalVotesCount(com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        try {
            return voteManager.getStats().totalProcessed;
        } catch (Exception e) {
            throw new CentralServerUnavailableException("Error consultando total: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public int getUniqueVotersCount(com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        try {
            return voteManager.getAllVoters().size();
        } catch (Exception e) {
            throw new CentralServerUnavailableException("Error consultando votantes: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public void ping(com.zeroc.Ice.Current current) {
        // Ping básico para verificar conectividad
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] Ping recibido desde: " +
                (current.con != null ? current.con.toString() : "unknown"));
    }

    @Override
    public String getServerStatus(com.zeroc.Ice.Current current) {
        try {
            CentralVoteManager.VotingStats voteStats = voteManager.getStats();
            CentralACKManager.ACKStats ackStats = ackManager.getStats();

            return String.format("OPERACIONAL - Votos: %d | ACKs: %d | Throughput: %.2f v/s",
                    voteStats.totalVoters, ackStats.totalACKs, voteStats.throughputVotesPerSec);
        } catch (Exception e) {
            return "ERROR - " + e.getMessage();
        }
    }

    @Override
    public void shutdown(com.zeroc.Ice.Current current) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] Shutdown solicitado...");

        try {
            voteManager.shutdown();
            ackManager.shutdown();
            citizenDAO.close();
            System.out.println("[" + timestamp + "] [" + serverName + "] Componentes terminados correctamente");
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] Error en shutdown: " + e.getMessage());
        }

        current.adapter.getCommunicator().shutdown();
    }

    // ============================================================================
    // MÉTODOS DE NOTIFICACIÓN DE CANDIDATOS
    // ============================================================================

    /**
     * NUEVO: Método para registro de VotingMachines
     */
    @Override
    public void registerVotingMachine(String machineId, VotingMachineCallbackPrx callback, com.zeroc.Ice.Current current)
            throws CentralServerUnavailableException {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String clientEndpoint = current.con != null ? current.con.toString() : "unknown";

        System.out.println("[" + timestamp + "] [" + serverName + "] 📱 Registrando VotingMachine: " + machineId);
        System.out.println("[" + timestamp + "] [" + serverName + "] Conexión desde: " + clientEndpoint);

        try {
            CandidateNotificationManager.getInstance().registerVotingMachine(machineId, callback);
            System.out.println("[" + timestamp + "] [" + serverName + "] ✅ VotingMachine registrada exitosamente");
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] ❌ Error registrando VotingMachine: " + e.getMessage());
            throw new CentralServerUnavailableException("Error registrando máquina de votación: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    /**
     * NUEVO: Método para desregistro de VotingMachines
     */
    @Override
    public void unregisterVotingMachine(String machineId, com.zeroc.Ice.Current current)
            throws CentralServerUnavailableException {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] 📱 Desregistrando VotingMachine: " + machineId);

        try {
            CandidateNotificationManager.getInstance().unregisterVotingMachine(machineId);
            System.out.println("[" + timestamp + "] [" + serverName + "] ✅ VotingMachine desregistrada exitosamente");
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] ❌ Error desregistrando VotingMachine: " + e.getMessage());
            throw new CentralServerUnavailableException("Error desregistrando máquina de votación: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    /**
     * NUEVO: Método para obtener candidatos actuales (fallback si fallan notificaciones)
     */
    @Override
    public CandidateListResponse getCurrentCandidates(com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        try {
            System.out.println("[" + timestamp + "] [" + serverName + "] 📋 Solicitud de candidatos actuales");

            CandidateListResponse response = new CandidateListResponse();
            response.updateTimestamp = System.currentTimeMillis();

            List<CandidateData> candidateList = new ArrayList<>();

            // Obtener candidatos activos
            for (CandidateManager.Candidate candidate : candidateManager.getActiveCandidates()) {
                CandidateManager.PoliticalParty party = candidateManager.getParty(candidate.partyId);

                CandidateData candidateData = new CandidateData();
                candidateData.candidateId = candidate.id;
                candidateData.firstName = candidate.firstName;
                candidateData.lastName = candidate.lastName;
                candidateData.fullName = candidate.fullName;
                candidateData.position = candidate.position;
                candidateData.photo = candidate.photo;
                candidateData.biography = candidate.biography;
                candidateData.isActive = candidate.isActive;

                if (party != null) {
                    candidateData.partyId = party.id;
                    candidateData.partyName = party.name;
                    candidateData.partyColor = party.color;
                    candidateData.partyIdeology = party.ideology;
                    candidateData.partyLogo = party.logo;
                }

                candidateList.add(candidateData);
            }

            // Agregar voto en blanco
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

            response.candidates = candidateList.toArray(new CandidateData[0]);
            response.totalCandidates = candidateList.size();

            System.out.println("[" + timestamp + "] [" + serverName + "] ✅ Enviando " +
                    response.totalCandidates + " candidatos");

            return response;

        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] ❌ Error obteniendo candidatos: " + e.getMessage());
            throw new CentralServerUnavailableException("Error obteniendo candidatos: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }

    // ============================================================================
    // MÉTODOS ADMINISTRATIVOS
    // ============================================================================

    /**
     * Cargar candidatos desde archivo con notificación automática
     */
    public void loadCandidatesFromFile(String filePath) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] Cargando candidatos desde: " + filePath);

        boolean success;
        if (filePath.toLowerCase().endsWith(".xlsx") || filePath.toLowerCase().endsWith(".xls")) {
            success = candidateManager.loadCandidatesFromExcel(filePath);
        } else if (filePath.toLowerCase().endsWith(".csv")) {
            success = candidateManager.loadCandidatesFromCSV(filePath);
        } else {
            System.err.println("[" + timestamp + "] [" + serverName + "] Formato de archivo no soportado. Use .xlsx, .xls o .csv");
            return;
        }

        if (success) {
            System.out.println("[" + timestamp + "] [" + serverName + "] ✅ Candidatos cargados exitosamente");
            candidateManager.printCandidatesInfo();

            // 🔥 NUEVA FUNCIONALIDAD: Notificar actualización a todas las VotingMachines
            System.out.println("[" + timestamp + "] [" + serverName + "] 📡 Notificando actualización a máquinas de votación...");
            try {
                CandidateNotificationManager.getInstance().notifyCandidateUpdate(candidateManager);
                System.out.println("[" + timestamp + "] [" + serverName + "] ✅ Notificación enviada exitosamente");
            } catch (Exception e) {
                System.err.println("[" + timestamp + "] [" + serverName + "] ⚠️  Error enviando notificación: " + e.getMessage());
                System.err.println("[" + timestamp + "] [" + serverName + "] Las máquinas recibirán la actualización en su próxima consulta");
            }

        } else {
            System.err.println("[" + timestamp + "] [" + serverName + "] ❌ Error cargando candidatos");
        }
    }

    public void printServerStatus() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n" + "═".repeat(70));
        System.out.println("[" + timestamp + "] [" + serverName + "] === ESTADO DEL SERVIDOR CENTRAL ===");

        try {
            CentralVoteManager.VotingStats voteStats = voteManager.getStats();
            CentralACKManager.ACKStats ackStats = ackManager.getStats();

            System.out.println("Servidor: " + serverName);
            System.out.println("Estado: OPERACIONAL");
            System.out.println();
            System.out.println("📊 ESTADÍSTICAS DE VOTOS:");
            System.out.println("   Votantes únicos:           " + voteStats.totalVoters);
            System.out.println("   Votos procesados:          " + voteStats.totalProcessed);
            System.out.println("   Duplicados detectados:     " + voteStats.duplicatesDetected);
            System.out.println("   Votos pendientes:          " + voteStats.pendingVotes);
            System.out.println("   Throughput actual:         " + String.format("%.2f", voteStats.throughputVotesPerSec) + " v/s");
            System.out.println();
            System.out.println("🔧 ESTADÍSTICAS DE ACK:");
            System.out.println("   ACKs totales:              " + ackStats.totalACKs);
            System.out.println("   ACKs pendientes:           " + ackStats.pendingWrites);
            System.out.println("   Último flush:              " + ackStats.lastFlushAgo + "ms atrás");
            System.out.println();
            System.out.println("💾 BASE DE DATOS:");
            System.out.println("   Estado:                    ACTIVA");
            System.out.println("   Integridad:                VERIFICADA");

        } catch (Exception e) {
            System.err.println("Error obteniendo estadísticas: " + e.getMessage());
        }

        System.out.println("═".repeat(70));
    }

    public void printVotesSummary() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [" + serverName + "] === RESUMEN DE VOTOS ===");

        voteManager.printDebugInfo();
    }

    public void printVotingResults() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [" + serverName + "] === RESULTADOS DE LA VOTACIÓN ===");

        try {
            // Obtener estadísticas detalladas del vote manager
            CentralVoteManager.DetailedVotingStats detailedStats = voteManager.getDetailedStats();

            if (detailedStats.basicStats.totalVoters == 0) {
                System.out.println("📊 No hay votos registrados en el sistema");
                System.out.println("═".repeat(70));
                return;
            }

            System.out.println("📊 ESTADÍSTICAS GENERALES:");
            System.out.println("   Total de votantes:         " + detailedStats.basicStats.totalVoters);
            System.out.println("   Total de votos válidos:     " + detailedStats.totalValidVotes);
            System.out.println("   Votos procesados:           " + detailedStats.basicStats.totalProcessed);
            System.out.println("   Duplicados detectados:      " + detailedStats.basicStats.duplicatesDetected);
            System.out.println();

            if (detailedStats.votesByCandidate.isEmpty()) {
                System.out.println("📊 No se pudieron obtener los resultados por candidato");
            } else {
                System.out.println("🗳️  RESULTADOS POR CANDIDATO:");
                System.out.println("   " + "─".repeat(50));

                // Ordenar candidatos por número de votos (descendente)
                java.util.List<java.util.Map.Entry<String, Integer>> sortedResults =
                        new java.util.ArrayList<>(detailedStats.votesByCandidate.entrySet());
                sortedResults.sort((a, b) -> b.getValue().compareTo(a.getValue()));

                int position = 1;

                for (java.util.Map.Entry<String, Integer> entry : sortedResults) {
                    String candidate = entry.getKey();
                    int votes = entry.getValue();
                    double percentage = detailedStats.totalValidVotes > 0 ?
                            (double) votes / detailedStats.totalValidVotes * 100 : 0;

                    String candidateName = formatCandidateName(candidate);
                    String positionIcon = getPositionIcon(position);

                    // Obtener emoji del candidato para mejor visualización
                    CandidateManager.Candidate candidateObj = candidateManager.getCandidate(candidate);
                    String candidateIcon = candidateObj != null ? candidateObj.photo : "📊";

                    System.out.println(String.format("   %s %s %d° lugar: %-25s %,4d votos (%.2f%%)",
                            positionIcon, candidateIcon, position, candidateName, votes, percentage));
                    position++;
                }

                System.out.println("   " + "─".repeat(50));
                System.out.println(String.format("   📊 TOTAL DE VOTOS VÁLIDOS: %,d", detailedStats.totalValidVotes));

                // Mostrar ganador
                if (detailedStats.winningCandidate != null) {
                    double winnerPercentage = detailedStats.totalValidVotes > 0 ?
                            (double) detailedStats.winningVotes / detailedStats.totalValidVotes * 100 : 0;

                    System.out.println();
                    System.out.println("🏆 GANADOR: " + formatCandidateName(detailedStats.winningCandidate));
                    System.out.println("   Votos obtenidos: " + String.format("%,d", detailedStats.winningVotes) +
                            " (" + String.format("%.2f%%", winnerPercentage) + ")");

                    // Verificar si hay mayoría absoluta
                    if (winnerPercentage > 50.0) {
                        System.out.println("   ✅ MAYORÍA ABSOLUTA");
                    } else {
                        System.out.println("   ⚠️  MAYORÍA SIMPLE (No hay mayoría absoluta)");
                    }
                }

                // Mostrar análisis adicional
                System.out.println();
                System.out.println("📈 ANÁLISIS:");
                System.out.println("   Participación efectiva:     100.00% (de votantes registrados)");
                System.out.println("   Votos en blanco:            " + detailedStats.votesByCandidate.getOrDefault("blank", 0));
                System.out.println("   Candidatos con votos:       " + detailedStats.votesByCandidate.size());

                // Mostrar distribución de votos
                System.out.println();
                System.out.println("📊 DISTRIBUCIÓN VISUAL:");
                showVotingChart(sortedResults, detailedStats.totalValidVotes);
            }

        } catch (Exception e) {
            System.err.println("Error obteniendo resultados: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("═".repeat(70));
    }

    /**
     * Formatear nombre del candidato para display usando CandidateManager
     */
    private String formatCandidateName(String candidateId) {
        return candidateManager.formatCandidateName(candidateId);
    }

    /**
     * Obtener icono según posición
     */
    private String getPositionIcon(int position) {
        switch (position) {
            case 1: return "🥇";
            case 2: return "🥈";
            case 3: return "🥉";
            default: return "📊";
        }
    }

    /**
     * Mostrar información de candidatos y partidos
     */
    public void printCandidatesInfo() {
        candidateManager.printCandidatesInfo();
    }

    /**
     * Abrir selector de archivos gráfico para cargar candidatos
     */
    public void openFileSelector() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] Abriendo selector de archivos...");

        try {
            // Verificar si hay interfaz gráfica disponible
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                System.out.println("⚠️  Interfaz gráfica no disponible. Usando modo texto:");
                loadCandidatesFromConsole();
                return;
            }

            // Crear file chooser en un thread separado para no bloquear
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    openFileChooserDialog();
                } catch (Exception e) {
                    System.err.println("Error abriendo selector: " + e.getMessage());
                    // Fallback a modo consola
                    loadCandidatesFromConsole();
                }
            });

        } catch (Exception e) {
            System.err.println("Error iniciando selector de archivos: " + e.getMessage());
            loadCandidatesFromConsole();
        }
    }

    /**
     * Abrir diálogo de selección de archivos
     */
    private void openFileChooserDialog() {
        try {
            // Configurar Look and Feel nativo
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Continuar con Look and Feel por defecto
        }

        JFileChooser fileChooser = new JFileChooser();

        // Configurar el file chooser
        fileChooser.setDialogTitle("Seleccionar Archivo de Candidatos - CentralServer");
        fileChooser.setMultiSelectionEnabled(false);
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        // Filtros de archivo
        FileNameExtensionFilter csvFilter =
                new FileNameExtensionFilter("Archivos CSV (*.csv)", "csv");
        FileNameExtensionFilter excelFilter =
                new FileNameExtensionFilter("Archivos Excel (*.xlsx, *.xls)", "xlsx", "xls");
        FileNameExtensionFilter allSupportedFilter =
                new FileNameExtensionFilter("Todos los archivos soportados", "csv", "xlsx", "xls");

        fileChooser.addChoosableFileFilter(allSupportedFilter);
        fileChooser.addChoosableFileFilter(csvFilter);
        fileChooser.addChoosableFileFilter(excelFilter);
        fileChooser.setFileFilter(allSupportedFilter);

        // Establecer directorio inicial
        File currentDir = new File(System.getProperty("user.dir"));
        File configDir = new File(currentDir, "config");
        if (configDir.exists()) {
            fileChooser.setCurrentDirectory(configDir);
        } else {
            fileChooser.setCurrentDirectory(currentDir);
        }

        // Crear frame padre para centrar el diálogo
        JFrame parentFrame = new JFrame();
        parentFrame.setAlwaysOnTop(true);
        parentFrame.setIconImage(createServerIcon());

        // Mostrar diálogo
        int result = fileChooser.showOpenDialog(parentFrame);

        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            String filePath = selectedFile.getAbsolutePath();

            String timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] [" + serverName + "] Archivo seleccionado: " + filePath);

            // Mostrar diálogo de confirmación
            String fileName = selectedFile.getName();
            long fileSize = selectedFile.length();
            String fileSizeStr = String.format("%.2f KB", fileSize / 1024.0);

            String message = String.format(
                    "¿Desea cargar el siguiente archivo?\n\n" +
                            "📁 Archivo: %s\n" +
                            "📊 Tamaño: %s\n" +
                            "📂 Ubicación: %s\n\n" +
                            "Se cargarán los candidatos y partidos políticos.",
                    fileName, fileSizeStr, selectedFile.getParent()
            );

            int confirm = JOptionPane.showConfirmDialog(
                    parentFrame,
                    message,
                    "Confirmar Carga de Candidatos",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );

            if (confirm == JOptionPane.YES_OPTION) {
                // Cargar archivo en thread separado para no bloquear UI
                new Thread(() -> {
                    loadCandidatesFromFile(filePath);
                }).start();

                // Mostrar mensaje de procesamiento
                showProcessingMessage(parentFrame, fileName);
            } else {
                System.out.println("[" + timestamp + "] [" + serverName + "] Carga cancelada por el usuario");
            }
        } else {
            String timestamp = LocalDateTime.now().format(timeFormatter);
            System.out.println("[" + timestamp + "] [" + serverName + "] Selección de archivo cancelada");
        }

        parentFrame.dispose();
    }

    /**
     * Crear icono para el servidor
     */
    private java.awt.Image createServerIcon() {
        try {
            // Crear un icono simple programáticamente
            java.awt.image.BufferedImage icon = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2d = icon.createGraphics();

            // Configurar antialiasing
            g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

            // Dibujar icono del servidor (cuadrado azul con "CS")
            g2d.setColor(new java.awt.Color(0, 100, 200));
            g2d.fillRoundRect(2, 2, 28, 28, 8, 8);

            g2d.setColor(java.awt.Color.WHITE);
            g2d.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 12));
            java.awt.FontMetrics fm = g2d.getFontMetrics();
            String text = "CS";
            int textWidth = fm.stringWidth(text);
            int textHeight = fm.getAscent();
            g2d.drawString(text, (32 - textWidth) / 2, (32 + textHeight) / 2 - 2);

            g2d.dispose();
            return icon;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Mostrar mensaje de procesamiento
     */
    private void showProcessingMessage(javax.swing.JFrame parent, String fileName) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            javax.swing.JDialog processingDialog = new javax.swing.JDialog(parent, "Procesando...", true);
            processingDialog.setDefaultCloseOperation(javax.swing.JDialog.DO_NOTHING_ON_CLOSE);
            processingDialog.setSize(400, 150);
            processingDialog.setLocationRelativeTo(parent);

            javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(10, 10));
            panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(20, 20, 20, 20));

            javax.swing.JLabel messageLabel = new javax.swing.JLabel(
                    "<html><center>🔄 Procesando archivo de candidatos...<br><br>" +
                            "<b>" + fileName + "</b><br><br>" +
                            "Por favor espere...</center></html>",
                    javax.swing.SwingConstants.CENTER
            );

            javax.swing.JProgressBar progressBar = new javax.swing.JProgressBar();
            progressBar.setIndeterminate(true);

            panel.add(messageLabel, java.awt.BorderLayout.CENTER);
            panel.add(progressBar, java.awt.BorderLayout.SOUTH);

            processingDialog.add(panel);

            // Cerrar automáticamente después de 3 segundos
            javax.swing.Timer timer = new javax.swing.Timer(3000, e -> processingDialog.dispose());
            timer.setRepeats(false);
            timer.start();

            processingDialog.setVisible(true);
        });
    }

    /**
     * Fallback a modo consola si la interfaz gráfica no está disponible
     */
    private void loadCandidatesFromConsole() {
        System.out.println("📁 SELECTOR DE ARCHIVOS - MODO CONSOLA");
        System.out.println("═".repeat(50));
        System.out.println("Formatos soportados: .csv, .xlsx, .xls");
        System.out.println("Ejemplos de rutas:");
        System.out.println("  - config/candidates-example.csv");
        System.out.println("  - /home/user/candidatos.xlsx");
        System.out.println("  - C:\\Documents\\candidatos.csv");
        System.out.println("═".repeat(50));

        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            System.out.print("Ingrese la ruta completa del archivo: ");
            String filePath = reader.readLine();

            if (filePath != null && !filePath.trim().isEmpty()) {
                loadCandidatesFromFile(filePath.trim());
            } else {
                System.out.println("❌ Ruta de archivo no válida");
            }
        } catch (Exception e) {
            System.err.println("❌ Error leyendo ruta de archivo: " + e.getMessage());
        }
    }

    public void verifyDataIntegrity() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [" + serverName + "] === VERIFICACIÓN DE INTEGRIDAD ===");

        try {
            // Obtener estadísticas
            CentralVoteManager.VotingStats voteStats = voteManager.getStats();
            CentralACKManager.ACKStats ackStats = ackManager.getStats();

            // Verificar correspondencia votos-ACKs
            java.util.Set<String> allVoters = voteManager.getAllVoters();
            int votersWithACK = 0;
            int votersWithoutACK = 0;

            for (String citizenId : allVoters) {
                if (ackManager.hasACK(citizenId)) {
                    votersWithACK++;
                } else {
                    votersWithoutACK++;
                    System.out.println("⚠️  Ciudadano sin ACK: " + citizenId);
                }
            }

            System.out.println("📊 VERIFICACIÓN DE CORRESPONDENCIA:");
            System.out.println("   Total votantes:              " + voteStats.totalVoters);
            System.out.println("   Total ACKs:                  " + ackStats.totalACKs);
            System.out.println("   Votantes con ACK:            " + votersWithACK);
            System.out.println("   Votantes sin ACK:            " + votersWithoutACK);

            // Verificar archivos
            System.out.println();
            System.out.println("📁 VERIFICACIÓN DE ARCHIVOS:");

            File voteFile = new File("config/db/central-votes.csv");
            File ackFile = new File("config/db/central-citizen-acks.csv");

            System.out.println("   Archivo de votos:            " + (voteFile.exists() ? "✅ Existe" : "❌ No existe"));
            System.out.println("   Archivo de ACKs:             " + (ackFile.exists() ? "✅ Existe" : "❌ No existe"));

            if (voteFile.exists()) {
                System.out.println("   Tamaño archivo votos:        " + String.format("%.2f KB", voteFile.length() / 1024.0));
            }
            if (ackFile.exists()) {
                System.out.println("   Tamaño archivo ACKs:         " + String.format("%.2f KB", ackFile.length() / 1024.0));
            }

            // Resultado de integridad
            boolean integrityOK = (votersWithoutACK == 0) && (voteStats.totalVoters == ackStats.totalACKs);

            System.out.println();
            if (integrityOK) {
                System.out.println("✅ INTEGRIDAD: CORRECTA - Todos los datos están sincronizados");
            } else {
                System.out.println("⚠️  INTEGRIDAD: PROBLEMAS DETECTADOS");
                if (votersWithoutACK > 0) {
                    System.out.println("   - " + votersWithoutACK + " votantes sin ACK");
                }
                if (voteStats.totalVoters != ackStats.totalACKs) {
                    System.out.println("   - Desincronización entre votos (" + voteStats.totalVoters +
                            ") y ACKs (" + ackStats.totalACKs + ")");
                }
            }

        } catch (Exception e) {
            System.err.println("Error en verificación de integridad: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("═".repeat(70));
    }

    /**
     * Mostrar gráfico visual de distribución de votos
     */
    private void showVotingChart(java.util.List<java.util.Map.Entry<String, Integer>> sortedResults, int totalVotes) {
        if (totalVotes == 0) return;

        final int CHART_WIDTH = 40;

        for (java.util.Map.Entry<String, Integer> entry : sortedResults) {
            String candidate = formatCandidateName(entry.getKey());
            int votes = entry.getValue();
            double percentage = (double) votes / totalVotes * 100;

            // Calcular barras del gráfico
            int barLength = (int) (percentage / 100.0 * CHART_WIDTH);
            String bar = "█".repeat(Math.max(1, barLength)) +
                    "░".repeat(Math.max(0, CHART_WIDTH - barLength));

            // Truncar nombre si es muy largo
            String shortName = candidate.length() > 20 ?
                    candidate.substring(0, 17) + "..." : candidate;

            System.out.println(String.format("   %-20s |%s| %6.2f%%",
                    shortName, bar, percentage));
        }
    }

    /**
     * Comando administrativo para gestión de notificaciones
     */
    public void printNotificationStatus() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [" + serverName + "] === ESTADO DE NOTIFICACIONES ===");

        CandidateNotificationManager.getInstance().printConnectionStatus();

        System.out.println("\nCOMANDOS ADICIONALES:");
        System.out.println("  healthcheck   - Verificar conectividad de máquinas");
        System.out.println("  notify        - Forzar notificación de candidatos");
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    /**
     * Forzar notificación manual de candidatos
     */
    public void forceNotifyCandidates() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] 🔔 Forzando notificación de candidatos...");

        try {
            CandidateNotificationManager.getInstance().notifyCandidateUpdate(candidateManager);
            System.out.println("[" + timestamp + "] [" + serverName + "] ✅ Notificación forzada completada");
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] ❌ Error en notificación forzada: " + e.getMessage());
        }
    }

    /**
     * Health check de máquinas conectadas
     */
    public void healthCheckVotingMachines() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] 🏥 Ejecutando health check de máquinas...");

        CandidateNotificationManager.getInstance().healthCheck();
    }

    public void printACKStatus() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [" + serverName + "] === ESTADO DE ACK MANAGER ===");

        ackManager.printDebugInfo();
    }

    public void printDetailedDebugInfo() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("\n[" + timestamp + "] [" + serverName + "] === DEBUG DETALLADO ===");

        System.out.println("\n🔍 VOTE MANAGER DEBUG:");
        voteManager.printDebugInfo();

        System.out.println("\n🔍 ACK MANAGER DEBUG:");
        ackManager.printDebugInfo();

        System.out.println("\n🔍 CANDIDATE NOTIFICATION DEBUG:");
        CandidateNotificationManager.getInstance().printConnectionStatus();

        System.out.println("\n🔍 MÉTRICAS DE SISTEMA:");
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;

        System.out.println("   Memoria total:             " + totalMemory + " MB");
        System.out.println("   Memoria usada:             " + usedMemory + " MB");
        System.out.println("   Memoria libre:             " + freeMemory + " MB");
        System.out.println("   Threads activos:           " + Thread.activeCount());
    }

    public void clearStateForTesting() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + serverName + "] ⚠️  LIMPIANDO ESTADO COMPLETO...");

        try {
            ackManager.clearForTesting();
            voteManager.clearForTesting();

            System.out.println("[" + timestamp + "] [" + serverName + "] ✅ Estado limpiado completamente");
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + serverName + "] ❌ Error limpiando estado: " + e.getMessage());
        }
    }

    // NUEVO: Método específico para validar ciudadano
    @Override
    public boolean validateCitizen(String citizenId, com.zeroc.Ice.Current current) throws CentralServerUnavailableException {
        try {
            return citizenDAO.validateCitizen(citizenId != null ? citizenId.trim() : null);
        } catch (Exception e) {
            throw new CentralServerUnavailableException("Error validando ciudadano: " + e.getMessage(),
                    System.currentTimeMillis());
        }
    }
}
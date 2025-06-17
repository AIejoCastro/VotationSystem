import VotingStation.*;
import Proxy.*;
import Central.*;
import CandidateNotification.*;
import java.util.*;

public class VoteStationI implements VotingStation.VoteStation {
    private final VotingProxyPrx votingProxy;
    private final CentralVotationPrx centralProxy;
    private final String stationId;
    private final Map<Integer, String> candidateMapping = new HashMap<>();

    // AGREGAR: Cache de candidatos
    private volatile List<CandidateData> currentCandidates = new ArrayList<>();
    private volatile long lastUpdateTimestamp = 0;
    private final Object candidatesLock = new Object();

    public VoteStationI(VotingProxyPrx votingProxy, CentralVotationPrx centralProxy, String stationId) {
        this.votingProxy = votingProxy;
        this.centralProxy = centralProxy;
        this.stationId = stationId;

        // Mapeo simple de candidatos por defecto
        candidateMapping.put(1, "candidate001");
        candidateMapping.put(2, "candidate002");
        candidateMapping.put(3, "candidate003");
        candidateMapping.put(4, "candidate004");
        candidateMapping.put(5, "blank");
    }

    @Override
    public int vote(String document, int candidateId, com.zeroc.Ice.Current current) {
        // Validaciones básicas
        if (document == null || document.trim().isEmpty()) {
            return 1; // Error: documento inválido
        }

        synchronized (candidatesLock) {
            int maxCandidates = currentCandidates.isEmpty() ? 5 : currentCandidates.size();
            if (candidateId < 1 || candidateId > maxCandidates) {
                return 3; // Error: candidato inválido
            }
        }

        // Mapear candidateId a string
        String candidateString = candidateMapping.get(candidateId);
        if (candidateString == null) {
            return 4; // Error: candidato no encontrado
        }

        try {
            // Procesar voto a través del sistema
            String ackId = votingProxy.submitVote(document.trim(), candidateString);
            return 0; // Éxito

        } catch (VotingSystemUnavailableException e) {
            // Sistema no disponible pero voto guardado para procesamiento
            return 0; // Éxito (será procesado automáticamente)

        } catch (InvalidVoteException e) {
            // Verificar si es duplicado
            if (e.reason != null && (e.reason.toLowerCase().contains("already voted") ||
                    e.reason.toLowerCase().contains("ya votó") ||
                    e.reason.toLowerCase().contains("duplicado") ||
                    e.reason.contains("ACK:"))) {
                return 2; // Ciudadano ya votó
            }
            return 5; // Error: voto inválido

        } catch (Proxy.CitizenNotRegisteredException e) {
            return 3; // Ciudadano no registrado en base de datos

        } catch (Exception e) {
            // Verificar mensajes de error para casos especiales
            String errorMsg = e.getMessage();
            if (errorMsg != null) {
                if (errorMsg.contains("already voted") || errorMsg.contains("ya votó") ||
                        errorMsg.contains("duplicado")) {
                    return 2; // Ciudadano ya votó
                }

                if (errorMsg.contains("CITIZEN_NOT_REGISTERED") ||
                        errorMsg.contains("no está registrado") ||
                        errorMsg.contains("not registered")) {
                    return 3; // Ciudadano no registrado
                }
            }

            return 9; // Error interno del sistema
        }
    }

    @Override
    public String getStationStatus(com.zeroc.Ice.Current current) {
        try {
            return votingProxy.getSystemStatus();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String getCandidateList(com.zeroc.Ice.Current current) {
        synchronized (candidatesLock) {
            if (currentCandidates.isEmpty()) {
                return getDefaultCandidateList();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("CANDIDATOS:\n");

            int pos = 1;
            for (CandidateData candidate : currentCandidates) {
                if (!"blank".equals(candidate.candidateId)) {
                    sb.append(pos).append(") ").append(candidate.fullName)
                            .append(" - ").append(candidate.partyName).append("\n");
                    pos++;
                }
            }
            sb.append(pos).append(") VOTO EN BLANCO");
            return sb.toString();
        }
    }

    private String getDefaultCandidateList() {
        StringBuilder sb = new StringBuilder();
        sb.append("CANDIDATOS:\n");
        sb.append("1) Juan Pérez - Partido Azul\n");
        sb.append("2) María García - Partido Verde\n");
        sb.append("3) Carlos López - Partido Rojo\n");
        sb.append("4) Ana Martínez - Partido Amarillo\n");
        sb.append("5) VOTO EN BLANCO");
        return sb.toString();
    }

    @Override
    public boolean hasVoted(String document, com.zeroc.Ice.Current current) {
        if (document == null || document.trim().isEmpty()) {
            return false;
        }

        try {
            if (centralProxy != null) {
                return centralProxy.hasVoted(document.trim());
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void shutdown(com.zeroc.Ice.Current current) {
        current.adapter.getCommunicator().shutdown();
    }

    public void updateCandidates(List<CandidateData> newCandidates, long updateTimestamp) {
        synchronized (candidatesLock) {
            currentCandidates = new ArrayList<>(newCandidates);
            lastUpdateTimestamp = updateTimestamp;

            // Reconstruir mapeo dinámico
            candidateMapping.clear();
            int position = 1;

            for (CandidateData candidate : currentCandidates) {
                if (!"blank".equals(candidate.candidateId)) {
                    candidateMapping.put(position, candidate.candidateId);
                    position++;
                }
            }

            // Voto en blanco siempre en la última posición
            candidateMapping.put(position, "blank");
        }

        System.out.println("[VoteStation-" + stationId + "] Candidatos actualizados vía notificación");
    }
}
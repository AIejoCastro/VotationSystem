//
// VotationI optimizado para alta carga con componentes mejorados
//

import Demo.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class VotationI implements Votation
{
    private final String _name;
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public VotationI(String name)
    {
        _name = name;
        System.out.println("[" + _name + "] Servidor iniciado con componentes optimizados para alta carga");
    }

    @Override
    public void sayHello(com.zeroc.Ice.Current current)
    {
        System.out.println(_name + " says Hello World! (Optimizado para 1777 v/s)");
    }

    @Override
    public void shutdown(com.zeroc.Ice.Current current)
    {
        System.out.println(_name + " shutting down...");

        // Shutdown graceful de componentes optimizados
        try {
            VoteManager.getInstance().shutdown();
            ACKManager.getInstance().shutdown();
            System.out.println("[" + _name + "] Componentes optimizados terminados correctamente");
        } catch (Exception e) {
            System.err.println("[" + _name + "] Error en shutdown: " + e.getMessage());
        }

        current.adapter.getCommunicator().shutdown();
    }

    @Override
    public synchronized String sendVote(String citizenId, String candidateId, com.zeroc.Ice.Current current) throws AlreadyVotedException {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        if (citizenId == null || citizenId.trim().isEmpty() || candidateId == null || candidateId.trim().isEmpty()) {
            throw new RuntimeException("Parámetros inválidos");
        }

        String cleanCitizenId = citizenId.trim();
        String cleanCandidateId = candidateId.trim();

        // PASO 1: Verificación rápida con ACKManager optimizado
        ACKManager ackManager = ACKManager.getInstance();
        String existingACK = ackManager.getACK(cleanCitizenId);

        if (existingACK != null) {
            // Ciudadano ya tiene ACK - retornar el mismo
            AlreadyVotedException ex = new AlreadyVotedException();
            ex.ackId = existingACK;
            throw ex;
        }

        // PASO 2: Procesar voto con VoteManager optimizado
        VoteManager.VoteResult result = VoteManager.getInstance().receiveVote(cleanCitizenId, cleanCandidateId);

        if (result.success) {
            // VOTO VÁLIDO - generar ACK único
            String ackId = ackManager.getOrCreateACK(cleanCitizenId, _name);

            // Log solo cada 100 votos para reducir overhead
            if (VoteManager.getInstance().getStats().totalProcessed % 100 == 0) {
                System.out.println("[" + timestamp + "] [" + _name + "] Voto #" +
                        VoteManager.getInstance().getStats().totalProcessed + " - ACK: " + ackId);
            }

            return ackId;

        } else {
            // VOTO DUPLICADO - obtener ACK existente
            String ackId = ackManager.getOrCreateACK(cleanCitizenId, _name);

            AlreadyVotedException ex = new AlreadyVotedException();
            ex.ackId = ackId;
            throw ex;
        }
    }

    /**
     * Debug info optimizado
     */
    public void printDebugInfo() {
        ACKManager.getInstance().printDebugInfo();
        VoteManager.getInstance().printDebugInfo();
    }

    /**
     * Verificar ACK usando componente optimizado
     */
    public boolean hasACK(String citizenId) {
        return ACKManager.getInstance().hasACK(citizenId);
    }

    /**
     * Obtener ACK usando componente optimizado
     */
    public String getACK(String citizenId) {
        return ACKManager.getInstance().getACK(citizenId);
    }

    /**
     * Limpiar estado para testing
     */
    public static void clearACKState() {
        try {
            ACKManager.getInstance().clearForTesting();
            System.out.println("[VotationI] Estado optimizado limpiado para testing");
        } catch (Exception e) {
            System.err.println("[VotationI] Error limpiando estado: " + e.getMessage());
        }
    }

    /**
     * Estadísticas de performance
     */
    public VoteManager.VotingStats getVotingStats() {
        return VoteManager.getInstance().getStats();
    }

    /**
     * Estadísticas de ACK
     */
    public ACKManager.ACKStats getACKStats() {
        return ACKManager.getInstance().getStats();
    }

    /**
     * Estado completo del servidor
     */
    public void printServerStatus() {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + _name + "] === SERVER STATUS ===");

        VoteManager.VotingStats voteStats = getVotingStats();
        ACKManager.ACKStats ackStats = getACKStats();

        System.out.println("Server: " + _name);
        System.out.println("Vote Stats: " + voteStats.toString());
        System.out.println("ACK Stats: " + ackStats.toString());
        System.out.println("Throughput: " + String.format("%.2f", voteStats.throughputVotesPerSec) + " v/s");
        System.out.println("===============================");
    }
}
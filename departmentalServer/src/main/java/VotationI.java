//
// Copyright (c) ZeroC, Inc. All rights reserved.
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
    }

    @Override
    public void sayHello(com.zeroc.Ice.Current current)
    {
        System.out.println(_name + " says Hello World!");
    }

    @Override
    public void shutdown(com.zeroc.Ice.Current current)
    {
        System.out.println(_name + " shutting down...");
        current.adapter.getCommunicator().shutdown();
    }

    @Override
    public synchronized String sendVote(String citizenId, String candidateId, com.zeroc.Ice.Current current) throws AlreadyVotedException {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        System.out.println("[" + timestamp + "] [" + _name + "] Voto recibido: " + citizenId + " -> " + candidateId);

        // PASO 1: Verificar con ACKManager centralizado si el ciudadano ya tiene ACK
        ACKManager ackManager = ACKManager.getInstance();
        String existingACK = ackManager.getACK(citizenId);

        if (existingACK != null) {
            // El ciudadano ya tiene un ACK - SIEMPRE retornar el mismo
            System.out.println("[" + timestamp + "] [" + _name + "] CIUDADANO CONOCIDO - ACK centralizado: " + existingACK);
            AlreadyVotedException ex = new AlreadyVotedException();
            ex.ackId = existingACK;
            throw ex;
        }

        // PASO 2: Verificar con VoteManager si es un voto válido
        VoteManager.VoteResult result = VoteManager.getInstance().receiveVote(citizenId, candidateId);

        if (result.success) {
            // PASO 3A: Voto válido - obtener ACK único del ACKManager centralizado
            String ackId = ackManager.getOrCreateACK(citizenId, _name);

            System.out.println("[" + timestamp + "] [" + _name + "] ✅ PRIMER VOTO VÁLIDO - ACK centralizado: " + ackId);
            return ackId;

        } else {
            // PASO 3B: Voto duplicado según VoteManager - obtener ACK del ciudadano
            System.out.println("[" + timestamp + "] [" + _name + "] " + result.message);

            // Obtener o generar ACK centralizado para este ciudadano
            String ackId = ackManager.getOrCreateACK(citizenId, _name);

            System.out.println("[" + timestamp + "] [" + _name + "] ✅ VOTO DUPLICADO - ACK centralizado: " + ackId);

            AlreadyVotedException ex = new AlreadyVotedException();
            ex.ackId = ackId;
            throw ex;
        }
    }

    /**
     * Método para debugging
     */
    public void printACKDebugInfo() {
        ACKManager.getInstance().printDebugInfo();
    }

    /**
     * Verificar si un ciudadano tiene ACK (usando ACKManager centralizado)
     */
    public boolean hasACK(String citizenId) {
        return ACKManager.getInstance().hasACK(citizenId);
    }

    /**
     * Obtener ACK de un ciudadano (usando ACKManager centralizado)
     */
    public String getACK(String citizenId) {
        return ACKManager.getInstance().getACK(citizenId);
    }

    /**
     * Limpiar estado para testing
     */
    public static void clearACKState() {
        ACKManager.getInstance().clearForTesting();
        System.out.println("[VotationI] Estado de ACKs centralizados limpiado para testing");
    }

    /**
     * Método para obtener estadísticas (útil para debugging)
     */
    public VoteManager.VotingStats getVotingStats() {
        return VoteManager.getInstance().getStats();
    }
}
//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

import Demo.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class VotationI implements Votation
{
    private final String _name;

    // Mapa para almacenar ACKs de votos (incluyendo duplicados)
    private static final ConcurrentHashMap<String, String> voteACKs = new ConcurrentHashMap<>();
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
    public String sendVote(String citizenId, String candidateId, com.zeroc.Ice.Current current) throws AlreadyVotedException {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String voteKey = citizenId + "|" + candidateId;

        System.out.println("[" + timestamp + "] [" + _name + "] Voto recibido: " + citizenId + " -> " + candidateId);

        // Verificar si ya existe un ACK para esta combinación exacta
        String exactMatchACK = voteACKs.get(voteKey);
        if (exactMatchACK != null) {
            System.out.println("[" + timestamp + "] [" + _name + "] DUPLICADO EXACTO - Retornando ACK: " + exactMatchACK);
            AlreadyVotedException ex = new AlreadyVotedException();
            ex.ackId = exactMatchACK;
            throw ex;
        }

        // Procesar voto con VoteManager (incluye validación de duplicados)
        VoteManager.VoteResult result = VoteManager.getInstance().receiveVote(citizenId, candidateId);

        if (!result.success) {
            // El ciudadano ya votó (por este u otro candidato)
            System.out.println("[" + timestamp + "] [" + _name + "] " + result.message);

            // Buscar ACK existente para el voto original del ciudadano
            String existingVoteKey = citizenId + "|" + result.candidateId;
            String existingACK = voteACKs.get(existingVoteKey);

            if (existingACK == null) {
                // Generar ACK para el voto original si no existe
                existingACK = "ACK-ORIG-" + _name + "-" + UUID.randomUUID().toString().substring(0, 8);
                voteACKs.put(existingVoteKey, existingACK);
                System.out.println("[" + timestamp + "] [" + _name + "] ACK generado para voto original: " + existingACK);
            }

            AlreadyVotedException ex = new AlreadyVotedException();
            ex.ackId = existingACK;
            throw ex;
        }

        // Voto exitoso - generar ACK único
        String ackId = "ACK-NEW-" + _name + "-" + UUID.randomUUID().toString().substring(0, 8);
        voteACKs.put(voteKey, ackId);

        System.out.println("[" + timestamp + "] [" + _name + "] ✅ Voto procesado exitosamente");
        System.out.println("[" + timestamp + "] [" + _name + "] ✅ ACK generado: " + ackId);

        return ackId;
    }

    /**
     * Método para obtener estadísticas (útil para debugging)
     */
    public VoteManager.VotingStats getVotingStats() {
        return VoteManager.getInstance().getStats();
    }
}
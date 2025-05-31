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

        // Verificar si ya existe un ACK para este voto
        String existingACK = voteACKs.get(voteKey);
        if (existingACK != null) {
            System.out.println("[" + timestamp + "] [" + _name + "] DUPLICADO detectado - Retornando ACK existente: " + existingACK);
            AlreadyVotedException ex = new AlreadyVotedException();
            ex.ackId = existingACK;
            throw ex;
        }

        // Procesar voto con VoteManager
        boolean success = VoteManager.getInstance().receiveVote(citizenId, candidateId);
        if (!success) {
            // Ya votó según VoteManager
            existingACK = voteACKs.get(voteKey);
            if (existingACK == null) {
                // Generar ACK para voto duplicado
                existingACK = "ACK-DUP-" + UUID.randomUUID().toString().substring(0, 8);
                voteACKs.put(voteKey, existingACK);
            }
            System.out.println("[" + timestamp + "] [" + _name + "] Ciudadano ya votó - ACK: " + existingACK);
            AlreadyVotedException ex = new AlreadyVotedException();
            ex.ackId = existingACK;
            throw ex;
        }

        // Generar ACK único para voto exitoso
        String ackId = "ACK-" + _name + "-" + UUID.randomUUID().toString().substring(0, 8);
        voteACKs.put(voteKey, ackId);

        System.out.println("[" + timestamp + "] [" + _name + "] Voto procesado exitosamente");
        System.out.println("[" + timestamp + "] [" + _name + "] ACK generado: " + ackId);

        return ackId;
    }
}
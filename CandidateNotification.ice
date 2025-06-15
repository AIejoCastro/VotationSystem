//
// Interface para notificaciones de candidatos entre CentralServer y VotingMachine
//

#pragma once
module CandidateNotification
{
    // Datos de un candidato
    struct CandidateData {
        string candidateId;
        string firstName;
        string lastName;
        string fullName;
        int position;
        string photo;
        string biography;
        bool isActive;
        string partyId;
        string partyName;
        string partyColor;
        string partyIdeology;
        string partyLogo;
    };

    // Lista de candidatos
    sequence<CandidateData> CandidateList;

    // Notificación de actualización
    struct CandidateUpdateNotification {
        long updateTimestamp;
        CandidateList candidates;
        int totalCandidates;
    };

    // Respuesta con lista de candidatos
    struct CandidateListResponse {
        CandidateList candidates;
        int totalCandidates;
        long updateTimestamp;
    };

    // Callback para VotingMachine recibir notificaciones
    // Usando "idempotent" para evitar generación automática de métodos async
    interface VotingMachineCallback {
        idempotent void onCandidatesUpdated(CandidateUpdateNotification notification);
    };
}
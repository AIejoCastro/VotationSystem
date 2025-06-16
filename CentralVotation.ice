//
// Interface extendida para comunicación DepartmentalServer -> CentralServer
// Con soporte para notificaciones de candidatos
//

#pragma once

#include "CandidateNotification.ice"

module Central
{
    exception AlreadyVotedCentralException {
        string ackId; // ACK del voto duplicado desde el servidor central
        string citizenId;
        string existingCandidate;
    };

    exception CentralServerUnavailableException {
        string reason;
        long timestamp;
    };

    exception CitizenNotRegisteredException {
        string citizenId;
        string message;
    };

    interface CentralVotation
    {
        // Operaciones principales de votación
        string processVote(string citizenId, string candidateId, string departmentalServerId)
            throws AlreadyVotedCentralException, CitizenNotRegisteredException, CentralServerUnavailableException;

        bool validateCitizen(string citizenId) throws CentralServerUnavailableException;

        string getExistingACK(string citizenId) throws CentralServerUnavailableException;

        bool hasVoted(string citizenId) throws CentralServerUnavailableException;

        // Operaciones de consulta
        string getVoteForCitizen(string citizenId) throws CentralServerUnavailableException;

        int getTotalVotesCount() throws CentralServerUnavailableException;

        int getUniqueVotersCount() throws CentralServerUnavailableException;

        // Operaciones de gestión de candidatos
        void registerVotingMachine(string machineId, CandidateNotification::VotingMachineCallback* callback)
            throws CentralServerUnavailableException;

        CandidateNotification::CandidateListResponse getCurrentCandidates()
            throws CentralServerUnavailableException;

        void unregisterVotingMachine(string machineId)
            throws CentralServerUnavailableException;

        // Operaciones administrativas
        void ping();

        string getServerStatus();

        void shutdown();
    }
}
//
// Interface para comunicación DepartmentalServer -> CentralServer
// El servidor central maneja toda la lógica de base de datos
//

#pragma once
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

    interface CentralVotation
    {
        // Operaciones principales
        string processVote(string citizenId, string candidateId, string departmentalServerId)
            throws AlreadyVotedCentralException, CentralServerUnavailableException;

        string getExistingACK(string citizenId) throws CentralServerUnavailableException;

        bool hasVoted(string citizenId) throws CentralServerUnavailableException;

        // Operaciones de consulta
        string getVoteForCitizen(string citizenId) throws CentralServerUnavailableException;

        int getTotalVotesCount() throws CentralServerUnavailableException;

        int getUniqueVotersCount() throws CentralServerUnavailableException;

        // Operaciones administrativas
        void ping();

        string getServerStatus();

        void shutdown();
    }
}
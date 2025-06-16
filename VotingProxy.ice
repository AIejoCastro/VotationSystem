//
// Interface para comunicación VotingMachine -> VotingSite
//

#pragma once
module Proxy
{
    exception VotingSystemUnavailableException {
        string reason;
    };

    exception InvalidVoteException {
        string reason;
    };

    // NUEVA: Excepción para ciudadano no registrado
    exception CitizenNotRegisteredException {
        string citizenId;
        string message;
    };

    interface VotingProxy
    {
        // ACTUALIZADO: Con nueva excepción
        string submitVote(string citizenId, string candidateId)
            throws VotingSystemUnavailableException, InvalidVoteException, CitizenNotRegisteredException;

        string getSystemStatus();
        int getPendingVotesCount();
    }
}
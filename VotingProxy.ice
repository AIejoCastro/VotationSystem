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

    interface VotingProxy
    {
        string submitVote(string citizenId, string candidateId) throws VotingSystemUnavailableException, InvalidVoteException;
        string getSystemStatus();
        int getPendingVotesCount();
    }
}
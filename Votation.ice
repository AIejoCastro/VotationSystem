//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

#pragma once
module Demo
{
    exception AlreadyVotedException {
        string ackId; // ID del voto duplicado
    };

    exception CitizenNotRegisteredException {
        string citizenId;
        string message;
    };

    interface Votation
    {
        idempotent void sayHello();
        void shutdown();
        string sendVote(string citizenId, string candidateId) throws AlreadyVotedException, CitizenNotRegisteredException;
    }
}
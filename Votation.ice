//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

#pragma once
module Demo
{
    exception AlreadyVotedException {};

    interface Votation
    {
        idempotent void sayHello();
        void shutdown();
        void sendVote(string citizenId, string candidateId) throws AlreadyVotedException;
    }
}
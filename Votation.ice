//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

#pragma once
module Demo
{
    interface Votation
    {
        idempotent void sayHello();
        void shutdown();
    }
}
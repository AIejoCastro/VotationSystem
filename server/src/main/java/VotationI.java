//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

import Demo.*;

public class VotationI implements Votation
{
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

    private final String _name;
}
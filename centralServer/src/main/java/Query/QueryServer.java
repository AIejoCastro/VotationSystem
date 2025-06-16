package Query;

import com.zeroc.Ice.*;
import com.zeroc.Ice.Object;

public class QueryServer {
    public static void main(String[] args) {
        try (Communicator communicator = Util.initialize(args, "config.cfg")) {
            ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints(
                    "QueryAdapter", "tcp -h 0.0.0.0 -p 8888"
            ); //Escucha desde cualquier IP

            QueryStationI servant = new QueryStationI();
            adapter.add((Object) servant, Util.stringToIdentity("QueryStation"));

            adapter.activate();
            System.out.println("Servidor de consultas iniciado en puerto 8888 🔥");
            communicator.waitForShutdown();
        }
    }
}

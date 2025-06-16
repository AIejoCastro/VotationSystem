package Query;

import com.zeroc.Ice.*;
import com.zeroc.Ice.Exception;
import com.zeroc.Ice.Object;

public class QueryServer {
    public static void main(String[] args) {
        try (Communicator communicator = Util.initialize(args, "config.cfg")) {

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                QueryStationI.shutdown();
            }));

            ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints(
                    "QueryAdapter", "tcp -h 0.0.0.0 -p 8888"
            );

            QueryStationI servant = new QueryStationI();
            adapter.add((Object) servant, Util.stringToIdentity("QueryStation"));

            adapter.activate();

            communicator.waitForShutdown();

        } catch (Exception e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
        }
    }
}
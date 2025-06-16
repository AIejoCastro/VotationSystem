import Query.QueryStationPrx;
import com.zeroc.Ice.*;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        try (Communicator communicator = Util.initialize(args)) {
            //ObjectPrx base = communicator.stringToProxy("QueryStation:default -h 10.147.17.101 -p 8888");
            ObjectPrx base = communicator.stringToProxy("QueryStation:default -h localhost -p 8888");
            QueryStationPrx proxy = QueryStationPrx.checkedCast(base);

            if (proxy == null) {
                System.err.println(" No se pudo conectar con el servidor QueryStation.");
                return;
            }

            Scanner scanner = new Scanner(System.in);
            System.out.print("Ingrese su número de documento: ");
            String doc = scanner.nextLine();

            String resultado = proxy.query(doc);
            if (resultado == null) {
                System.out.println("No se encontró al ciudadano.");
            } else {
                System.out.println(resultado);
            }
        }
    }
}

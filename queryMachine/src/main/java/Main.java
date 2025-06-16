import Query.QueryStationPrx;
import com.zeroc.Ice.*;
import com.zeroc.Ice.Exception;

import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        try (Communicator communicator = Util.initialize(args)) {
            //ObjectPrx base = communicator.stringToProxy("QueryStation:default -h 10.147.17.101 -p 8888");
            ObjectPrx base = communicator.stringToProxy("QueryStation:default -h localhost -p 8888");
            QueryStationPrx proxy = QueryStationPrx.checkedCast(base);

            if (proxy == null) {
                return;
            }

            Scanner scanner = new Scanner(System.in);
            String documento;

            // Bucle continuo hasta que el usuario escriba "exit"
            while (true) {
                System.out.print("\nIngrese número de documento: ");
                documento = scanner.nextLine().trim();

                // Verificar si el usuario quiere salir
                if ("exit".equalsIgnoreCase(documento)) {
                    break;
                }

                // Validar que no esté vacío
                if (documento.isEmpty()) {
                    System.out.println("Por favor ingrese un número de documento válido");
                    continue;
                }

                // Validar formato básico (solo números)
                if (!documento.matches("\\d+")) {
                    System.out.println("El documento debe contener solo números");
                    continue;
                }

                try {
                    String resultado = proxy.query(documento);

                    if (resultado == null) {
                        System.out.println("   El documento " + documento + " no está registrado en el sistema");
                    } else {
                        System.out.println(resultado);
                    }

                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }

            }

            scanner.close();

        } catch (Exception e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
        }
    }
}
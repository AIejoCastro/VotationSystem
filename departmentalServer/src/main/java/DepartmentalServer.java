//
// DepartmentalServer - MODIFICADO para actuar como balanceador hacia CentralServer
// CON DepartmentalReliableMessaging para comunicación confiable
//

public class DepartmentalServer
{
    public static void main(String[] args)
    {
        int status = 0;
        java.util.List<String> extraArgs = new java.util.ArrayList<String>();

        //
        // Try with resources block - communicator is automatically destroyed
        // at the end of this try block
        //
        try(com.zeroc.Ice.Communicator communicator = com.zeroc.Ice.Util.initialize(args, extraArgs))
        {
            communicator.getProperties().setProperty("Ice.Default.Package", "com.zeroc.demos.IceGrid.simple");

            // Cargar configuración del DepartmentalReliableMessaging
            try {
                communicator.getProperties().load("departmentalReliableMessaging/src/main/resources/config.departmentalReliableMessaging");
                System.out.println("[DepartmentalServer] Configuración DepartmentalReliableMessaging cargada correctamente");
            } catch (Exception e) {
                System.out.println("[DepartmentalServer] Usando configuración por defecto para DepartmentalReliableMessaging");
            }

            //
            // Install shutdown hook to (also) destroy communicator during JVM shutdown.
            // This ensures the communicator gets destroyed when the user interrupts the application with Ctrl-C.
            //
            Runtime.getRuntime().addShutdownHook(new Thread(() -> communicator.destroy()));

            if(!extraArgs.isEmpty())
            {
                System.err.println("too many arguments");
                status = 1;
            }
            else
            {
                com.zeroc.Ice.ObjectAdapter adapter = communicator.createObjectAdapter("Votation");
                com.zeroc.Ice.Properties properties = communicator.getProperties();
                com.zeroc.Ice.Identity id = com.zeroc.Ice.Util.stringToIdentity(properties.getProperty("Identity"));

                // CAMBIO PRINCIPAL: Crear VotationI que actúa como proxy hacia CentralServer con reliable messaging
                VotationI votationServant = new VotationI(properties.getProperty("Ice.ProgramName"));
                votationServant.setCommunicator(communicator); // Pasar el communicator

                adapter.add(votationServant, id);
                adapter.activate();

                communicator.waitForShutdown();
            }
        }

        System.exit(status);
    }
}
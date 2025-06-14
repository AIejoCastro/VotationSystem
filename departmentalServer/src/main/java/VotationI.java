//
// VotationI - MODIFICADO para actuar como proxy/balanceador hacia CentralServer
// YA NO maneja ACKs ni votos directamente, solo reenvía al servidor central
//

import Demo.*;
import Central.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class VotationI implements Votation
{
    private final String departmentalServerName;
    private CentralVotationPrx centralServerProxy;
    private com.zeroc.Ice.Communicator communicator;
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public VotationI(String name)
    {
        this.departmentalServerName = name;

        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Servidor departamental iniciado como PROXY");
        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Configurado para conectar al servidor central...");
    }

    // Método para establecer el communicator (llamado desde DepartmentalServer)
    public void setCommunicator(com.zeroc.Ice.Communicator communicator) {
        this.communicator = communicator;

        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Conectando al servidor central...");

        // Obtener proxy al servidor central
        this.centralServerProxy = getCentralServerProxy();

        if (centralServerProxy != null) {
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] ✅ Conectado al servidor central");
        } else {
            System.err.println("[" + timestamp + "] [" + departmentalServerName + "] ❌ ERROR: No se pudo conectar al servidor central");
        }
    }

    @Override
    public void sayHello(com.zeroc.Ice.Current current)
    {
        System.out.println(departmentalServerName + " says Hello World! (Proxy hacia CentralServer)");
    }

    @Override
    public void shutdown(com.zeroc.Ice.Current current)
    {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Shutting down proxy...");

        current.adapter.getCommunicator().shutdown();
    }

    @Override
    public String sendVote(String citizenId, String candidateId, com.zeroc.Ice.Current current) throws AlreadyVotedException {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        if (citizenId == null || citizenId.trim().isEmpty() || candidateId == null || candidateId.trim().isEmpty()) {
            throw new RuntimeException("Parámetros inválidos");
        }

        String cleanCitizenId = citizenId.trim();
        String cleanCandidateId = candidateId.trim();

        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Reenviando voto al servidor central");
        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Voto: " + cleanCitizenId + " -> " + cleanCandidateId);

        // Verificar que tenemos conexión al servidor central
        if (centralServerProxy == null) {
            centralServerProxy = getCentralServerProxy();
            if (centralServerProxy == null) {
                throw new RuntimeException("Servidor central no disponible");
            }
        }

        try {
            // REENVAR AL SERVIDOR CENTRAL
            String ackId = centralServerProxy.processVote(cleanCitizenId, cleanCandidateId, departmentalServerName);

            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] ACK recibido del servidor central: " + ackId);
            return ackId;

        } catch (AlreadyVotedCentralException centralEx) {
            // Convertir excepción central a excepción departamental
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Duplicado detectado en servidor central");
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Ciudadano " + centralEx.citizenId +
                    " ya votó por " + centralEx.existingCandidate + " (ACK: " + centralEx.ackId + ")");

            AlreadyVotedException ex = new AlreadyVotedException();
            ex.ackId = centralEx.ackId;
            throw ex;

        } catch (CentralServerUnavailableException centralEx) {
            // Manejar indisponibilidad del servidor central
            System.err.println("[" + timestamp + "] [" + departmentalServerName + "] Servidor central no disponible: " + centralEx.reason);

            // Intentar reconectar
            CentralVotationPrx newProxy = getCentralServerProxy();
            if (newProxy != null) {
                this.centralServerProxy = newProxy;
                System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Reconexión exitosa, reintentando voto...");
                try {
                    String ackId = newProxy.processVote(cleanCitizenId, cleanCandidateId, departmentalServerName);
                    return ackId;
                } catch (Exception retryEx) {
                    System.err.println("[" + timestamp + "] [" + departmentalServerName + "] Fallo en reintento: " + retryEx.getMessage());
                }
            }

            throw new RuntimeException("Servidor central no disponible temporalmente");

        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + departmentalServerName + "] Error comunicándose con servidor central: " + e.getMessage());
            throw new RuntimeException("Error interno del sistema de votación");
        }
    }

    /**
     * Obtener proxy al servidor central con manejo de errores
     */
    private CentralVotationPrx getCentralServerProxy() {
        if (communicator == null) {
            System.err.println("[" + departmentalServerName + "] Communicator no disponible");
            return null;
        }

        try {
            // Conectar directamente al servidor central en puerto 8888
            String centralServerEndpoint = "CentralVotation:default -h localhost -p 8888";

            com.zeroc.Ice.ObjectPrx baseProxy = communicator.stringToProxy(centralServerEndpoint);
            CentralVotationPrx proxy = CentralVotationPrx.checkedCast(baseProxy);

            if (proxy != null) {
                // Verificar conectividad con ping
                proxy.ping();
                return proxy;
            } else {
                System.err.println("[" + departmentalServerName + "] No se pudo hacer cast a CentralVotationPrx");
                return null;
            }

        } catch (Exception e) {
            System.err.println("[" + departmentalServerName + "] Error conectando al servidor central: " + e.getMessage());
            return null;
        }
    }

    /**
     * Verificar estado del servidor central
     */
    public String getCentralServerStatus() {
        try {
            if (centralServerProxy != null) {
                return centralServerProxy.getServerStatus();
            } else {
                return "DESCONECTADO del servidor central";
            }
        } catch (Exception e) {
            return "ERROR consultando servidor central: " + e.getMessage();
        }
    }

    /**
     * Obtener estadísticas del servidor central
     */
    public void printCentralServerStats() {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        try {
            if (centralServerProxy != null) {
                String status = centralServerProxy.getServerStatus();
                int totalVotes = centralServerProxy.getTotalVotesCount();
                int uniqueVoters = centralServerProxy.getUniqueVotersCount();

                System.out.println("\n[" + timestamp + "] [" + departmentalServerName + "] === ESTADO DEL SERVIDOR CENTRAL ===");
                System.out.println("Estado: " + status);
                System.out.println("Total de votos: " + totalVotes);
                System.out.println("Votantes únicos: " + uniqueVoters);
                System.out.println("===============================");
            } else {
                System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Sin conexión al servidor central");
            }
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + departmentalServerName + "] Error consultando servidor central: " + e.getMessage());
        }
    }

    /**
     * DEPRECATED: Métodos que ya no se usan (movidos a CentralServer)
     */
    @Deprecated
    public void printDebugInfo() {
        System.out.println("[" + departmentalServerName + "] DEBUG: Este servidor actúa como proxy hacia CentralServer");
        printCentralServerStats();
    }

    @Deprecated
    public boolean hasACK(String citizenId) {
        try {
            return centralServerProxy != null && centralServerProxy.hasVoted(citizenId);
        } catch (Exception e) {
            System.err.println("Error verificando voto en servidor central: " + e.getMessage());
            return false;
        }
    }

    @Deprecated
    public String getACK(String citizenId) {
        try {
            return centralServerProxy != null ? centralServerProxy.getExistingACK(citizenId) : null;
        } catch (Exception e) {
            System.err.println("Error obteniendo ACK del servidor central: " + e.getMessage());
            return null;
        }
    }

    @Deprecated
    public static void clearACKState() {
        System.out.println("[VotationI] DEPRECATED: clearACKState() - usar CentralServer directamente");
    }
}
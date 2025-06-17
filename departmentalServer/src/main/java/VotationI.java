//
// VotationI - MODIFICADO para actuar como proxy/balanceador hacia CentralServer
// CON DepartmentalReliableMessaging para comunicación confiable
//

import Central.CitizenNotRegisteredException;
import Demo.*;
import Central.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class VotationI implements Votation
{
    private final String departmentalServerName;
    private CentralVotationPrx centralServerProxy;
    private com.zeroc.Ice.Communicator communicator;
    private Object messagingService; // Usar Object temporalmente para evitar errores de compilación
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public VotationI(String name)
    {
        this.departmentalServerName = name;

        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Servidor departamental iniciado como PROXY");
        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Configurado para conectar al servidor central con reliable messaging...");
    }

    // Método para establecer el communicator (llamado desde DepartmentalServer)
    public void setCommunicator(com.zeroc.Ice.Communicator communicator) {
        this.communicator = communicator;

        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Inicializando DepartmentalReliableMessaging...");

        // Inicializar reliable messaging service
        try {
            // Usar reflexión para evitar problemas de compilación
            Class<?> serviceClass = Class.forName("DepartmentalReliableMessagingService");
            java.lang.reflect.Method getInstance = serviceClass.getMethod("getInstance");
            this.messagingService = getInstance.invoke(null);

            java.lang.reflect.Method initialize = serviceClass.getMethod("initialize", com.zeroc.Ice.Communicator.class);
            initialize.invoke(this.messagingService, communicator);

            System.out.println("[" + timestamp + "] [" + departmentalServerName + "]  DepartmentalReliableMessaging inicializado");
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + departmentalServerName + "] Error inicializando reliable messaging: " + e.getMessage());
            this.messagingService = null;
        }

        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Conectando al servidor central...");

        // Obtener proxy al servidor central
        this.centralServerProxy = getCentralServerProxy();

        if (centralServerProxy != null) {
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "]  Conectado al servidor central");
        } else {
            System.err.println("[" + timestamp + "] [" + departmentalServerName + "] No se pudo conectar al servidor central - Reliable messaging activo");
        }
    }

    @Override
    public void sayHello(com.zeroc.Ice.Current current)
    {
        System.out.println(departmentalServerName + " says Hello World! (Proxy hacia CentralServer con Reliable Messaging)");
    }

    @Override
    public void shutdown(com.zeroc.Ice.Current current)
    {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Shutting down proxy...");

        // Shutdown del reliable messaging
        if (messagingService != null) {
            try {
                java.lang.reflect.Method shutdown = messagingService.getClass().getMethod("shutdown");
                shutdown.invoke(messagingService);
            } catch (Exception e) {
                System.err.println("[" + timestamp + "] [" + departmentalServerName + "] Error en shutdown de reliable messaging: " + e.getMessage());
            }
        }

        current.adapter.getCommunicator().shutdown();
    }

    @Override
    public String sendVote(String citizenId, String candidateId, com.zeroc.Ice.Current current) throws AlreadyVotedException, Demo.CitizenNotRegisteredException {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        if (citizenId == null || citizenId.trim().isEmpty() || candidateId == null || candidateId.trim().isEmpty()) {
            throw new RuntimeException("Parametros invalidos");
        }

        String cleanCitizenId = citizenId.trim();
        String cleanCandidateId = candidateId.trim();

        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Procesando voto hacia CentralServer");
        System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Voto: " + cleanCitizenId + " -> " + cleanCandidateId);

        // Verificar que tenemos conexión al servidor central
        if (centralServerProxy == null) {
            centralServerProxy = getCentralServerProxy();
        }

        try {
            // INTENTAR ENVIO DIRECTO AL CENTRALSERVER CON VALIDACIÓN
            if (centralServerProxy != null) {
                long startTime = System.currentTimeMillis();
                String ackId = centralServerProxy.processVote(cleanCitizenId, cleanCandidateId, departmentalServerName);
                long latency = System.currentTimeMillis() - startTime;

                System.out.println("[" + timestamp + "] [" + departmentalServerName + "] ACK recibido del servidor central: " + ackId);

                // Confirmar en reliable messaging si está activo
                if (messagingService != null) {
                    try {
                        String voteKey = cleanCitizenId + "|" + cleanCandidateId + "|" + departmentalServerName;
                        java.lang.reflect.Method confirmVoteACK = messagingService.getClass().getMethod("confirmVoteACK", String.class, String.class, long.class);
                        confirmVoteACK.invoke(messagingService, voteKey, ackId, latency);
                    } catch (Exception ex) {
                        // Ignorar errores de confirmación
                    }
                }

                return ackId;
            } else {
                throw new CentralServerUnavailableException("CentralServer no disponible", System.currentTimeMillis());
            }

        } catch (AlreadyVotedCentralException centralEx) {
            // Convertir excepción central a excepción departmental
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Duplicado detectado en servidor central");
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Ciudadano " + centralEx.citizenId +
                    " ya votó por " + centralEx.existingCandidate + " (ACK: " + centralEx.ackId + ")");

            // Confirmar en reliable messaging
            if (messagingService != null) {
                try {
                    String voteKey = cleanCitizenId + "|" + cleanCandidateId + "|" + departmentalServerName;
                    java.lang.reflect.Method confirmVoteACK = messagingService.getClass().getMethod("confirmVoteACK", String.class, String.class, long.class);
                    confirmVoteACK.invoke(messagingService, voteKey, centralEx.ackId, 0L);
                } catch (Exception ex) {
                    // Ignorar errores de confirmación
                }
            }

            AlreadyVotedException ex = new AlreadyVotedException();
            ex.ackId = centralEx.ackId;
            throw ex;

        } catch (CitizenNotRegisteredException citizenEx) {
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] ❌ Ciudadano NO registrado: " + citizenEx.citizenId);
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Mensaje: " + citizenEx.message);

            // CREAR Y LANZAR LA EXCEPCIÓN CORRECTA DEL MÓDULO PROXY
            Demo.CitizenNotRegisteredException ex = new Demo.CitizenNotRegisteredException();
            ex.citizenId = citizenEx.citizenId;
            ex.message = citizenEx.message;
            throw ex;

        } catch (CentralServerUnavailableException centralEx) {
            // ACTIVAR RELIABLE MESSAGING
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] CentralServer no disponible: " + centralEx.reason);
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Activando DepartmentalReliableMessaging...");

            return handleOfflineVote(cleanCitizenId, cleanCandidateId, centralEx.reason);

        } catch (com.zeroc.Ice.LocalException localEx) {
            // ERROR DE CONEXIÓN - ACTIVAR RELIABLE MESSAGING
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Error de conexión con CentralServer: " + localEx.getMessage());
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Activando DepartmentalReliableMessaging...");

            // Resetear proxy
            centralServerProxy = null;

            return handleOfflineVote(cleanCitizenId, cleanCandidateId, "Error de conexión: " + localEx.getMessage());

        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + departmentalServerName + "] Error inesperado: " + e.getMessage());

            // Verificar si es error de ciudadano no registrado
            if (e.getMessage() != null && e.getMessage().startsWith("CITIZEN_NOT_REGISTERED:")) {
                System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Reintentando en modo offline para ciudadano no registrado");
                return handleOfflineVote(cleanCitizenId, cleanCandidateId, e.getMessage());
            }

            return handleOfflineVote(cleanCitizenId, cleanCandidateId, "Error inesperado: " + e.getMessage());
        }
    }

    /**
     * Manejar voto offline usando DepartmentalReliableMessaging
     */
    private String handleOfflineVote(String citizenId, String candidateId, String reason) throws AlreadyVotedException {
        String timestamp = LocalDateTime.now().format(timeFormatter);

        if (messagingService == null) {
            throw new RuntimeException("Servidor central no disponible y reliable messaging no inicializado");
        }

        try {
            // Guardar voto para procesamiento garantizado usando reflexión
            java.lang.reflect.Method storeOfflineVote = messagingService.getClass().getMethod("storeOfflineVoteWithACK", String.class, String.class, String.class);
            String voteKey = (String) storeOfflineVote.invoke(messagingService, citizenId, candidateId, departmentalServerName);

            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Voto guardado en DepartmentalReliableMessaging");
            System.out.println("[" + timestamp + "] [" + departmentalServerName + "] Será procesado automáticamente cuando CentralServer esté disponible");

            // Generar ACK temporal departamental
            String tempACK = "DEPT-TEMP-" + departmentalServerName.substring(Math.max(0, departmentalServerName.length()-2)) +
                    "-" + Long.toHexString(System.currentTimeMillis()).toUpperCase();

            return tempACK;

        } catch (Exception e) {
            System.err.println("[" + timestamp + "] [" + departmentalServerName + "] Error crítico en reliable messaging: " + e.getMessage());
            throw new RuntimeException("Sistema temporalmente no disponible");
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
            // No loggear error aqui - es normal durante indisponibilidad
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

        // Mostrar estado del reliable messaging
        if (messagingService != null) {
            try {
                java.lang.reflect.Method printStatus = messagingService.getClass().getMethod("printStatus");
                printStatus.invoke(messagingService);
            } catch (Exception e) {
                System.err.println("[" + timestamp + "] [" + departmentalServerName + "] Error consultando reliable messaging: " + e.getMessage());
            }
        }
    }

    /**
     * DEPRECATED: Métodos que ya no se usan (movidos a CentralServer)
     */
    @Deprecated
    public void printDebugInfo() {
        System.out.println("[" + departmentalServerName + "] DEBUG: Este servidor actúa como proxy hacia CentralServer con DepartmentalReliableMessaging");
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
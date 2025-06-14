package Query;

public class QueryStationI implements QueryStation {

    @Override
    public String query(String document, com.zeroc.Ice.Current current) {
        // Datos de prueba - después conectaremos a BD real
        if ("12345678".equals(document)) {
            return "Usted debe votar en Plaza Ani Segarra ubicado en Callejon Paco Larranaga 85 Puerta 7, Ceuta, 13282 en ABEJORRAL, ANTIOQUIA en la mesa 1.";
        } else if ("87654321".equals(document)) {
            return "Usted debe votar en Centro Cultural ubicado en Calle 50 #30-20 en MEDELLÍN, ANTIOQUIA en la mesa 5.";
        } else {
            return null; // No encontrado
        }
    }
}
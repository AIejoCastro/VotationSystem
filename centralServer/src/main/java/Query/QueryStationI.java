package Query;

import java.sql.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class QueryStationI implements QueryStation {

    private static final HikariDataSource dataSource;

    static {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5433/votacion");
        config.setUsername("admin");
        config.setPassword("123");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(3000);
        config.setIdleTimeout(30000);

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        dataSource = new HikariDataSource(config);
    }

    @Override
    public String query(String document, com.zeroc.Ice.Current current) {
        if (document == null || document.trim().isEmpty()) {
            return null;
        }

        String documento = document.trim();

        String sql = "SELECT " +
                "c.nombre, " +
                "c.apellido, " +
                "c.mesa_id, " +
                "mv.consecutive as mesa_consecutivo, " +
                "pv.nombre as puesto_nombre, " +
                "pv.direccion as puesto_direccion, " +
                "mun.nombre as municipio_nombre, " +
                "dep.nombre as departamento_nombre " +
                "FROM ciudadano c " +
                "INNER JOIN mesa_votacion mv ON c.mesa_id = mv.id " +
                "INNER JOIN puesto_votacion pv ON mv.puesto_id = pv.id " +
                "INNER JOIN municipio mun ON pv.municipio_id = mun.id " +
                "INNER JOIN departamento dep ON mun.departamento_id = dep.id " +
                "WHERE c.documento = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, documento);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int mesaId = rs.getInt("mesa_id");
                    String puestoNombre = rs.getString("puesto_nombre");
                    String puestoDireccion = rs.getString("puesto_direccion");
                    String municipioNombre = rs.getString("municipio_nombre");
                    String departamentoNombre = rs.getString("departamento_nombre");

                    String respuesta = String.format(
                            "Usted debe votar en %s ubicado en %s en %s, %s en la mesa %d.",
                            puestoNombre,
                            puestoDireccion,
                            municipioNombre,
                            departamentoNombre,
                            mesaId
                    );

                    return respuesta;
                } else {
                    return null;
                }
            }

        } catch (SQLException e) {
            return null;
        }
    }

    public static void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
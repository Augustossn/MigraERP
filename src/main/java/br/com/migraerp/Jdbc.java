package br.com.migraerp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

final class Jdbc {
    private final DataSource dataSource;

    Jdbc(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    interface Row<T> {
        T map(ResultSet row) throws SQLException;
    }

    <T> T transaction(Work<T> work) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    <T> T read(Work<T> work) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            return work.run(conn);
        }
    }

    static int update(Connection conn, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            bind(stmt, parameters);
            return stmt.executeUpdate();
        }
    }

    static <T> List<T> query(Connection conn, String sql, Row<T> mapper, Object... parameters)
            throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            bind(stmt, parameters);
            try (ResultSet rows = stmt.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(mapper.map(rows));
                }
                return result;
            }
        }
    }

    static void bind(PreparedStatement stmt, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] == null) {
                stmt.setNull(i + 1, Types.VARCHAR);
            } else {
                stmt.setObject(i + 1, parameters[i]);
            }
        }
    }
}

package br.com.migraerp;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import static br.com.migraerp.Jdbc.bind;
import static br.com.migraerp.Jdbc.query;
import static br.com.migraerp.Jdbc.update;

@Service
class Lotes {
    enum Motor { JAVA, PLSQL }
    enum Estado { IMPORTADO, INVALIDO, VALIDADO, APROVADO, MIGRADO }

    record Lote(long id, Estado estado, String motor, Integer validos, Integer invalidos,
                Instant criadoEm, Instant validadoEm, Instant aprovadoEm, Instant migradoEm) {}
    record Erro(String entidade, int linha, String campo, String codigo, String motivo) {}
    record Total(String entidade, int importados, Integer validos, Integer invalidos, int chavesNoDestino) {}
    record Validacao(Lote lote, double motorMs) {}

    private record Contagem(int validos, int invalidos) {}

    private static final String STAGE = """
            insert into staging_registro
                (lote_id, entidade, linha, codigo, nome, email, cliente_codigo, inicio_txt, fim_txt, valor_txt, erro_csv)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final Jdbc jdbc;
    private final String mergeClientes;
    private final String mergeContratos;
    private final String conciliacao;

    Lotes(DataSource dataSource) throws IOException {
        jdbc = new Jdbc(dataSource);
        mergeClientes = sql("merge-clientes");
        mergeContratos = sql("merge-contratos");
        conciliacao = sql("conciliacao");
    }

    Lote importar(List<CsvImport.Staged> clientes, List<CsvImport.Staged> contratos) throws SQLException {
        if (clientes.isEmpty() && contratos.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "O lote precisa de pelo menos um registro.");
        }
        return jdbc.transaction(conn -> {
            long id;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "insert into lote (estado) values ('IMPORTADO')", new String[]{"ID"})) {
                stmt.executeUpdate();
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Oracle nao retornou a chave do lote");
                    }
                    id = keys.getLong(1);
                }
            }
            stage(conn, id, clientes);
            stage(conn, id, contratos);
            return get(conn, id);
        });
    }

    Validacao validar(long id, Motor motor) throws SQLException {
        return jdbc.transaction(conn -> {
            Estado estado = lockBatch(conn, id).estado();
            if (estado != Estado.IMPORTADO && estado != Estado.INVALIDO && estado != Estado.VALIDADO) {
                throw conflito("Valide o lote antes de aprovar.");
            }
            long inicio = System.nanoTime();
            if (motor == Motor.PLSQL) {
                Contagem contagem = validarNoBanco(conn, id);
                double elapsed = (System.nanoTime() - inicio) / 1_000_000.0;
                Lote lote = get(conn, id);
                if (lote.validos() != contagem.validos() || lote.invalidos() != contagem.invalidos()) {
                    throw new SQLException("Contagem da procedure diverge do lote");
                }
                return new Validacao(lote, elapsed);
            }

            validarViaJava(conn, id);
            double elapsed = (System.nanoTime() - inicio) / 1_000_000.0;
            return new Validacao(get(conn, id), elapsed);
        });
    }

    Lote aprovar(long id) throws SQLException {
        return jdbc.transaction(conn -> {
            Lote lote = lockBatch(conn, id);
            if (lote.estado() == Estado.APROVADO) {
                return lote;
            }
            if (lote.estado() != Estado.VALIDADO) {
                throw conflito("A aprovacao exige um lote validado sem erros.");
            }
            update(conn, """
                    update lote set estado = 'APROVADO', aprovado_em = sys_extract_utc(systimestamp)
                    where id = ?
                    """, id);
            return get(conn, id);
        });
    }

    Lote migrar(long id) throws SQLException {
        return jdbc.transaction(conn -> {
            Lote lote = lockBatch(conn, id);
            if (lote.estado() == Estado.MIGRADO) {
                return lote;
            }
            if (lote.estado() != Estado.APROVADO) {
                throw conflito("A migracao exige um lote aprovado.");
            }
            // Lotes distintos também podem escrever a mesma chave de negócio.
            query(conn, "select id from controle_migracao where id = 1 for update nowait", rs -> rs.getInt(1))
                    .stream().findFirst()
                    .orElseThrow(() -> new SQLException("Linha de controle de migracao ausente"));
            int clientes = update(conn, mergeClientes, id);
            int contratos = update(conn, mergeContratos, id);
            if (clientes + contratos != lote.validos()) {
                throw new SQLException("Quantidade migrada diverge da quantidade aprovada");
            }
            update(conn, """
                    update lote set estado = 'MIGRADO', migrado_em = sys_extract_utc(systimestamp)
                    where id = ?
                    """, id);
            return get(conn, id);
        });
    }

    Lote get(long id) throws SQLException {
        return jdbc.read(conn -> get(conn, id));
    }

    List<Erro> erros(long id, int offset, int limit) throws SQLException {
        return jdbc.read(conn -> {
            get(conn, id);
            return query(conn, """
                    select entidade, linha, campo, codigo, motivo
                    from erro_validacao where lote_id = ?
                    order by entidade, linha, campo, codigo
                    offset ? rows fetch next ? rows only
                    """, rs -> new Erro(rs.getString(1), rs.getInt(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)), id, offset, limit);
        });
    }

    List<Total> conciliacao(long id) throws SQLException {
        return jdbc.read(conn -> {
            List<Total> totals = query(conn, conciliacao, rs -> new Total(rs.getString("entidade"),
                    rs.getInt("importados"), integerOrNull(rs, "validos"),
                    integerOrNull(rs, "invalidos"), rs.getInt("chaves_no_destino")), id);
            if (totals.isEmpty()) {
                throw naoEncontrado(id);
            }
            return totals;
        });
    }

    private static void validarViaJava(Connection conn, long id) throws SQLException {
        var linhas = query(conn, """
                select s.entidade, s.linha, s.codigo, s.nome, s.email, s.cliente_codigo,
                       s.inicio_txt, s.fim_txt, s.valor_txt, s.erro_csv,
                       case when c.id is null then 0 else 1 end cliente_no_destino
                from staging_registro s
                left join cliente c on s.entidade = 'CONTRATO' and c.codigo = s.cliente_codigo
                where s.lote_id = ?
                order by s.entidade, s.linha
                """, rs -> new ValidadorJava.Linha(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getInt(11) == 1), id);
        var resultado = ValidadorJava.validar(linhas);
        update(conn, "delete from erro_validacao where lote_id = ?", id);
        try (var stmt = conn.prepareStatement("""
                insert into erro_validacao (lote_id, entidade, linha, campo, codigo, motivo)
                values (?, ?, ?, ?, ?, ?)
                """)) {
            int pending = 0;
            for (var erro : resultado.erros()) {
                bind(stmt, id, erro.entidade(), erro.linha(), erro.campo(), erro.codigo(), erro.motivo());
                stmt.addBatch();
                if (++pending == 500) {
                    stmt.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                stmt.executeBatch();
            }
        }
        Estado estado = resultado.invalidos() == 0 ? Estado.VALIDADO : Estado.INVALIDO;
        update(conn, """
                update lote set estado = ?, motor = 'JAVA', validos = ?, invalidos = ?,
                                validado_em = sys_extract_utc(systimestamp)
                where id = ?
                """, estado.name(), resultado.validos(), resultado.invalidos(), id);
    }

    private static Contagem validarNoBanco(Connection conn, long id) throws SQLException {
        try (var stmt = conn.prepareCall("{call validar_lote(?, ?, ?)}")) {
            stmt.setLong(1, id);
            stmt.registerOutParameter(2, Types.NUMERIC);
            stmt.registerOutParameter(3, Types.NUMERIC);
            stmt.execute();
            return new Contagem(stmt.getInt(2), stmt.getInt(3));
        }
    }

    private static Lote lockBatch(Connection conn, long id) throws SQLException {
        var ids = query(conn, "select id from lote where id = ? for update nowait", rs -> rs.getLong(1), id);
        if (ids.isEmpty()) {
            throw naoEncontrado(id);
        }
        return get(conn, id);
    }

    private static Lote get(Connection conn, long id) throws SQLException {
        return query(conn, """
                select id, estado, motor, validos, invalidos, criado_em, validado_em, aprovado_em, migrado_em
                from lote where id = ?
                """, rs -> new Lote(rs.getLong("id"), Estado.valueOf(rs.getString("estado")),
                rs.getString("motor"), integerOrNull(rs, "validos"), integerOrNull(rs, "invalidos"),
                timestamp(rs, "criado_em"), timestamp(rs, "validado_em"), timestamp(rs, "aprovado_em"),
                timestamp(rs, "migrado_em")), id).stream().findFirst().orElseThrow(() -> naoEncontrado(id));
    }

    private static void stage(Connection conn, long id, List<CsvImport.Staged> rows) throws SQLException {
        try (var stmt = conn.prepareStatement(STAGE)) {
            int pending = 0;
            for (var row : rows) {
                boolean cliente = row.entity() == CsvImport.Entity.CLIENTE;
                bind(stmt, id, row.entity().name(), row.line(), row.optional(0),
                        cliente ? row.optional(1) : null, cliente ? row.optional(2) : null,
                        cliente ? null : row.optional(1), cliente ? null : row.optional(2),
                        cliente ? null : row.optional(3), cliente ? null : row.optional(4), row.error());
                stmt.addBatch();
                if (++pending == 500) {
                    stmt.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                stmt.executeBatch();
            }
        }
    }

    private static Instant timestamp(ResultSet rs, String field) throws SQLException {
        var value = rs.getTimestamp(field, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        return value == null ? null : value.toInstant();
    }

    private static Integer integerOrNull(ResultSet rs, String field) throws SQLException {
        int value = rs.getInt(field);
        return rs.wasNull() ? null : value;
    }

    private static String sql(String name) throws IOException {
        return new ClassPathResource("sql/" + name + ".sql").getContentAsString(StandardCharsets.UTF_8);
    }

    private static ApiException naoEncontrado(long id) {
        return new ApiException(HttpStatus.NOT_FOUND, "Lote " + id + " nao encontrado.");
    }

    private static ApiException conflito(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }
}

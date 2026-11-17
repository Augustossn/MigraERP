package br.com.migraerp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MigracaoOracleIT {
    private static final String API = "/api/lotes";
    private static final String CLIENTES = "codigo,nome,email\n";
    private static final String CONTRATOS = "codigo,cliente_codigo,inicio,fim,valor\n";
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*([0-9]+)");

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private String chave;
    private String contrato;

    @BeforeEach
    void chavesUnicas() {
        chave = "C-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(java.util.Locale.ROOT);
        contrato = "T-" + chave;
    }

    @Test
    void importaMigraReprocessaEAtualizaSemDuplicar() throws Exception {
        long lote = importarValido("Ana Original", "1234.50");
        mvc.perform(get(API + "/{id}/conciliacao", lote))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].importados").value(1))
                .andExpect(jsonPath("$[0].validos").isEmpty());
        aprovarValido(lote, "JAVA");
        String primeira = migrar(lote);
        assertThat(migrar(lote)).isEqualTo(primeira);
        long idCliente = numero("select id from cliente where codigo = ?", chave);
        long idContrato = numero("select id from contrato where codigo = ?", contrato);
        mvc.perform(get(API + "/{id}/conciliacao", lote))
                .andExpect(jsonPath("$[0].chavesNoDestino").value(1))
                .andExpect(jsonPath("$[1].chavesNoDestino").value(1));

        long atualizado = importarValido("Ana Atualizada", "999.75");
        aprovarValido(atualizado, "PLSQL");
        migrar(atualizado);
        migrar(lote);
        assertThat(numero("select id from cliente where codigo = ?", chave)).isEqualTo(idCliente);
        assertThat(numero("select id from contrato where codigo = ?", contrato)).isEqualTo(idContrato);
        assertThat(numero("select count(*) from cliente where codigo = ?", chave)).isEqualTo(1);
        assertThat(numero("select count(*) from contrato where codigo = ?", contrato)).isEqualTo(1);
        try (var conn = dataSource.getConnection()) {
            var valores = Jdbc.query(conn, "select valor from contrato where codigo = ?",
                    rs -> rs.getBigDecimal(1), contrato);
            assertThat(valores.getFirst()).isEqualByComparingTo(new BigDecimal("999.75"));
        }
    }

    @Test
    void osDoisMotoresRegistramOsMesmosErrosPorLinha() throws Exception {
        long lote = importar(chave + ",Ana,a@example.test\n" + chave + ",Ana repetida,b@example.test\n"
                        + "OUTRO-" + chave + ",,invalido\n",
                contrato + ",AUSENTE-" + chave + ",2026-02-30,2026-12-31,50\n"
                        + "T2-" + chave + "," + chave + ",2026-12-31,2026-01-01,-1\n");
        validarInvalido(lote, "JAVA");
        String errosJava = erros(lote);
        validarInvalido(lote, "PLSQL");
        assertThat(erros(lote)).isEqualTo(errosJava);
        assertThat(errosJava).contains("DUPLICADO", "ORFAO", "DATA_INVALIDA", "OBRIGATORIO",
                "FORMATO", "PERIODO", "VALOR_INVALIDO");
        mvc.perform(get(API + "/{id}/erros", lote).param("limit", "1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].linha").value(2))
                .andExpect(jsonPath("$[0].campo").value("codigo"))
                .andExpect(jsonPath("$[0].codigo").value("DUPLICADO"));
        mvc.perform(post(API + "/{id}/aprovar", lote)).andExpect(status().isConflict());
        mvc.perform(post(API + "/{id}/migrar", lote)).andExpect(status().isConflict());
    }

    @Test
    void falhaNoMergeContratoReverteClientePermiteNovaTentativa() throws Exception {
        long lote = importarValido("Cliente com rollback", "8.50");
        aprovarValido(lote, "JAVA");
        alterarValorStaging(lote, "valor-invalido");
        mvc.perform(post(API + "/{id}/migrar", lote)).andExpect(status().isInternalServerError());
        mvc.perform(get(API + "/{id}", lote)).andExpect(jsonPath("$.estado").value("APROVADO"));
        assertThat(numero("select count(*) from cliente where codigo = ?", chave)).isZero();
        assertThat(numero("select count(*) from contrato where codigo = ?", contrato)).isZero();
        alterarValorStaging(lote, "8.50");
        migrar(lote);
        assertThat(numero("select count(*) from cliente where codigo = ?", chave)).isEqualTo(1);
    }

    @Test
    void concorrenciaRetorna409EnquantoLinhaTravadaEDoisPedidosNaoDuplicam() throws Exception {
        long lote = importarValido("Cliente concorrente", "5.00");
        aprovarValido(lote, "JAVA");
        try (Connection bloqueio = dataSource.getConnection()) {
            bloqueio.setAutoCommit(false);
            try {
                Jdbc.query(bloqueio, "select id from lote where id = ? for update", rs -> rs.getLong(1), lote);
                mvc.perform(post(API + "/{id}/migrar", lote)).andExpect(status().isConflict());
            } finally {
                bloqueio.rollback();
            }
        }

        try (var threads = Executors.newFixedThreadPool(2)) {
            var inicio = new CountDownLatch(1);
            java.util.concurrent.Callable<Integer> pedido = () -> {
                inicio.await();
                return mvc.perform(post(API + "/{id}/migrar", lote)).andReturn().getResponse().getStatus();
            };
            var primeiro = threads.submit(pedido);
            var segundo = threads.submit(pedido);
            inicio.countDown();
            var codigos = java.util.List.of(primeiro.get(20, TimeUnit.SECONDS), segundo.get(20, TimeUnit.SECONDS));
            assertThat(codigos).contains(200).allMatch(codigo -> codigo == 200 || codigo == 409);
        }
        migrar(lote);
        assertThat(numero("select count(*) from contrato where codigo = ?", contrato)).isEqualTo(1);
    }

    @Test
    void contratoPodeApontarParaClienteDeLoteAnterior() throws Exception {
        long clientes = importar(chave + ",Ana,a@example.test\n", "");
        mvc.perform(post(API + "/{id}/validar", clientes))
                .andExpect(jsonPath("$.lote.validos").value(1));
        mvc.perform(post(API + "/{id}/aprovar", clientes)).andExpect(status().isOk());
        migrar(clientes);

        long contratos = importar("", contrato + "," + chave + ",2026-01-01,2026-12-31,12.50\n");
        mvc.perform(post(API + "/{id}/validar", contratos).param("motor", "PLSQL"))
                .andExpect(jsonPath("$.lote.validos").value(1));
        mvc.perform(post(API + "/{id}/aprovar", contratos)).andExpect(status().isOk());
        migrar(contratos);
        assertThat(numero("select count(*) from contrato where codigo = ?", contrato)).isEqualTo(1);
    }

    private long importarValido(String nome, String valor) throws Exception {
        return importar(chave + "," + nome + ",ana@example.test\n",
                contrato + "," + chave + ",2024-02-29,2026-12-31," + valor + "\n");
    }

    private long importar(String clientes, String contratos) throws Exception {
        var resposta = mvc.perform(multipart(API)
                        .file(csv("clientes", CLIENTES + clientes))
                        .file(csv("contratos", CONTRATOS + contratos)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("IMPORTADO"))
                .andReturn().getResponse().getContentAsString();
        var id = ID.matcher(resposta);
        assertThat(id.find()).isTrue();
        return Long.parseLong(id.group(1));
    }

    private static MockMultipartFile csv(String nome, String conteudo) {
        return new MockMultipartFile(nome, nome + ".csv", "text/csv", conteudo.getBytes(StandardCharsets.UTF_8));
    }

    private void aprovarValido(long lote, String motor) throws Exception {
        mvc.perform(post(API + "/{id}/validar", lote).param("motor", motor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lote.estado").value("VALIDADO"))
                .andExpect(jsonPath("$.lote.validos").value(2))
                .andExpect(jsonPath("$.lote.invalidos").value(0));
        mvc.perform(post(API + "/{id}/aprovar", lote))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("APROVADO"));
    }

    private void validarInvalido(long lote, String motor) throws Exception {
        mvc.perform(post(API + "/{id}/validar", lote).param("motor", motor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lote.estado").value("INVALIDO"))
                .andExpect(jsonPath("$.lote.validos").value(0))
                .andExpect(jsonPath("$.lote.invalidos").value(5));
    }

    private String erros(long lote) throws Exception {
        return mvc.perform(get(API + "/{id}/erros", lote)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String migrar(long lote) throws Exception {
        return mvc.perform(post(API + "/{id}/migrar", lote))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("MIGRADO"))
                .andReturn().getResponse().getContentAsString();
    }

    private long numero(String sql, String argumento) throws SQLException {
        try (var conn = dataSource.getConnection()) {
            return Jdbc.query(conn, sql, rs -> rs.getLong(1), argumento).getFirst();
        }
    }

    private void alterarValorStaging(long lote, String valor) throws SQLException {
        new Jdbc(dataSource).transaction(conn -> Jdbc.update(conn, """
                update staging_registro set valor_txt = ? where lote_id = ? and entidade = 'CONTRATO'
                """, valor, lote));
    }
}

package br.com.migraerp;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static br.com.migraerp.CsvImport.Entity.CLIENTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvImportTest {
    @Test
    void conservaLinhaFisicaComBomAspasECrlf() {
        var registros = CsvImport.parse(
                "\uFEFFcodigo,nome,email\r\nC-1,\"Ana, Silva\r\nFilial\",ana@example.test\r\nC-2,,b@example.test\r\n",
                CLIENTE);
        assertThat(registros).extracting(CsvImport.Staged::line).containsExactly(2, 4);
        assertThat(registros.getFirst().optional(1)).isEqualTo("Ana, Silva\r\nFilial");
        assertThat(registros.getLast().optional(1)).isNull();
    }

    @Test
    void registraQuantidadeIncorretaDeColunas() {
        var registros = CsvImport.parse("codigo,nome,email\nC-1,Ana\n", CLIENTE);
        assertThat(registros.getFirst().error()).contains("recebidas 2");
        assertThat(registros.getFirst().optional(2)).isNull();
    }

    @Test
    void rejeitaCabecalhoIncorretoAspasAbertasEUtf8Invalido() {
        assertThatThrownBy(() -> CsvImport.parse("codigo,email,nome\n", CLIENTE))
                .isInstanceOf(ApiException.class).hasMessageContaining("Cabecalho");
        assertThatThrownBy(() -> CsvImport.parse("codigo,nome,email\nC-1,\"Ana", CLIENTE))
                .isInstanceOf(ApiException.class).hasMessageContaining("malformado");
        var arquivo = new MockMultipartFile("clientes", new byte[]{(byte) 0xC3, 0x28});
        assertThatThrownBy(() -> CsvImport.read(arquivo, CLIENTE))
                .isInstanceOf(ApiException.class).hasMessageContaining("UTF-8");
    }
}

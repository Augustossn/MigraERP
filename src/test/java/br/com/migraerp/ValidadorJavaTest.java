package br.com.migraerp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidadorJavaTest {
    @Test
    void contaLinhasInvalidasUmaVezMesmoComVariosErros() {
        var invalida = new ValidadorJava.Linha("CONTRATO", 2, "CT-1", null, null,
                "AUSENTE", "2026-02-29", "2026-12-31", "0", null, false);
        var valida = new ValidadorJava.Linha("CONTRATO", 3, "CT-2", null, null,
                "CADASTRADO", "2024-02-29", "2024-12-31", "9999999999.99", null, true);

        var resultado = ValidadorJava.validar(List.of(invalida, valida));
        assertThat(resultado.invalidos()).isEqualTo(1);
        assertThat(resultado.validos()).isEqualTo(1);
        assertThat(resultado.erros()).extracting(Lotes.Erro::codigo)
                .containsExactlyInAnyOrder("DATA_INVALIDA", "VALOR_INVALIDO", "ORFAO");
    }
}

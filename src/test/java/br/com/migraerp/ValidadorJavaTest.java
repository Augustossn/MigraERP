package br.com.migraerp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidadorJavaTest {
    @Test
    void aceitaContratoReferenciadoPorClienteValidoNoMesmoLote() {
        var cliente = new ValidadorJava.Linha("CLIENTE", 2, "C-1", "Ana", "ana@example.test",
                null, null, null, null, null, false);
        var contrato = new ValidadorJava.Linha("CONTRATO", 2, "CT-1", null, null,
                "C-1", "2026-01-01", "2026-12-31", "25.00", null, false);

        var resultado = ValidadorJava.validar(List.of(cliente, contrato));
        assertThat(resultado.validos()).isEqualTo(2);
        assertThat(resultado.invalidos()).isZero();
        assertThat(resultado.erros()).isEmpty();
    }

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

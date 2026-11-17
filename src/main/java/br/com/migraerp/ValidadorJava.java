package br.com.migraerp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

final class ValidadorJava {
    private static final Pattern CODIGO = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,29}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+([.][A-Za-z0-9-]+)+");
    private static final Pattern DATA = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern VALOR = Pattern.compile("[0-9]{1,10}([.][0-9]{1,2})?");

    record Linha(String entidade, int numero, String codigo, String nome, String email,
                 String clienteCodigo, String inicio, String fim, String valor, String erroCsv,
                 boolean clienteNoDestino) {
        boolean cliente() {
            return entidade.equals("CLIENTE");
        }

        Lotes.Erro erro(String campo, String codigo, String motivo) {
            return new Lotes.Erro(entidade, numero, campo, codigo, motivo);
        }
    }

    record Resultado(List<Lotes.Erro> erros, int validos, int invalidos) {}
    private record Chave(String entidade, String codigo) {}

    private ValidadorJava() {}

    // O SELECT JDBC entrega todos os clientes antes dos contratos.
    static Resultado validar(List<Linha> linhas) {
        var ocorrencias = new HashMap<Chave, Integer>();
        var clientesValidos = new HashSet<String>();
        var erros = new ArrayList<Lotes.Erro>();
        int invalidos = 0;
        for (Linha linha : linhas) {
            if (linha.codigo() != null) {
                ocorrencias.merge(new Chave(linha.entidade(), linha.codigo()), 1, Integer::sum);
            }
        }
        for (Linha linha : linhas) {
            int antes = erros.size();
            if (linha.erroCsv() != null) {
                erros.add(linha.erro("_linha", "FORMATO_CSV", linha.erroCsv()));
            }
            validarTexto(linha, erros, "codigo", linha.codigo(), 0, CODIGO);
            if (ocorrencias.getOrDefault(new Chave(linha.entidade(), linha.codigo()), 0) > 1) {
                erros.add(linha.erro("codigo", "DUPLICADO", "Codigo repetido para esta entidade no lote"));
            }
            if (linha.cliente()) {
                validarTexto(linha, erros, "nome", linha.nome(), 120, null);
                validarTexto(linha, erros, "email", linha.email(), 254, EMAIL);
                if (erros.size() == antes) {
                    clientesValidos.add(linha.codigo());
                }
            } else {
                validarTexto(linha, erros, "cliente_codigo", linha.clienteCodigo(), 0, CODIGO);
                LocalDate inicio = data(linha, erros, "inicio", linha.inicio());
                LocalDate fim = data(linha, erros, "fim", linha.fim());
                if (inicio != null && fim != null && fim.isBefore(inicio)) {
                    erros.add(linha.erro("fim", "PERIODO", "Fim anterior ao inicio"));
                }
                if (linha.valor() == null) {
                    obrigatorio(linha, erros, "valor");
                } else if (!VALOR.matcher(linha.valor()).matches() || new BigDecimal(linha.valor()).signum() <= 0) {
                    erros.add(linha.erro("valor", "VALOR_INVALIDO", "Use valor positivo com ponto decimal e ate duas casas"));
                }
                if (linha.clienteCodigo() != null && CODIGO.matcher(linha.clienteCodigo()).matches()
                        && !linha.clienteNoDestino() && !clientesValidos.contains(linha.clienteCodigo())) {
                    erros.add(linha.erro("cliente_codigo", "ORFAO", "Cliente ausente no destino e sem registro valido no lote"));
                }
            }
            if (erros.size() != antes) {
                invalidos++;
            }
        }
        return new Resultado(erros, linhas.size() - invalidos, invalidos);
    }

    private static void validarTexto(Linha linha, List<Lotes.Erro> erros, String campo,
                                     String valor, int maximo, Pattern formato) {
        if (valor == null) {
            obrigatorio(linha, erros, campo);
        } else if (maximo > 0 && valor.codePointCount(0, valor.length()) > maximo) {
            erros.add(linha.erro(campo, "TAMANHO", "Campo excede o tamanho permitido"));
        } else if (formato != null && !formato.matcher(valor).matches()) {
            erros.add(linha.erro(campo, "FORMATO", "Formato do campo invalido"));
        }
    }

    private static LocalDate data(Linha linha, List<Lotes.Erro> erros, String campo, String valor) {
        if (valor == null) {
            obrigatorio(linha, erros, campo);
            return null;
        }
        if (DATA.matcher(valor).matches()) {
            try {
                LocalDate data = LocalDate.parse(valor);
                if (data.getYear() >= 1900) {
                    return data;
                }
            } catch (DateTimeParseException ignored) {
                // O texto original permanece no staging.
            }
        }
        erros.add(linha.erro(campo, "DATA_INVALIDA", "Use uma data existente desde 1900 no formato AAAA-MM-DD"));
        return null;
    }

    private static void obrigatorio(Linha linha, List<Lotes.Erro> erros, String campo) {
        erros.add(linha.erro(campo, "OBRIGATORIO", "Campo obrigatorio ausente"));
    }
}

package br.com.migraerp;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class CsvImport {
    private static final int MAX_RECORDS = 50_000;
    private static final int MAX_FIELD_LENGTH = 1_000;

    enum Entity {
        CLIENTE("codigo", "nome", "email"),
        CONTRATO("codigo", "cliente_codigo", "inicio", "fim", "valor");

        private final List<String> headers;

        Entity(String... headers) {
            this.headers = List.of(headers);
        }
    }

    record Staged(Entity entity, int line, String[] fields, String error) {
        String optional(int index) {
            return index < fields.length ? fields[index] : null;
        }
    }

    private CsvImport() {}

    static List<Staged> read(MultipartFile file, Entity entity) throws IOException {
        byte[] bytes = file.getBytes();
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw invalid(entity, "Use um CSV com codificacao UTF-8.");
        }
        return parse(content, entity);
    }

    static List<Staged> parse(String content, Entity entity) {
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        try (CSVParser parser = CSVParser.parse(content, CSVFormat.RFC4180)) {
            Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext() || !records.next().toList().equals(entity.headers)) {
                throw invalid(entity, "Cabecalho esperado: " + String.join(",", entity.headers));
            }

            List<Staged> rows = new ArrayList<>();
            int cursor = 0;
            int line = 1;
            while (records.hasNext()) {
                CSVRecord record = records.next();
                if (rows.size() == MAX_RECORDS) {
                    throw invalid(entity, "Limite de 50000 registros por arquivo.");
                }
                while (cursor < record.getCharacterPosition()) {
                    char ch = content.charAt(cursor++);
                    if (ch == '\r' || (ch == '\n' && (cursor == 1 || content.charAt(cursor - 2) != '\r'))) {
                        line++;
                    }
                }
                rows.add(stage(record, entity, line));
            }
            return rows;
        } catch (IOException | UncheckedIOException e) {
            throw invalid(entity, "CSV malformado. Verifique aspas e separadores.");
        }
    }

    private static Staged stage(CSVRecord record, Entity entity, int line) {
        int expected = entity.headers.size();
        String[] fields = new String[expected];
        for (int index = 0; index < Math.min(record.size(), expected); index++) {
            fields[index] = record.get(index);
        }
        String error = null;
        if (record.size() != expected) {
            error = "Esperadas " + expected + " colunas, recebidas " + record.size();
        }
        for (int index = 0; index < expected; index++) {
            if (fields[index] == null) {
                continue;
            }
            String value = fields[index].strip();
            if (value.codePointCount(0, value.length()) > MAX_FIELD_LENGTH) {
                fields[index] = value.substring(0, value.offsetByCodePoints(0, MAX_FIELD_LENGTH));
                if (error == null) {
                    error = "Campo " + entity.headers.get(index) + " excede 1000 caracteres no staging";
                }
            } else {
                fields[index] = value.isEmpty() ? null : value;
            }
        }
        return new Staged(entity, line, fields, error);
    }

    private static ApiException invalid(Entity entity, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, entity + ": " + message);
    }
}

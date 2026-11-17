package br.com.migraerp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;

@RestController
@RequestMapping("/api/lotes")
class LoteController {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoteController.class);
    private final Lotes lotes;

    LoteController(Lotes lotes) {
        this.lotes = lotes;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Lotes.Lote> importar(@RequestPart("clientes") MultipartFile clientes,
                                       @RequestPart("contratos") MultipartFile contratos)
            throws IOException, SQLException {
        var lote = lotes.importar(CsvImport.read(clientes, CsvImport.Entity.CLIENTE),
                CsvImport.read(contratos, CsvImport.Entity.CONTRATO));
        return ResponseEntity.created(URI.create("/api/lotes/" + lote.id())).body(lote);
    }

    @GetMapping("/{id}")
    Lotes.Lote get(@PathVariable long id) throws SQLException {
        return lotes.get(id);
    }

    @PostMapping("/{id}/validar")
    Lotes.Validacao validar(@PathVariable long id, @RequestParam(defaultValue = "JAVA") Lotes.Motor motor)
            throws SQLException {
        return lotes.validar(id, motor);
    }

    @GetMapping("/{id}/erros")
    List<Lotes.Erro> erros(@PathVariable long id, @RequestParam(defaultValue = "0") int offset,
                           @RequestParam(defaultValue = "100") int limit) throws SQLException {
        if (offset < 0 || limit < 1 || limit > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Use offset >= 0 e limit entre 1 e 500.");
        }
        return lotes.erros(id, offset, limit);
    }

    @GetMapping("/{id}/conciliacao")
    List<Lotes.Total> conciliacao(@PathVariable long id) throws SQLException {
        return lotes.conciliacao(id);
    }

    @PostMapping("/{id}/aprovar")
    Lotes.Lote aprovar(@PathVariable long id) throws SQLException {
        return lotes.aprovar(id);
    }

    @PostMapping("/{id}/migrar")
    Lotes.Lote migrar(@PathVariable long id) throws SQLException {
        return lotes.migrar(id);
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail requestError(ApiException e) {
        return e.getBody();
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "CSV acima do limite de upload.");
    }

    @ExceptionHandler(SQLException.class)
    ProblemDetail databaseError(SQLException e) {
        for (SQLException cause = e; cause != null; cause = cause.getNextException()) {
            if (cause.getErrorCode() == 54) {
                return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                        "Operacao concorrente em andamento. Tente novamente.");
            }
        }
        LOGGER.error("Operacao JDBC falhou", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Operacao no Oracle falhou.");
    }
}

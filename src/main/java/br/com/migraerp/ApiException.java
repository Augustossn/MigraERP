package br.com.migraerp;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ApiException extends ResponseStatusException {
    ApiException(HttpStatus status, String message) {
        super(status, message);
    }
}

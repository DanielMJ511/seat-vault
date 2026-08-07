package com.seatvault.seat_vault.exception;

import org.springframework.http.HttpStatus;

/**
 * Base unchecked exception for all business/service-layer errors.
 * Services should throw this (or a subclass) rather than letting
 * infrastructure exceptions leak to the API layer.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}

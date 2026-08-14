package com.seatvault.seat_vault.exception;

import org.springframework.http.HttpStatus;

/**
 * Base unchecked exception for all business/service-layer errors.
 * Services should throw this (or a subclass) rather than letting
 * infrastructure exceptions leak to the API layer.
 *
 * <p>Only takes an {@link ErrorCode}, never a raw status/code pair: {@link
 * ErrorCode} is the single source of truth for the closed catalogue (see its
 * Javadoc and {@code docs/adr/0009-error-codes-are-a-closed-enum-and-split-on-client-action.md}),
 * and a public bypass around that would defeat the point of closing it.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return errorCode.getStatus();
    }

    public String getCode() {
        return errorCode.name();
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

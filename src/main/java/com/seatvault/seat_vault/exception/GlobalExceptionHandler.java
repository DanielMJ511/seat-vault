package com.seatvault.seat_vault.exception;

import com.seatvault.seat_vault.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Translates exceptions thrown anywhere in the request-handling pipeline
 * into a consistent {@link ErrorResponse} payload.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                ex.getStatus().value(),
                ex.getCode(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                ErrorCode.VALIDATION_ERROR.getStatus().value(),
                ErrorCode.VALIDATION_ERROR.name(),
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatchException(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String requiredType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "the expected type";
        String message = "Parameter '" + ex.getName() + "' must be of type " + requiredType + ".";

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                ErrorCode.INVALID_PARAMETER.getStatus().value(),
                ErrorCode.INVALID_PARAMETER.name(),
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(ErrorCode.INVALID_PARAMETER.getStatus()).body(body);
    }

    /**
     * Spring MVC signals "no handler matched this path" by throwing
     * {@link NoResourceFoundException}. Without this method it falls through
     * to {@link #handleUnexpectedException} below and is reported as a 500,
     * logged at {@code ERROR} with a stack trace - which is wrong twice
     * over: it tells the caller the server is broken and their request may
     * be worth retrying, and it makes every mistyped URL, stale bookmark,
     * and vulnerability scanner look like an incident in the logs.
     * <p>
     * Logged at {@code DEBUG} rather than {@code WARN} for that second
     * reason: on a public endpoint this fires constantly and carries no
     * signal about the application's own health.
     * <p>
     * Registered ahead of the catch-all by specificity, not by declaration
     * order - Spring resolves the most specific applicable
     * {@code @ExceptionHandler} - but the two are kept adjacent so the
     * relationship is visible. See ADR-0009 for why this gets its own code
     * rather than reusing a resource-level 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        log.debug("No handler for request [{}]", request.getRequestURI());
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                ErrorCode.ROUTE_NOT_FOUND.getStatus().value(),
                ErrorCode.ROUTE_NOT_FOUND.name(),
                ErrorCode.ROUTE_NOT_FOUND.getDescription(),
                request.getRequestURI()
        );
        return ResponseEntity.status(ErrorCode.ROUTE_NOT_FOUND.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while processing request [{}]", request.getRequestURI(), ex);
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                ErrorCode.INTERNAL_SERVER_ERROR.getStatus().value(),
                ErrorCode.INTERNAL_SERVER_ERROR.name(),
                ErrorCode.INTERNAL_SERVER_ERROR.getDescription(),
                request.getRequestURI()
        );
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus()).body(body);
    }
}

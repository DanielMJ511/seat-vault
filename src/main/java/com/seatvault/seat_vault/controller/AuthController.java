package com.seatvault.seat_vault.controller;

import com.seatvault.seat_vault.dto.AuthResponse;
import com.seatvault.seat_vault.dto.ErrorResponse;
import com.seatvault.seat_vault.dto.LoginRequest;
import com.seatvault.seat_vault.dto.RegisterRequest;
import com.seatvault.seat_vault.dto.UserResponse;
import com.seatvault.seat_vault.security.AuthenticatedUser;
import com.seatvault.seat_vault.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // ErrorCode.EMAIL_ALREADY_REGISTERED / ErrorCode.VALIDATION_ERROR
    @Operation(summary = "Register a new account and receive a JWT.")
    @ApiResponse(responseCode = "201", description = "Account created; token issued.",
            content = @Content(schema = @Schema(implementation = AuthResponse.class)))
    @ApiResponse(responseCode = "400", description = "Request body failed validation "
            + "(malformed email, or password not 8-72 chars with upper/lower/digit).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "VALIDATION_ERROR", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"VALIDATION_ERROR",\
                            "message":"password: password must be 8-72 characters and include an uppercase letter, \
                            a lowercase letter, and a digit","path":"/api/auth/register"}""")))
    @ApiResponse(responseCode = "409", description = "An account with this email already exists.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "EMAIL_ALREADY_REGISTERED", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":409,"code":"EMAIL_ALREADY_REGISTERED",\
                            "message":"An account with this email already exists.","path":"/api/auth/register"}""")))
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    // ErrorCode.INVALID_CREDENTIALS / ErrorCode.VALIDATION_ERROR
    @Operation(summary = "Authenticate with email and password and receive a JWT.")
    @ApiResponse(responseCode = "200", description = "Credentials valid; token issued.",
            content = @Content(schema = @Schema(implementation = AuthResponse.class)))
    @ApiResponse(responseCode = "400", description = "Request body failed validation (blank email or password).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "VALIDATION_ERROR", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"VALIDATION_ERROR",\
                            "message":"email: must not be blank","path":"/api/auth/login"}""")))
    @ApiResponse(responseCode = "401",
            description = "The supplied email or password is incorrect. Returned identically for an unknown "
                    + "email and a wrong password, so a caller can't enumerate registered emails by timing "
                    + "or response shape.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "INVALID_CREDENTIALS", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":401,"code":"INVALID_CREDENTIALS",\
                            "message":"Invalid email or password","path":"/api/auth/login"}""")))
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    // ErrorCode.USER_NOT_FOUND / ErrorCode.UNAUTHENTICATED
    @Operation(summary = "Get the authenticated caller's own account details.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "The caller's account.",
            content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication is required to access this resource.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "UNAUTHENTICATED", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":401,"code":"UNAUTHENTICATED",\
                            "message":"Authentication is required to access this resource.","path":"/api/auth/me"}""")))
    @ApiResponse(responseCode = "404",
            description = "The token is validly signed but the account it names no longer exists (JWTs are "
                    + "stateless and non-revocable per ADR-0005, so a deleted account's outstanding tokens keep "
                    + "authenticating until they expire).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "USER_NOT_FOUND", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":404,"code":"USER_NOT_FOUND",\
                            "message":"User no longer exists.","path":"/api/auth/me"}""")))
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.currentUser(principal);
    }
}

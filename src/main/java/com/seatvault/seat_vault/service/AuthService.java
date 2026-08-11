package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.dto.AuthResponse;
import com.seatvault.seat_vault.dto.LoginRequest;
import com.seatvault.seat_vault.dto.RegisterRequest;
import com.seatvault.seat_vault.dto.UserResponse;
import com.seatvault.seat_vault.entity.User;
import com.seatvault.seat_vault.exception.ApiException;
import com.seatvault.seat_vault.repository.UserRepository;
import com.seatvault.seat_vault.security.AuthenticatedUser;
import com.seatvault.seat_vault.security.JwtService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";

    /**
     * Precomputed valid BCrypt hash (cost factor 10, matching
     * {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}'s
     * default) of an arbitrary random string with no corresponding user
     * account. {@link #login(LoginRequest)} matches against this whenever the
     * requested email doesn't exist, so a BCrypt comparison always runs
     * regardless of whether the account is real — keeping the "unknown
     * email" and "wrong password" paths the same cost and closing a timing
     * side-channel that would otherwise let an attacker enumerate registered
     * emails.
     */
    private static final String DUMMY_HASH_FOR_TIMING_SAFETY =
            "$2a$10$yoJRYsJKb1.r2.nXOt5T8OERS2IlGqGeB2MTdHN.EPqq8p4Xv5vlu";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                    "An account with this email already exists.");
        }

        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .build();

        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            // Pre-check above isn't atomic with the insert, so a concurrent
            // registration for the same email can still slip through and
            // trip the uq_users_email_lower constraint here. Translate it to
            // the same 409 the pre-check would have produced instead of
            // letting it fall through to the generic 500 handler.
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                    "An account with this email already exists.");
        }

        return issueTokenFor(saved);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Optional<User> found = userRepository.findByEmailIgnoreCase(request.email());
        boolean matches = passwordEncoder.matches(
                request.password(),
                found.map(User::getPasswordHash).orElse(DUMMY_HASH_FOR_TIMING_SAFETY));

        if (found.isEmpty() || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", INVALID_CREDENTIALS_MESSAGE);
        }

        return issueTokenFor(found.get());
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(AuthenticatedUser principal) {
        User user = userRepository.findById(principal.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                        "User no longer exists."));

        return new UserResponse(user.getId(), user.getEmail(), user.getCreatedAt());
    }

    private AuthResponse issueTokenFor(User user) {
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, jwtService.extractExpiration(token));
    }
}

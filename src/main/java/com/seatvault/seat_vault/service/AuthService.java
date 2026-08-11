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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";

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
        User saved = userRepository.save(user);

        return issueTokenFor(saved);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                        INVALID_CREDENTIALS_MESSAGE));

        return issueTokenFor(user);
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

package com.seatvault.seat_vault.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.dto.LoginRequest;
import com.seatvault.seat_vault.dto.RegisterRequest;
import com.seatvault.seat_vault.entity.User;
import com.seatvault.seat_vault.repository.UserRepository;
import com.seatvault.seat_vault.security.JwtProperties;
import com.seatvault.seat_vault.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end coverage of the {@code /api/auth/**} surface against a real
 * Postgres/Redis stack, including the seeded demo accounts from
 * {@code db/seed}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class AuthIntegrationTest {

    private static final String SEEDED_EMAIL = "alice@example.com";
    private static final String SEEDED_PASSWORD = "Password123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void registerHappyPathReturnsTokenAndPersistsHashedPassword() throws Exception {
        String email = "new-user@example.com";
        String rawPassword = "StrongPass1";

        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, rawPassword)))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.token").value(notNullValue()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.expiresAt").value(notNullValue()));

        User saved = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo(rawPassword);
        assertThat(passwordEncoder.matches(rawPassword, saved.getPasswordHash())).isTrue();
    }

    @Test
    void registerWithDuplicateEmailCaseInsensitiveIsRejected() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("ALICE@example.com", "StrongPass1")))
                .andExpect(MockMvcResultMatchers.status().isConflict())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("EMAIL_ALREADY_REGISTERED")));
    }

    @Test
    void registerWithWeakPasswordIsRejected() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("weak-pw-user@example.com", "weak")))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("VALIDATION_ERROR")));
    }

    @Test
    void loginHappyPathWithSeededUserReturnsToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(SEEDED_EMAIL, SEEDED_PASSWORD)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.token").value(notNullValue()));
    }

    @Test
    void loginWithWrongPasswordIsRejected() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(SEEDED_EMAIL, "wrong-password")))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("INVALID_CREDENTIALS")));
    }

    @Test
    void loginWithUnknownEmailIsRejectedWithSameCodeAsWrongPassword() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("nobody@example.com", "whatever1A")))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("INVALID_CREDENTIALS")));
    }

    @Test
    void meWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me"))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("UNAUTHENTICATED")));
    }

    @Test
    void meWithValidTokenReturnsCurrentUser() throws Exception {
        User alice = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow();
        String token = jwtService.generateToken(alice.getId(), alice.getEmail());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(alice.getId()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.email").value(SEEDED_EMAIL));
    }

    @Test
    void meWithExpiredTokenIsUnauthorized() throws Exception {
        User alice = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow();

        long originalExpiration = jwtProperties.getExpirationMinutes();
        jwtProperties.setExpirationMinutes(0);
        String token;
        try {
            token = jwtService.generateToken(alice.getId(), alice.getEmail());
            Thread.sleep(1_100); // cross a whole-second boundary so exp is guaranteed to be in the past
        } finally {
            jwtProperties.setExpirationMinutes(originalExpiration);
        }

        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("UNAUTHENTICATED")));
    }

    @Test
    void meWithTamperedTokenIsUnauthorized() throws Exception {
        User alice = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow();
        String token = jwtService.generateToken(alice.getId(), alice.getEmail());
        String tamperedToken = tamperSignature(token);

        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("UNAUTHENTICATED")));
    }

    /**
     * Flips a character in the signature segment of a valid JWT so the
     * payload/header stay well-formed but the signature no longer verifies —
     * this exercises the real {@link com.seatvault.seat_vault.security.JwtAuthenticationFilter}
     * + {@code AuthenticationEntryPoint} together, driven through the actual
     * {@code SecurityFilterChain} via MockMvc.
     */
    private String tamperSignature(String token) {
        String[] parts = token.split("\\.");
        char[] signatureChars = parts[2].toCharArray();
        // Flip a character in the middle of the signature rather than the
        // last character: base64url's final character of a non-padded
        // segment only encodes a couple of spare bits, so mutating it can
        // decode back to the exact same bytes and leave the signature
        // valid. A middle character always changes the decoded byte value.
        int index = signatureChars.length / 2;
        char original = signatureChars[index];
        signatureChars[index] = original == 'A' ? 'B' : 'A';
        parts[2] = new String(signatureChars);
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    private String registerJson(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new RegisterRequest(email, password));
    }

    private String loginJson(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(email, password));
    }
}

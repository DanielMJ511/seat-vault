package com.seatvault.seat_vault.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * @param email    must be a well-formed, non-blank email address
 * @param password must be 8-72 characters (BCrypt silently truncates
 *                 anything past 72 bytes, so longer input is rejected rather
 *                 than accepted-but-ignored) and contain at least one
 *                 uppercase letter, one lowercase letter, and one digit
 */
public record RegisterRequest(
        @Email @NotBlank String email,

        @NotBlank
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,72}$",
                message = "password must be 8-72 characters and include an uppercase letter, "
                        + "a lowercase letter, and a digit")
        String password
) {
}

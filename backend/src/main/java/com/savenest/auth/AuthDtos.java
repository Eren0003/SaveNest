package com.savenest.auth;

import com.savenest.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 80) String displayName) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record VerifyResetCodeRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "must be 6 digits") String code) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 20, max = 100) String resetToken,
            @NotBlank @Size(min = 12, max = 128) String newPassword,
            @NotBlank @Size(max = 128) String confirmPassword) {
    }

    public record MessageDto(String message) {
    }

    public record ResetTokenDto(String resetToken, long expiresInSeconds) {
    }

    public record UserDto(UUID id, String email, String displayName, String avatarUrl, Instant createdAt) {
        public static UserDto from(User u) {
            return new UserDto(u.getId(), u.getEmail(), u.getDisplayName(), u.getAvatarUrl(), u.getCreatedAt());
        }
    }

    public record CsrfDto(String headerName, String token) {
    }
}

package com.savenest.auth;

import com.savenest.common.ApiError;
import com.savenest.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;

/** Server-side password strength rules (length matches registration: 12–128 characters). */
final class PasswordPolicy {

    private PasswordPolicy() {
    }

    static void check(String password, String emailNormalized) {
        String problem = null;
        if (password.length() < 12 || password.length() > 128) {
            problem = "Password must be 12 to 128 characters";
        } else if (password.isBlank() || password.chars().distinct().count() < 4) {
            problem = "Password is too simple";
        } else if (emailNormalized != null && password.toLowerCase(Locale.ROOT).contains(emailNormalized)) {
            problem = "Password must not contain your email address";
        }
        if (problem != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "weak_password", problem,
                    List.of(new ApiError.FieldError("newPassword", problem)));
        }
    }
}

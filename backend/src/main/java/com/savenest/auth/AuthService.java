package com.savenest.auth;

import com.savenest.common.ApiException;
import com.savenest.user.User;
import com.savenest.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(AuthDtos.RegisterRequest req) {
        String normalized = User.normalizeEmail(req.email());
        if (users.existsByEmailNormalized(normalized)) {
            throw emailTaken();
        }
        try {
            return users.saveAndFlush(new User(req.email(), req.displayName(), passwordEncoder.encode(req.password())));
        } catch (DataIntegrityViolationException e) {
            // Concurrent registration with the same email: unique constraint is the source of truth.
            throw emailTaken();
        }
    }

    @Transactional(readOnly = true)
    public User get(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Session is no longer valid"));
    }

    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "email_taken", "An account with this email already exists");
    }
}

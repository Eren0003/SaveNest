package com.savenest.auth;

import com.savenest.common.ApiException;
import com.savenest.common.DataResponse;
import com.savenest.ratelimit.RateLimiter;
import com.savenest.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final RateLimiter rateLimiter;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(AuthService authService, AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          CsrfTokenRepository csrfTokenRepository, RateLimiter rateLimiter) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.rateLimiter = rateLimiter;
    }

    /** Issues/returns the CSRF token; also writes the XSRF-TOKEN cookie. */
    @GetMapping("/csrf")
    public DataResponse<AuthDtos.CsrfDto> csrf(CsrfToken token) {
        return DataResponse.of(new AuthDtos.CsrfDto(token.getHeaderName(), token.getToken()));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public DataResponse<AuthDtos.UserDto> register(@Valid @RequestBody AuthDtos.RegisterRequest body,
                                                   HttpServletRequest req, HttpServletResponse res) {
        rateLimiter.check(RateLimiter.Bucket.AUTH, req.getRemoteAddr());
        User user = authService.register(body);
        establishSession(authenticate(body.email(), body.password()), req, res);
        return DataResponse.of(AuthDtos.UserDto.from(user));
    }

    @PostMapping("/login")
    public DataResponse<AuthDtos.UserDto> login(@Valid @RequestBody AuthDtos.LoginRequest body,
                                                HttpServletRequest req, HttpServletResponse res) {
        rateLimiter.check(RateLimiter.Bucket.AUTH, req.getRemoteAddr());
        Authentication auth = authenticate(body.email(), body.password());
        establishSession(auth, req, res);
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        return DataResponse.of(AuthDtos.UserDto.from(authService.get(principal.getUserId())));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest req, HttpServletResponse res, Authentication auth) {
        new SecurityContextLogoutHandler().logout(req, res, auth);
        csrfTokenRepository.saveToken(null, req, res);
    }

    @GetMapping("/me")
    public DataResponse<AuthDtos.UserDto> me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return DataResponse.of(AuthDtos.UserDto.from(authService.get(principal.getUserId())));
    }

    private Authentication authenticate(String email, String password) {
        try {
            return authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        } catch (AuthenticationException e) {
            // Generic message: do not reveal whether the email exists.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid email or password");
        }
    }

    private void establishSession(Authentication auth, HttpServletRequest req, HttpServletResponse res) {
        // Session fixation protection: rotate the session ID on login.
        if (req.getSession(false) != null) {
            req.changeSessionId();
        }
        SecurityContext ctx = holder.createEmptyContext();
        ctx.setAuthentication(auth);
        holder.setContext(ctx);
        securityContextRepository.saveContext(ctx, req, res);
        // Rotate the CSRF token after authentication; client re-fetches /api/auth/csrf.
        csrfTokenRepository.saveToken(null, req, res);
    }
}

package com.savenest.user;

import com.savenest.auth.AppUserPrincipal;
import com.savenest.common.ApiException;
import com.savenest.common.DataResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code DELETE /api/account}: irreversible account and data deletion (spec §14). */
@RestController
@RequestMapping("/api")
public class AccountController {

    public record DeleteAccountRequest(@NotBlank @Size(max = 128) String password) {
    }

    private final AccountService accounts;
    private final CsrfTokenRepository csrfTokenRepository;

    public AccountController(AccountService accounts, CsrfTokenRepository csrfTokenRepository) {
        this.accounts = accounts;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    /** Requires the current password and {@code ?confirm=true}; signs the caller out afterwards. */
    @DeleteMapping("/account")
    public DataResponse<AccountService.DeletionSummary> delete(
            @RequestParam(defaultValue = "false") boolean confirm,
            @Valid @RequestBody DeleteAccountRequest body,
            @AuthenticationPrincipal AppUserPrincipal user,
            Authentication auth, HttpServletRequest req, HttpServletResponse res) {
        if (!confirm) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "confirmation_required",
                    "Account deletion requires confirm=true");
        }
        var summary = accounts.delete(user.getUserId(), body.password());
        new SecurityContextLogoutHandler().logout(req, res, auth);
        csrfTokenRepository.saveToken(null, req, res);
        return DataResponse.of(summary);
    }
}

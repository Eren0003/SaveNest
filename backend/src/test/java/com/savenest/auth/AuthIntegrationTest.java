package com.savenest.auth;

import com.savenest.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    private static String body(String email, String password, String name) {
        return """
                {"email":"%s","password":"%s","displayName":"%s"}""".formatted(email, password, name);
    }

    @Test
    void registerLoginMeLogout() throws Exception {
        String email = "demo-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "correct-horse-battery", "Demo")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email.toUpperCase(), "correct-horse-battery", "x")))
                .andExpect(status().isOk())
                .andReturn();
        // Sessions live in Spring Session JDBC and are delivered by cookie, exactly like a browser.
        jakarta.servlet.http.Cookie session = login.getResponse().getCookie("SAVENEST_SESSION");
        org.assertj.core.api.Assertions.assertThat(session).isNotNull();

        mvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email));

        mvc.perform(post("/api/auth/logout").cookie(session).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsGenericUnauthorized() throws Exception {
        String email = "demo-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(email, "correct-horse-battery", "Demo"))).andExpect(status().isCreated());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "wrong-password-123", "x")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_credentials"))
                .andExpect(jsonPath("$.error.requestId").exists());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("nobody-" + UUID.randomUUID() + "@example.test", "whatever-12345", "x")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_credentials"));
    }

    @Test
    void duplicateEmailConflicts() throws Exception {
        String email = "demo-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(email, "correct-horse-battery", "Demo"))).andExpect(status().isCreated());
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email.toUpperCase(), "correct-horse-battery", "Demo")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("email_taken"));
    }

    @Test
    void validationErrorsReturn422() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-an-email", "short", "")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    void protectedEndpointRequiresSession() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthenticated"));
    }

    @Test
    void stateChangingRequestWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("a@example.test", "whatever-12345", "x")))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfEndpointIssuesToken() throws Exception {
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }
}

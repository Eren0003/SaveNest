package com.savenest.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SMTP mailer (works with Brevo, Gmail app passwords, Mailpit, ...). Sending is asynchronous so
 * the HTTP response time does not depend on whether the email address is registered.
 */
@Component
public class SmtpPasswordResetMailer implements PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetMailer.class);

    private final JavaMailSender sender;
    private final String host;
    private final String from;
    private final String supportEmail;

    public SmtpPasswordResetMailer(JavaMailSender sender,
                                   @Value("${spring.mail.host:}") String host,
                                   @Value("${savenest.mail.from:}") String from,
                                   @Value("${savenest.mail.support-email:}") String supportEmail) {
        this.sender = sender;
        this.host = host;
        this.from = from;
        this.supportEmail = supportEmail;
    }

    @Async
    @Override
    public void sendResetCode(String to, String code, Duration validity) {
        String text = """
                Hello,

                Your SaveNest password reset code is:

                    %s

                This code expires in %d minutes.

                Do not share this code with anyone. SaveNest staff will never ask for it.

                If you did not request a password reset, you can safely ignore this email; \
                your password will not change.
                """.formatted(code, validity.toMinutes());
        send(to, "Your password reset code", text, "reset_code");
    }

    @Async
    @Override
    public void sendPasswordChanged(String to) {
        String contact = supportEmail.isBlank() ? "support" : "support (" + supportEmail + ")";
        String text = """
                Hello,

                Your password was changed successfully. If this was not you, contact %s immediately.

                For your security, you have been signed out of all devices.
                """.formatted(contact);
        send(to, "Your SaveNest password was changed", text, "password_changed");
    }

    private void send(String to, String subject, String text, String kind) {
        if (host.isBlank() || from.isBlank()) {
            log.warn("Mail is not configured (MAIL_HOST/MAIL_FROM); skipped {} email", kind);
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(text);
        try {
            sender.send(msg);
            log.info("Sent {} email", kind);
        } catch (MailException e) {
            // Never include the message body (it contains the code) or the recipient in logs.
            log.error("Failed to send {} email: {}", kind, e.getClass().getSimpleName());
        }
    }
}

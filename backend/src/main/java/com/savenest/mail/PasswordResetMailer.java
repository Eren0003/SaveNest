package com.savenest.mail;

import java.time.Duration;

/** Sends password-reset emails. Implementations must never log the code. */
public interface PasswordResetMailer {

    void sendResetCode(String to, String code, Duration validity);

    void sendPasswordChanged(String to);
}

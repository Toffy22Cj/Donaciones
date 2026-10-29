package com.traceability.app.infrastructure.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.net.URI;

/**
 * [Plan] Las comprobaciones de arranque comunes a los correos de la Enmienda 2 de ADR-050, las mismas que hace
 * {@link SmtpPasswordResetMailAdapter} (ADR-049 D1, DD-61): sin servidor SMTP, remitente o base de la web válida, no
 * arranca.
 */
final class MailSettings {

    private MailSettings() {
    }

    static JavaMailSender requireSender(ObjectProvider<JavaMailSender> sender) {
        JavaMailSender available = sender.getIfAvailable();
        if (available == null) {
            throw new IllegalStateException("Falta el servidor SMTP: SPRING_MAIL_HOST es obligatorio (ADR-049 D1)");
        }
        return available;
    }

    static String requireFrom(String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("TRACEABILITY_MAIL_FROM es obligatorio (ADR-049 D1)");
        }
        return from.strip();
    }

    static String requireBaseUrl(String baseUrl) {
        URI base = baseUrl == null ? null : URI.create(baseUrl.strip());
        if (base == null || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()))
                || base.getHost() == null || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalStateException("TRACEABILITY_WEB_BASE_URL debe ser una URL http(s) sin query ni fragmento");
        }
        return baseUrl.strip().replaceAll("/+$", "");
    }
}

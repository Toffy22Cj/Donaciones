package com.traceability.app.infrastructure.mail;

import identity.application.port.out.InvitationMailPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Correo de invitación por SMTP (ADR-049 D1, D3, D5). El enlace lleva el token en el <b>fragmento</b>
 * ({@code {base}/invitaciones#token=…}), que el navegador no envía al servidor: excepción consciente decidida por
 * Carlos. El cuerpo solo lleva el nombre de la organización, el rol, el enlace y la caducidad; nada de quien invita.
 * <p>
 * Sin servidor SMTP, remitente o URL base de la web, la aplicación no arranca (DD-61). Este adaptador nunca registra
 * el token, el email ni el cuerpo.
 */
@Component
public class SmtpInvitationMailAdapter implements InvitationMailPort {

    static final String PATH = "/invitaciones";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm 'UTC'",
            Locale.forLanguageTag("es"));

    private final JavaMailSender sender;
    private final String from;
    private final String baseUrl;

    public SmtpInvitationMailAdapter(ObjectProvider<JavaMailSender> sender,
                                     @Value("${traceability.mail.from}") String from,
                                     @Value("${traceability.web.base-url}") String baseUrl) {
        this.sender = sender.getIfAvailable();
        if (this.sender == null) {
            throw new IllegalStateException("Falta el servidor SMTP: SPRING_MAIL_HOST es obligatorio (ADR-049 D1)");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("TRACEABILITY_MAIL_FROM es obligatorio (ADR-049 D1)");
        }
        URI base = baseUrl == null ? null : URI.create(baseUrl.strip());
        if (base == null || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()))
                || base.getHost() == null || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalStateException("TRACEABILITY_WEB_BASE_URL debe ser una URL http(s) sin query ni fragmento");
        }
        this.from = from.strip();
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
    }

    /** {@code {base}/invitaciones#token=…}: el token nunca en la ruta ni en la query (ADR-049 D3). */
    String link(String token) {
        return baseUrl + PATH + "#token=" + token;
    }

    @Override
    public void send(InvitationMail mail) {
        String organization = mail.organizationName() == null || mail.organizationName().isBlank()
                ? "una organización" : mail.organizationName();
        String role = "ADMINISTRATOR".equals(mail.role()) ? "administrador" : "empleado";
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(mail.to());
        message.setSubject("Invitación a " + organization + " en PaxFide");
        message.setText("""
                Te han invitado a unirte a %s en PaxFide como %s.

                Para aceptar, inicia sesión con esta dirección de correo y abre este enlace:
                %s

                La invitación caduca el %s y solo se puede usar una vez.

                Si no esperabas esta invitación, ignora este correo.
                """.formatted(organization, role, link(mail.token()),
                DATE.format(mail.expiresAt().atOffset(ZoneOffset.UTC))));
        sender.send(message);
    }
}

package com.traceability.app.infrastructure.mail;

import com.traceability.app.config.PasswordResetMailExecutorConfig;
import identity.application.port.out.PasswordResetMailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Correo de recuperación por SMTP (ADR-050 D8, Q5 (a)). {@link #dispatch} entrega la tarea al ejecutor propio y vuelve
 * enseguida: la respuesta {@code 202} nunca espera al SMTP. El enlace lleva el token en el <b>fragmento</b>
 * ({@code {base}/recuperar#token=…}), que el navegador no envía al servidor, como las invitaciones (ADR-049 D3).
 * <p>
 * Si la cola está llena o el envío falla, solo se registran el {@code tokenId} y el tipo de fallo: nunca el token, el
 * correo ni el cuerpo. Usa la misma configuración que las invitaciones y, como ellas, sin ella no arranca (DD-61).
 */
@Component
public class SmtpPasswordResetMailAdapter implements PasswordResetMailPort {

    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetMailAdapter.class);
    static final String PATH = "/recuperar";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm 'UTC'",
            Locale.forLanguageTag("es"));

    private final JavaMailSender sender;
    private final TaskExecutor executor;
    private final String from;
    private final String baseUrl;

    public SmtpPasswordResetMailAdapter(ObjectProvider<JavaMailSender> sender,
                                        @Qualifier(PasswordResetMailExecutorConfig.EXECUTOR) TaskExecutor executor,
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
        this.executor = executor;
        this.from = from.strip();
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
    }

    /** {@code {base}/recuperar#token=…}: el token nunca en la ruta ni en la query (D8, test 18). */
    String link(String token) {
        return baseUrl + PATH + "#token=" + token;
    }

    @Override
    public void dispatch(PasswordResetMail mail) {
        try {
            executor.execute(() -> send(mail));
        } catch (RuntimeException rejected) {
            // cola llena (TaskRejectedException) u otro rechazo del ejecutor: la petición ya respondió y no se reintenta
            log.warn("password-reset.mail.rejected tokenId={} cause={}", mail.tokenId(), rejected.getClass().getSimpleName());
        }
    }

    private void send(PasswordResetMail mail) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(mail.to());
            message.setSubject("Recupera tu contraseña de PaxFide");
            message.setText("""
                    Alguien pidió restablecer la contraseña de tu cuenta de PaxFide.

                    Para elegir una nueva, abre este enlace:
                    %s

                    El enlace caduca el %s y solo se puede usar una vez.

                    Si no lo pediste, ignora este correo: tu contraseña no cambia.
                    """.formatted(link(mail.token()), TIME.format(mail.expiresAt().atOffset(ZoneOffset.UTC))));
            sender.send(message);
        } catch (RuntimeException e) {
            log.warn("password-reset.mail.failed tokenId={} cause={}", mail.tokenId(), e.getClass().getSimpleName());
        }
    }
}

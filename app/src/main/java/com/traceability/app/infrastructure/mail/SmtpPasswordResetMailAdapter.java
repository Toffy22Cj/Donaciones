package com.traceability.app.infrastructure.mail;

import com.traceability.app.config.PasswordResetMailExecutorConfig;
import identity.application.port.out.PasswordResetMailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Map;

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

    private final JavaMailSender sender;
    private final TaskExecutor executor;
    private final String from;
    private final String baseUrl;
    private final MailTemplates templates;

    public SmtpPasswordResetMailAdapter(ObjectProvider<JavaMailSender> sender,
                                        @Qualifier(PasswordResetMailExecutorConfig.EXECUTOR) TaskExecutor executor,
                                        @Value("${traceability.mail.from}") String from,
                                        @Value("${traceability.web.base-url}") String baseUrl,
                                        MailTemplates templates) {
        this.templates = templates;
        this.sender = MailSettings.requireSender(sender);
        this.executor = executor;
        this.from = MailSettings.requireFrom(from);
        this.baseUrl = MailSettings.requireBaseUrl(baseUrl);
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

    MailTemplates.Body body(PasswordResetMail mail) {
        return templates.render("recuperacion", Map.of(
                "title", "Restablece tu contraseña",
                "kicker", "Recuperar contraseña",
                "preheader", "Pediste cambiar tu contraseña. El enlace vence pronto.",
                "recipientEmail", mail.to(),
                "expiresAt", MailSettings.when(mail.expiresAt()),
                "actionUrl", link(mail.token()),
                "actionLabel", "Crear una contraseña nueva",
                "closing", "Si no lo pediste, ignora este correo: tu contraseña no cambia y nadie puede cambiarla sin "
                        + "este enlace.",
                "reason", "Recibiste este correo porque alguien pidió restablecer la contraseña de esta cuenta."));
    }

    private void send(PasswordResetMail mail) {
        try {
            BrandedMail.send(sender, from, mail.to(), "Recupera tu contraseña de PaxFide", body(mail));
        } catch (RuntimeException e) {
            log.warn("password-reset.mail.failed tokenId={} cause={}", mail.tokenId(), e.getClass().getSimpleName());
        }
    }
}

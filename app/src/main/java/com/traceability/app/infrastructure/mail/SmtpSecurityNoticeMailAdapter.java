package com.traceability.app.infrastructure.mail;

import com.traceability.app.config.PasswordResetMailExecutorConfig;
import identity.application.port.out.SecurityNoticeMailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * [Plan] Avisos de seguridad por SMTP (ADR-050 Enmienda 2, E2-4): cambio de contraseña, recuperación confirmada y
 * desactivación propia. <b>Sin enlace con token</b>, sin contraseñas, sin IP ni dispositivo (Q5); la hora en
 * America/Bogota (P-E2V-10). Mismo ejecutor que la recuperación, sin reintentos (Q5 (a) de ADR-050). Un fallo no
 * cambia la operación, ya confirmada: se registran el {@code accountId} y el tipo de aviso, nunca el correo.
 */
@Component
public class SmtpSecurityNoticeMailAdapter implements SecurityNoticeMailPort {

    private static final Logger log = LoggerFactory.getLogger(SmtpSecurityNoticeMailAdapter.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm '(hora de Colombia)'",
            Locale.forLanguageTag("es"));
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final JavaMailSender sender;
    private final TaskExecutor executor;
    private final String from;
    private final String baseUrl;

    public SmtpSecurityNoticeMailAdapter(ObjectProvider<JavaMailSender> sender,
                                         @Qualifier(PasswordResetMailExecutorConfig.EXECUTOR) TaskExecutor executor,
                                         @Value("${traceability.mail.from}") String from,
                                         @Value("${traceability.web.base-url}") String baseUrl) {
        this.sender = MailSettings.requireSender(sender);
        this.executor = executor;
        this.from = MailSettings.requireFrom(from);
        this.baseUrl = MailSettings.requireBaseUrl(baseUrl);
    }

    @Override
    public void dispatch(SecurityNotice notice) {
        try {
            executor.execute(() -> send(notice));
        } catch (RuntimeException rejected) {
            log.warn("security-notice.mail.rejected accountId={} kind={} cause={}", notice.accountId(), notice.kind(),
                    rejected.getClass().getSimpleName());
        }
    }

    private void send(SecurityNotice notice) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(notice.to());
            message.setSubject(subject(notice.kind()));
            message.setText(text(notice));
            sender.send(message);
        } catch (RuntimeException e) {
            log.warn("security-notice.mail.failed accountId={} kind={} cause={}", notice.accountId(), notice.kind(),
                    e.getClass().getSimpleName());
        }
    }

    /** E2-4: los asuntos de la tabla. */
    static String subject(SecurityNotice.Kind kind) {
        return switch (kind) {
            case PASSWORD_CHANGED -> "Tu contraseña de PaxFide cambió";
            case PASSWORD_RESET -> "Restableciste tu contraseña de PaxFide";
            case ACCOUNT_DEACTIVATED -> "Desactivaste tu cuenta de PaxFide";
        };
    }

    private String text(SecurityNotice notice) {
        String when = TIME.format(notice.occurredAt().atZone(BOGOTA));
        return switch (notice.kind()) {
            case PASSWORD_CHANGED -> """
                    La contraseña de tu cuenta de PaxFide cambió el %s. Se cerraron las demás sesiones.

                    Si no fuiste tú, recupera tu contraseña aquí: %s/recuperar
                    """.formatted(when, baseUrl);
            case PASSWORD_RESET -> """
                    Restableciste la contraseña de tu cuenta de PaxFide el %s. Se cerraron las demás sesiones.

                    Si no fuiste tú, recupera tu contraseña aquí: %s/recuperar
                    """.formatted(when, baseUrl);
            case ACCOUNT_DEACTIVATED -> """
                    Tu cuenta de PaxFide se desactivó el %s.

                    Para volver a activarla, escribe a la plataforma.
                    """.formatted(when);
        };
    }
}

package com.traceability.app.infrastructure.mail;

import com.traceability.app.config.PasswordResetMailExecutorConfig;
import identity.application.port.out.SecurityNoticeMailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * [Plan] Avisos de seguridad por SMTP (ADR-050 Enmienda 2, E2-4): cambio de contraseña, recuperación confirmada y
 * desactivación propia; y, por la ampliación de Manuel en #164, vincular y desvincular Google. <b>Sin enlace con token</b>, sin contraseñas, sin IP ni dispositivo (Q5); la hora en
 * America/Bogota (P-E2V-10). Mismo ejecutor que la recuperación, sin reintentos (Q5 (a) de ADR-050). Un fallo no
 * cambia la operación, ya confirmada: se registran el {@code accountId} y el tipo de aviso, nunca el correo.
 */
@Component
public class SmtpSecurityNoticeMailAdapter implements SecurityNoticeMailPort {

    private static final Logger log = LoggerFactory.getLogger(SmtpSecurityNoticeMailAdapter.class);

    private final JavaMailSender sender;
    private final TaskExecutor executor;
    private final String from;
    private final String baseUrl;
    private final MailTemplates templates;

    public SmtpSecurityNoticeMailAdapter(ObjectProvider<JavaMailSender> sender,
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
            BrandedMail.send(sender, from, notice.to(), subject(notice.kind()), body(notice));
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
            case GOOGLE_LINKED -> "Vinculaste tu cuenta de Google a PaxFide";
            case GOOGLE_UNLINKED -> "Desvinculaste tu cuenta de Google de PaxFide";
        };
    }

    /** Sin enlace con token, contraseñas, IP ni dispositivo (E2-4, Q5): solo qué pasó, cuándo y qué hacer. */
    MailTemplates.Body body(SecurityNotice notice) {
        String recover = baseUrl + "/recuperar";
        String ifNotYou = "Si no fuiste tú, recupera tu contraseña cuanto antes.";
        Map<String, String> v = new HashMap<>();
        v.put("kicker", "Aviso de seguridad");
        v.put("recipientEmail", notice.to());
        v.put("occurredAt", MailSettings.when(notice.occurredAt()));
        v.put("closing", "Si fuiste tú, no tienes que hacer nada.");
        v.put("reason", "Recibiste este aviso porque hubo un cambio de seguridad en tu cuenta de PaxFide.");
        v.put("actionUrl", recover);
        v.put("actionLabel", "Recuperar mi contraseña");
        v.put("advice", ifNotYou);
        switch (notice.kind()) {
            case PASSWORD_CHANGED -> {
                v.put("title", "Tu contraseña cambió");
                v.put("message", "La contraseña de tu cuenta de PaxFide cambió. Se cerraron las demás sesiones.");
            }
            case PASSWORD_RESET -> {
                v.put("title", "Restableciste tu contraseña");
                v.put("message", "Restableciste la contraseña de tu cuenta de PaxFide. Se cerraron las demás sesiones.");
            }
            case ACCOUNT_DEACTIVATED -> {
                v.put("title", "Desactivaste tu cuenta");
                v.put("message", "Tu cuenta de PaxFide se desactivó.");
                v.put("advice", "Para volver a activarla, escribe a la plataforma.");
                v.put("actionUrl", "");
                v.put("closing", "Si no fuiste tú, escribe a la plataforma cuanto antes.");
            }
            case GOOGLE_LINKED -> {
                v.put("title", "Vinculaste tu cuenta de Google");
                v.put("message", "Vinculaste una cuenta de Google a tu cuenta de PaxFide. Desde ahora puedes iniciar "
                        + "sesión con ella.");
                v.put("advice", ifNotYou + " Después, desvincula Google desde tu perfil.");
            }
            case GOOGLE_UNLINKED -> {
                v.put("title", "Desvinculaste tu cuenta de Google");
                v.put("message", "Desvinculaste la cuenta de Google de tu cuenta de PaxFide. Ya no puedes iniciar "
                        + "sesión con ella.");
            }
        }
        v.put("preheader", v.get("message"));
        return templates.render("aviso-seguridad", v);
    }
}

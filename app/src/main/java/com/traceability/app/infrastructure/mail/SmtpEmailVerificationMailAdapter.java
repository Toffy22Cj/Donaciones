package com.traceability.app.infrastructure.mail;

import com.traceability.app.config.PasswordResetMailExecutorConfig;
import identity.application.port.out.EmailVerificationMailPort;
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
 * [Plan] Correo de verificación por SMTP (ADR-050 Enmienda 2, E2-2), con la forma de {@link SmtpPasswordResetMailAdapter}
 * y su mismo ejecutor (P-E2V-5): {@link #dispatch} entrega la tarea y vuelve enseguida. El enlace lleva el token en el
 * <b>fragmento</b> ({@code {base}/verificar-correo#token=…}), que el navegador no envía al servidor (ADR-049 D3).
 * <p>
 * Si la cola está llena o el envío falla, solo se registran el {@code tokenId} y el tipo de fallo: nunca el token, el
 * correo ni el cuerpo. La misma configuración que las invitaciones; sin ella no arranca (DD-61).
 */
@Component
public class SmtpEmailVerificationMailAdapter implements EmailVerificationMailPort {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailVerificationMailAdapter.class);
    static final String PATH = "/verificar-correo";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm '(hora de Colombia)'",
            Locale.forLanguageTag("es"));
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final JavaMailSender sender;
    private final TaskExecutor executor;
    private final String from;
    private final String baseUrl;

    public SmtpEmailVerificationMailAdapter(ObjectProvider<JavaMailSender> sender,
                                            @Qualifier(PasswordResetMailExecutorConfig.EXECUTOR) TaskExecutor executor,
                                            @Value("${traceability.mail.from}") String from,
                                            @Value("${traceability.web.base-url}") String baseUrl) {
        this.sender = MailSettings.requireSender(sender);
        this.executor = executor;
        this.from = MailSettings.requireFrom(from);
        this.baseUrl = MailSettings.requireBaseUrl(baseUrl);
    }

    /** {@code {base}/verificar-correo#token=…}: el token nunca en la ruta ni en la query (test 10). */
    String link(String token) {
        return baseUrl + PATH + "#token=" + token;
    }

    @Override
    public void dispatch(EmailVerificationMail mail) {
        try {
            executor.execute(() -> send(mail));
        } catch (RuntimeException rejected) {
            log.warn("email-verification.mail.rejected tokenId={} cause={}", mail.tokenId(), rejected.getClass().getSimpleName());
        }
    }

    private void send(EmailVerificationMail mail) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(mail.to());
            message.setSubject("Confirma tu correo en PaxFide");
            message.setText("""
                    Para terminar de crear tu cuenta de PaxFide, confirma que este correo es tuyo con este enlace:
                    %s

                    El enlace caduca el %s y solo se puede usar una vez.

                    Si no creaste una cuenta en PaxFide, ignora este correo.
                    """.formatted(link(mail.token()), TIME.format(mail.expiresAt().atZone(BOGOTA))));
            sender.send(message);
        } catch (RuntimeException e) {
            log.warn("email-verification.mail.failed tokenId={} cause={}", mail.tokenId(), e.getClass().getSimpleName());
        }
    }
}

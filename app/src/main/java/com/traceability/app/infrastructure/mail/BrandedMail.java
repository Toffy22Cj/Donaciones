package com.traceability.app.infrastructure.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

/**
 * Envía un correo con su parte de texto y su parte HTML ({@code multipart/alternative}): el cliente muestra la HTML y,
 * si no puede, el texto. Un fallo al preparar el mensaje es una {@link MailPreparationException} (de tiempo de
 * ejecución), igual que un fallo de envío: quien llama ya trata cualquier {@code RuntimeException} como envío fallido.
 */
final class BrandedMail {

    private BrandedMail() {
    }

    static void send(JavaMailSender sender, String from, String to, String subject, MailTemplates.Body body) {
        MimeMessage message = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body.text(), body.html());
        } catch (MessagingException e) {
            throw new MailPreparationException("No se pudo preparar el correo", e);
        }
        sender.send(message);
    }
}

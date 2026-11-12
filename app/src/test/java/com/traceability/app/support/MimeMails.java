package com.traceability.app.support;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.SimpleMailMessage;

import java.io.IOException;
import java.util.Arrays;

/**
 * Los correos salen como {@code multipart/alternative} (texto y HTML). Los senders de test los guardan como un
 * {@link SimpleMailMessage} con la <b>parte de texto</b>, así las comprobaciones sobre el texto siguen igual; la parte
 * HTML se comprueba aparte ({@link #html(MimeMessage)}).
 */
public final class MimeMails {

    private MimeMails() {
    }

    public static SimpleMailMessage toSimple(MimeMessage mime) {
        try {
            SimpleMailMessage simple = new SimpleMailMessage();
            simple.setFrom(((InternetAddress) mime.getFrom()[0]).getAddress());
            simple.setTo(Arrays.stream(mime.getRecipients(Message.RecipientType.TO))
                    .map(a -> ((InternetAddress) a).getAddress()).toArray(String[]::new));
            simple.setSubject(mime.getSubject());
            simple.setText(part(mime, "text/plain"));
            return simple;
        } catch (MessagingException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String html(MimeMessage mime) {
        try {
            return part(mime, "text/html");
        } catch (MessagingException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String part(Part part, String type) throws MessagingException, IOException {
        if (part.isMimeType(type)) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart body = multipart.getBodyPart(i);
                String found = part(body, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}

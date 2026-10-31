package com.traceability.app.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [Plan] Captura de correos para los tests HTTP de {@code app} (ADR-050 Enmienda 2, plan §2): un {@code JavaMailSender}
 * de test que guarda lo enviado, puede bloquearse con un latch y falla para los destinatarios {@code smtp-fail…}. Con
 * {@link #verificationToken(String)}, los tests que registran por la ruta pública toman el token del correo de
 * verificación y lo confirman por la ruta pública antes de iniciar sesión.
 */
@TestConfiguration
public class CapturedMail {

    private static final Pattern VERIFICATION_LINK = Pattern.compile("https://\\S+/verificar-correo#token=([A-Za-z0-9_-]+)");

    public static final Sender SENDER = new Sender();

    @Bean
    @Primary
    JavaMailSender capturingMailSender() {
        return SENDER;
    }

    public static final class Sender extends JavaMailSenderImpl {
        public final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();
        public volatile CountDownLatch gate;

        @Override
        public void send(SimpleMailMessage message) {
            send(new SimpleMailMessage[] {message});
        }

        @Override
        public void send(SimpleMailMessage... messages) {
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            for (SimpleMailMessage m : messages) {
                if (m.getTo()[0].startsWith("smtp-fail")) {
                    throw new MailSendException("servidor caído");
                }
                sent.add(m);
            }
        }
    }

    /** Espera (el envío es asíncrono) a que haya al menos {@code count} correos a {@code to} y los devuelve. */
    public static List<SimpleMailMessage> mailsTo(String to, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<SimpleMailMessage> found = to(to);
            if (found.size() >= count) {
                return found;
            }
            Thread.sleep(25);
        }
        return to(to);
    }

    public static List<SimpleMailMessage> to(String to) {
        return SENDER.sent.stream().filter(m -> m.getTo()[0].equals(to)).toList();
    }

    /** El token del último correo de verificación enviado a {@code email}; espera a que llegue. */
    public static String verificationToken(String email) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<SimpleMailMessage> found = to(email).stream()
                    .filter(m -> VERIFICATION_LINK.matcher(m.getText()).find()).toList();
            if (!found.isEmpty()) {
                Matcher m = VERIFICATION_LINK.matcher(found.get(found.size() - 1).getText());
                m.find();
                return m.group(1);
            }
            Thread.sleep(25);
        }
        throw new AssertionError("No llegó el correo de verificación");
    }

    /**
     * Verifica el correo de una cuenta registrada por la ruta pública, como haría la persona: toma el token del correo
     * capturado y lo confirma en {@code POST /api/v1/auth/email-verification/confirm} (ADR-050 Enmienda 2, E2-2).
     */
    public static void confirmVerification(int port, String email) throws Exception {
        String token = verificationToken(email);
        java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port
                                + "/api/v1/auth/email-verification/confirm"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}"))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 204) {
            throw new AssertionError("La confirmación del correo respondió " + r.statusCode());
        }
    }
}

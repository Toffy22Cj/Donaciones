package com.traceability.app.infrastructure.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plantillas de los correos con el diseño de PaxFide ({@code resources/mail/}, ver su LEEME). Cada correo es una pieza
 * HTML y una de texto dentro del marco común, y se envían juntas ({@code multipart/alternative}).
 * <p>
 * Sintaxis mínima, sin dependencias: {@code {{nombre}}} (escapado en HTML), {@code {{{contenido}}}} (la pieza ya
 * renderizada, solo en el marco) y {@code {{#nombre}}…{{/nombre}}} (el bloque solo si el valor no está vacío). Las
 * plantillas se cargan al arrancar: si falta una, la aplicación no arranca. Un marcador sin valor es un error.
 * <p>
 * Opcionales por entorno: {@code TRACEABILITY_MAIL_LOGO_URL} (PNG público por https) y
 * {@code TRACEABILITY_MAIL_SUPPORT_EMAIL}. Sin ellos, la cabecera lleva solo la marca en texto y el pie no ofrece
 * contacto.
 */
@Component
public class MailTemplates {

    static final String[] MAILS = {"verificacion", "invitacion", "recuperacion", "aviso-seguridad"};

    private static final Pattern SECTION = Pattern.compile("\\{\\{#([a-zA-Z]+)}}(.*?)\\{\\{/\\1}}", Pattern.DOTALL);
    private static final Pattern RAW = Pattern.compile("\\{\\{\\{([a-zA-Z]+)}}}");
    private static final Pattern VALUE = Pattern.compile("\\{\\{([a-zA-Z]+)}}");
    private static final Pattern LEFTOVER = Pattern.compile("\\{\\{[^}]*}}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@<>\"']+@[^\\s@<>\"']+\\.[^\\s@<>\"']+");

    private final Map<String, String> files = new HashMap<>();
    private final String logoUrl;
    private final String supportEmail;

    public MailTemplates(@Value("${traceability.mail.logo-url:}") String logoUrl,
                         @Value("${traceability.mail.support-email:}") String supportEmail) {
        this.logoUrl = checkLogo(logoUrl == null ? "" : logoUrl.strip());
        this.supportEmail = checkSupport(supportEmail == null ? "" : supportEmail.strip());
        for (String frame : new String[] {"marco.html", "marco.txt"}) {
            files.put(frame, load(frame));
        }
        for (String mail : MAILS) {
            files.put(mail + ".html", load(mail + ".html"));
            files.put(mail + ".txt", load(mail + ".txt"));
        }
    }

    /** El cuerpo de un correo: la parte de texto y la HTML. */
    public record Body(String text, String html) {
    }

    /**
     * Renderiza el correo {@code mail} con sus valores y los del marco ({@code title}, {@code kicker},
     * {@code preheader}, {@code actionUrl}, {@code actionLabel}, {@code closing}, {@code reason}).
     */
    public Body render(String mail, Map<String, String> values) {
        Map<String, String> all = new LinkedHashMap<>(values);
        all.putIfAbsent("logoUrl", logoUrl);
        all.putIfAbsent("supportEmail", supportEmail);
        all.putIfAbsent("actionUrl", "");
        all.putIfAbsent("actionLabel", "");
        String text = fill(files.get("marco.txt"), all, fill(file(mail + ".txt"), all, null, false), false);
        String html = fill(files.get("marco.html"), all, fill(file(mail + ".html"), all, null, true), true);
        return new Body(tidy(text), html);
    }

    private String file(String name) {
        String content = files.get(name);
        if (content == null) {
            throw new IllegalArgumentException("No hay plantilla de correo " + name);
        }
        return content;
    }

    private static final String SLOT = "\u0000contenido\u0000";

    /**
     * Rellena una plantilla: primero los bloques opcionales, luego los valores y, al final, la pieza ya renderizada
     * ({@code content}), que no vuelve a pasar por la sustitución: un valor con {@code {{…}}} no se interpreta.
     */
    private static String fill(String template, Map<String, String> values, String content, boolean html) {
        String out = SECTION.matcher(template).replaceAll(m ->
                Matcher.quoteReplacement(values.getOrDefault(m.group(1), "").isEmpty() ? "" : m.group(2)));
        out = RAW.matcher(out).replaceAll(m -> {
            if (!"content".equals(m.group(1)) || content == null) {
                throw new IllegalStateException("Marcador sin contenido en la plantilla de correo: " + m.group());
            }
            return Matcher.quoteReplacement(SLOT);
        });
        out = VALUE.matcher(out).replaceAll(m -> {
            String v = values.get(m.group(1));
            if (v == null) {
                throw new IllegalStateException("Falta el valor {{" + m.group(1) + "}} de una plantilla de correo");
            }
            return Matcher.quoteReplacement(SLOT_SAFE.matcher(html ? escape(v) : v).replaceAll(""));
        });
        Matcher left = LEFTOVER.matcher(template);
        while (left.find()) {
            String marker = left.group();
            if (!marker.startsWith("{{#") && !marker.startsWith("{{/") && !VALUE.matcher(marker).matches()
                    && !marker.equals("{{{content}}")) {
                throw new IllegalStateException("Marcador mal escrito en una plantilla de correo: " + marker);
            }
        }
        return content == null ? out : out.replace(SLOT, html ? content : content.strip());
    }

    private static final Pattern SLOT_SAFE = Pattern.compile("\u0000");

    /** Escapa los cinco caracteres con significado en HTML: un valor nunca abre una etiqueta ni cierra un atributo. */
    static String escape(String value) {
        StringBuilder b = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Sin más de una línea en blanco seguida (los bloques opcionales vacíos dejan huecos). */
    private static String tidy(String text) {
        return text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip() + "\n";
    }

    private static String checkLogo(String url) {
        if (url.isEmpty()) {
            return url;
        }
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalStateException("TRACEABILITY_MAIL_LOGO_URL debe ser una URL https pública (un PNG)");
        }
        return url;
    }

    private static String checkSupport(String email) {
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) {
            throw new IllegalStateException("TRACEABILITY_MAIL_SUPPORT_EMAIL no es un correo válido");
        }
        return email;
    }

    private static String load(String name) {
        ClassPathResource resource = new ClassPathResource("mail/" + name);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Falta la plantilla de correo mail/" + name, e);
        }
    }
}

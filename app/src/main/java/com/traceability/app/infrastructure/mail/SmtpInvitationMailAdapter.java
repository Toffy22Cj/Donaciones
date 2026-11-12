package com.traceability.app.infrastructure.mail;

import identity.application.port.out.InvitationMailPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Map;

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

    private final JavaMailSender sender;
    private final String from;
    private final String baseUrl;
    private final MailTemplates templates;

    public SmtpInvitationMailAdapter(ObjectProvider<JavaMailSender> sender,
                                     @Value("${traceability.mail.from}") String from,
                                     @Value("${traceability.web.base-url}") String baseUrl,
                                     MailTemplates templates) {
        this.templates = templates;
        this.sender = MailSettings.requireSender(sender);
        this.from = MailSettings.requireFrom(from);
        this.baseUrl = MailSettings.requireBaseUrl(baseUrl);
    }

    /** {@code {base}/invitaciones#token=…}: el token nunca en la ruta ni en la query (ADR-049 D3). */
    String link(String token) {
        return baseUrl + PATH + "#token=" + token;
    }

    @Override
    public void send(InvitationMail mail) {
        BrandedMail.send(sender, from, mail.to(), "Invitación a " + organization(mail) + " en PaxFide", body(mail));
    }

    private static String organization(InvitationMail mail) {
        return mail.organizationName() == null || mail.organizationName().isBlank()
                ? "una organización" : mail.organizationName();
    }

    /** Sin datos de quien invita (ADR-049 D5): organización, rol, para quién, enlace y caducidad. */
    MailTemplates.Body body(InvitationMail mail) {
        boolean admin = "ADMINISTRATOR".equals(mail.role());
        String organization = organization(mail);
        return templates.render("invitacion", Map.ofEntries(
                Map.entry("title", "Te invitaron a unirte a " + organization),
                Map.entry("kicker", "Invitación"),
                Map.entry("preheader", "Te invitaron como " + (admin ? "administrador" : "empleado")
                        + ". Acepta antes del " + MailSettings.when(mail.expiresAt()) + "."),
                Map.entry("organizationName", organization),
                Map.entry("roleName", admin ? "administrador" : "empleado"),
                Map.entry("roleLabel", admin ? "Administrador" : "Empleado"),
                Map.entry("roleDescription", admin
                        ? "Gestionar el equipo y las convocatorias de la organización, y asignar a sus responsables."
                        : "Llevar la convocatoria que te asignen: registrar sus lotes, sus movimientos y sus entregas."),
                Map.entry("recipientEmail", mail.to()),
                Map.entry("expiresAt", MailSettings.when(mail.expiresAt())),
                Map.entry("actionUrl", link(mail.token())),
                Map.entry("actionLabel", "Aceptar invitación"),
                Map.entry("closing", "Si no esperabas esta invitación, ignora este correo: sin aceptar, no te unes a "
                        + "ninguna organización."),
                Map.entry("reason", "Recibiste este correo porque " + organization + " te invitó desde PaxFide.")));
    }
}

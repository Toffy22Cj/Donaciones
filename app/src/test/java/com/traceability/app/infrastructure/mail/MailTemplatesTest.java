package com.traceability.app.infrastructure.mail;

import identity.application.port.out.EmailVerificationMailPort.EmailVerificationMail;
import identity.application.port.out.InvitationMailPort.InvitationMail;
import identity.application.port.out.PasswordResetMailPort.PasswordResetMail;
import identity.application.port.out.SecurityNoticeMailPort.SecurityNotice;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las plantillas de los correos (resources/mail): cada correo sale con texto y HTML, el enlace con el token solo en el
 * fragmento, los valores escapados en HTML, sin datos de quien invita (ADR-049 D5) y los avisos sin token (E2-4).
 */
class MailTemplatesTest {

    private static final String BASE = "https://web.x.test";
    private static final Instant WHEN = Instant.parse("2026-11-12T20:30:00Z");
    private static final String TOKEN = "tok_ABC-123";

    /** Guarda los mensajes en vez de enviarlos. */
    static final class Captor extends JavaMailSenderImpl {
        final List<MimeMessage> sent = new ArrayList<>();
        @Override public void send(MimeMessage... messages) {
            for (MimeMessage m : messages) {
                try { m.saveChanges(); } catch (Exception e) { throw new IllegalStateException(e); }
                sent.add(m);
            }
        }
    }

    private static org.springframework.beans.factory.ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("mailSender", sender);
        return beans.getBeanProvider(JavaMailSender.class);
    }

    private static String text(MimeMessage m) {
        return com.traceability.app.support.MimeMails.toSimple(m).getText();
    }

    private static String html(MimeMessage m) {
        return com.traceability.app.support.MimeMails.html(m);
    }

    @Test
    void theInvitation_goesAsTextAndHtml_withTheDesign_theTokenOnlyInTheFragment_andTheValuesEscaped() throws Exception {
        Captor captor = new Captor();
        SmtpInvitationMailAdapter adapter = new SmtpInvitationMailAdapter(provider(captor), "no-reply@x.test", BASE,
                new MailTemplates("https://cdn.x.test/logo.png", "soporte@x.test"));
        adapter.send(new InvitationMail("inv1", "laura@x.test", "Fundación <b>A&B</b>", "ADMINISTRATOR", TOKEN,
                WHEN));

        MimeMessage m = captor.sent.get(0);
        assertThat(m.getSubject()).isEqualTo("Invitación a Fundación <b>A&B</b> en PaxFide");
        assertThat(m.getContentType()).startsWith("multipart/");
        String text = text(m);
        String html = html(m);
        assertThat(text).contains(BASE + "/invitaciones#token=" + TOKEN).contains("administrador")
                .contains("caduca el").contains("Fundación <b>A&B</b>").contains("soporte@x.test")
                .doesNotContain("{{").doesNotContain("?token");
        assertThat(html).contains("href=\"" + BASE + "/invitaciones#token=" + TOKEN + "\"")
                .contains("Fundación &lt;b&gt;A&amp;B&lt;/b&gt;").doesNotContain("<b>A&B</b>")
                .contains("Aceptar invitación").contains("https://cdn.x.test/logo.png")
                .contains("mailto:soporte@x.test").contains("prefers-color-scheme:dark").doesNotContain("{{");
        // ADR-049 D5: nada de quien invita
        assertThat(html).doesNotContain("te invitó a formar parte");
    }

    @Test
    void withoutLogoOrSupport_theHeaderIsOnlyTheWordmark_andTheFooterHasNoContact() {
        MailTemplates templates = new MailTemplates("", "");
        SmtpEmailVerificationMailAdapter adapter = new SmtpEmailVerificationMailAdapter(
                provider(new Captor()), new SyncTaskExecutor(), "no-reply@x.test", BASE, templates);
        MailTemplates.Body body = adapter.body(new EmailVerificationMail("t1", "ana@x.test", TOKEN, WHEN));
        assertThat(body.html()).doesNotContain("<img").doesNotContain("mailto:").contains("Pax<span");
        assertThat(body.text()).doesNotContain("¿Dudas?");
        assertThat(body.html()).contains(BASE + "/verificar-correo#token=" + TOKEN).contains("Confirmar mi correo")
                .contains("(hora de Colombia)");
        assertThat(body.text()).containsPattern("https://web\\.x\\.test/verificar-correo#token=" + TOKEN);
    }

    @Test
    void theRecovery_keepsItsTextAndLink() {
        SmtpPasswordResetMailAdapter adapter = new SmtpPasswordResetMailAdapter(provider(new Captor()),
                new SyncTaskExecutor(), "no-reply@x.test", BASE, new MailTemplates("", ""));
        MailTemplates.Body body = adapter.body(new PasswordResetMail("t1", "ana@x.test", TOKEN, WHEN));
        assertThat(body.text()).contains(BASE + "/recuperar#token=" + TOKEN)
                .contains("Si no lo pediste, ignora este correo").contains("caduca el");
        assertThat(body.html()).contains("Crear una contraseña nueva").contains("ana@x.test");
    }

    @Test
    void theSecurityNotices_neverCarryAToken() {
        SmtpSecurityNoticeMailAdapter adapter = new SmtpSecurityNoticeMailAdapter(provider(new Captor()),
                new SyncTaskExecutor(), "no-reply@x.test", BASE, new MailTemplates("", "soporte@x.test"));
        for (SecurityNotice.Kind kind : SecurityNotice.Kind.values()) {
            MailTemplates.Body body = adapter.body(new SecurityNotice("acc1", "ana@x.test", kind, WHEN));
            assertThat(body.text()).as(kind.name()).doesNotContain("#token=").doesNotContain("{{");
            assertThat(body.html()).as(kind.name()).doesNotContain("#token=").doesNotContain("{{")
                    .contains("Aviso de seguridad");
        }
        MailTemplates.Body deactivated = adapter.body(new SecurityNotice("acc1", "ana@x.test",
                SecurityNotice.Kind.ACCOUNT_DEACTIVATED, WHEN));
        assertThat(deactivated.html()).doesNotContain("Recuperar mi contraseña");
    }

    @Test
    void aValueThatLooksLikeAMarker_isNotInterpreted() {
        MailTemplates.Body body = new MailTemplates("", "").render("invitacion", Map.ofEntries(
                Map.entry("title", "t"), Map.entry("kicker", "k"), Map.entry("preheader", "p"),
                Map.entry("organizationName", "{{actionUrl}} {{{content}}}"), Map.entry("roleName", "empleado"),
                Map.entry("roleLabel", "Empleado"), Map.entry("roleDescription", "d"),
                Map.entry("recipientEmail", "a@x.test"), Map.entry("expiresAt", "mañana"),
                Map.entry("closing", "c"), Map.entry("reason", "r")));
        assertThat(body.text()).contains("{{actionUrl}} {{{content}}}");
    }

    @Test
    void aMissingValue_orABadOptionalSetting_failsClearly() {
        assertThatThrownBy(() -> new MailTemplates("", "").render("verificacion", Map.of("title", "x")))
                .hasMessageContaining("Falta el valor");
        assertThatThrownBy(() -> new MailTemplates("http://x.test/logo.png", ""))
                .hasMessageContaining("TRACEABILITY_MAIL_LOGO_URL");
        assertThatThrownBy(() -> new MailTemplates("", "no-es-un-correo"))
                .hasMessageContaining("TRACEABILITY_MAIL_SUPPORT_EMAIL");
    }
}

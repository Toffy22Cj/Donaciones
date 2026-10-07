package com.traceability.app.infrastructure.mail;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-049 D1 y D3 (DD-61): fail-fast sin SMTP, remitente o URL base; el token solo en el fragmento. */
class SmtpInvitationMailAdapterTest {

    private static StaticListableBeanFactory with(JavaMailSender sender) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (sender != null) beans.addBean("mailSender", sender);
        return beans;
    }

    @Test
    void withoutAnSmtpServer_orSender_orWebBaseUrl_theApplicationDoesNotStart() {
        assertThatThrownBy(() -> new SmtpInvitationMailAdapter(with(null).getBeanProvider(JavaMailSender.class),
                "no-reply@x.test", "https://web.x.test"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SPRING_MAIL_HOST");
        JavaMailSender sender = new JavaMailSenderImpl();
        assertThatThrownBy(() -> new SmtpInvitationMailAdapter(with(sender).getBeanProvider(JavaMailSender.class),
                " ", "https://web.x.test")).hasMessageContaining("TRACEABILITY_MAIL_FROM");
        for (String bad : new String[] {"", "web.x.test", "ftp://web.x.test", "https://web.x.test/?a=1",
                "https://web.x.test/#x"}) {
            assertThatThrownBy(() -> new SmtpInvitationMailAdapter(with(sender).getBeanProvider(JavaMailSender.class),
                    "no-reply@x.test", bad)).as(bad).hasMessageContaining("TRACEABILITY_WEB_BASE_URL");
        }
    }

    @Test
    void theLink_carriesTheTokenOnlyInTheFragment() {
        SmtpInvitationMailAdapter adapter = new SmtpInvitationMailAdapter(
                with(new JavaMailSenderImpl()).getBeanProvider(JavaMailSender.class), "no-reply@x.test", "https://web.x.test/app/");
        assertThat(adapter.link("tok_EN-1")).isEqualTo("https://web.x.test/app/invitaciones#token=tok_EN-1");
    }
}

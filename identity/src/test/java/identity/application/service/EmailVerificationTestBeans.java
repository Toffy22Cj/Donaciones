package identity.application.service;

import identity.application.port.out.EmailVerificationMailPort;
import identity.application.port.out.SecurityNoticeMailPort;
import identity.infrastructure.persistence.mongo.repositories.MongoEmailVerificationTokenRepositoryAdapter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * [Plan] Lo que los tests de {@code identity} necesitan desde la Enmienda 2 de ADR-050 para construir
 * {@link CreateAccountService}, {@link ChangeCredentialsService} y {@link DeactivateAccountService}: el servicio y el
 * adaptador de los tokens de verificación y los dos puertos de correo, que aquí solo capturan lo que se entrega.
 */
@TestConfiguration
@Import({EmailVerificationService.class, MongoEmailVerificationTokenRepositoryAdapter.class})
public class EmailVerificationTestBeans {

    /** Correos de verificación entregados para enviar. */
    public static final List<EmailVerificationMailPort.EmailVerificationMail> VERIFICATION_MAILS = new CopyOnWriteArrayList<>();
    /** Avisos de seguridad entregados para enviar. */
    public static final List<SecurityNoticeMailPort.SecurityNotice> NOTICES = new CopyOnWriteArrayList<>();

    public static void clear() {
        VERIFICATION_MAILS.clear();
        NOTICES.clear();
    }

    @Bean
    EmailVerificationMailPort emailVerificationMail() {
        return VERIFICATION_MAILS::add;
    }

    @Bean
    SecurityNoticeMailPort securityNoticeMail() {
        return NOTICES::add;
    }
}

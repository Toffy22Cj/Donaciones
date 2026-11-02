package identity.application.archfixture;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * Fixture de ArchUnit (plan de preverificación, test f): un programador en la capa de aplicación de {@code identity}.
 * Solo existe para comprobar que la regla {@code no_scheduled_methods_in_identity_application} muerde.
 */
public class ArchfixtureScheduledCheck {

    @Scheduled(fixedDelay = 60_000)
    public void run() {
    }
}

package identity.application.service;

import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.GoogleIdTokenVerifierPort;
import identity.application.port.out.GoogleIdTokenVerifierPort.VerifiedGoogleIdentity;
import identity.application.port.out.PasswordHasherPort;
import identity.application.port.out.SecurityNoticeMailPort;
import identity.domain.model.LegalVersions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [Plan] ADR-050 D9, test G-9 del plan de Google: el login con Google busca la cuenta por el {@code sub} y
 * <b>nunca</b> por el correo, aunque el correo del {@code idToken} sea el de una cuenta existente.
 */
class GoogleSignInServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void g9_withoutALinkedAccount_isTheUsualFailure_andTheEmailIsNeverLookedUp() {
        AccountRepositoryPort accounts = mock(AccountRepositoryPort.class);
        GoogleIdTokenVerifierPort verifier = mock(GoogleIdTokenVerifierPort.class);
        when(verifier.verify("id-token")).thenReturn(new VerifiedGoogleIdentity("sub-1", "ana@example.org"));
        when(accounts.findByGoogleSubject("sub-1")).thenReturn(Optional.empty());
        ObjectProvider<LegalVersions> versions = mock(ObjectProvider.class);
        ObjectProvider<Clock> clock = mock(ObjectProvider.class);
        when(clock.getIfAvailable(any())).thenReturn(Clock.systemUTC());
        GoogleSignInService service = new GoogleSignInService(accounts, verifier, mock(PasswordHasherPort.class),
                mock(AuditLogPort.class), mock(MongoTransactionRetryHelper.class), mock(AuthorizationAuditActorMapper.class),
                mock(SecurityNoticeMailPort.class), versions, clock);

        assertThatThrownBy(() -> service.authenticate("id-token", AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(accounts, never()).findByEmail(any());
    }
}

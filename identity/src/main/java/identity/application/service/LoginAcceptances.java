package identity.application.service;

import com.traceability.contracts.authentication.AcceptanceRequiredException;
import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import identity.application.port.out.AccountRepositoryPort;
import identity.domain.model.Account;
import identity.domain.model.LegalAcceptance;
import identity.domain.model.LegalVersions;

import java.time.Clock;
import java.time.Instant;

/**
 * [Plan] ADR-050 D3 (Enmienda 1, parte 3, E3-2 y E3-3), común a los dos inicios de sesión: con contraseña
 * ({@link AuthenticateAccountPortImpl}) y con Google ({@link GoogleSignInService}, P-G-2). Si la cuenta tiene alguna
 * versión vigente pendiente, el login solo sigue si {@code accepted} trae exactamente esas, y entonces se guardan con
 * una actualización parcial condicionada al hash leído y a {@code ACTIVE}. Si no las trae,
 * {@link AcceptanceRequiredException} con solo las pendientes; si la escritura no encuentra el mismo hash (un cambio de
 * contraseña o una desactivación concurrentes), {@link AuthenticationFailedException}. Una cuenta al día no escribe.
 */
final class LoginAcceptances {

    private LoginAcceptances() {
    }

    static void require(AccountRepositoryPort accounts, LegalVersions current, Clock clock, Account account,
                        AcceptedLegalVersions accepted) {
        AcceptedLegalVersions offered = accepted == null ? AcceptedLegalVersions.NONE : accepted;
        String pendingTerms = isCurrent(account.getTermsAcceptance(), current.terms()) ? null : current.terms();
        String pendingDataPolicy = isCurrent(account.getDataPolicyAcceptance(), current.dataPolicy())
                ? null : current.dataPolicy();
        if (pendingTerms == null && pendingDataPolicy == null) {
            return;
        }
        boolean acceptsThem = (pendingTerms == null || pendingTerms.equals(offered.termsVersion()))
                && (pendingDataPolicy == null || pendingDataPolicy.equals(offered.dataPolicyVersion()));
        if (!acceptsThem) {
            throw new AcceptanceRequiredException(pendingTerms, pendingDataPolicy);
        }
        Instant now = clock.instant();
        boolean written = accounts.recordLegalAcceptancesIfUnchanged(account.getAccountId(), account.getPasswordHash(),
                pendingTerms == null ? null : new LegalAcceptance(pendingTerms, now),
                pendingDataPolicy == null ? null : new LegalAcceptance(pendingDataPolicy, now));
        if (!written) {
            throw new AuthenticationFailedException();
        }
    }

    private static boolean isCurrent(LegalAcceptance acceptance, String currentVersion) {
        return acceptance != null && currentVersion.equals(acceptance.version());
    }
}

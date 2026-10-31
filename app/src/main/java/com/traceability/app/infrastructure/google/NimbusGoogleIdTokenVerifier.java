package com.traceability.app.infrastructure.google;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import identity.application.port.out.GoogleIdTokenVerifierPort;
import identity.domain.exception.GoogleIdTokenInvalidException;
import identity.domain.exception.GoogleSignInUnavailableException;
import identity.domain.exception.InvalidEmailFormatException;
import identity.domain.model.Email;

import java.text.ParseException;
import java.util.List;
import java.util.Set;

/**
 * [Plan] Verificación del {@code idToken} de Google con Nimbus (ADR-050 D9; plan de Google §2, P-G-9 y P-G-10):
 * <ul>
 *   <li>firma {@code RS256} con las llaves del JWKS, cacheadas por {@code JWKSourceBuilder};</li>
 *   <li>{@code iss} igual a {@code accounts.google.com} o {@code https://accounts.google.com};</li>
 *   <li>{@code aud}: al menos uno de los ID de cliente configurados ({@code GOOGLE_OAUTH_CLIENT_IDS}, G-1 y G-2);</li>
 *   <li>{@code exp} vigente, con la tolerancia de 60 s de Nimbus;</li>
 *   <li>{@code email_verified} booleano y {@code true}.</li>
 * </ul>
 * Sin ID de cliente configurados, todo {@code idToken} es inválido (P-G-8). Si las llaves no se pueden obtener,
 * {@link GoogleSignInUnavailableException} (503). Nunca registra el {@code idToken}, el {@code sub} ni el correo.
 */
public final class NimbusGoogleIdTokenVerifier implements GoogleIdTokenVerifierPort {

    static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final DefaultJWTProcessor<SecurityContext> processor;
    private final Set<String> clientIds;

    public NimbusGoogleIdTokenVerifier(JWKSource<SecurityContext> keys, List<String> clientIds) {
        this.clientIds = Set.copyOf(clientIds);
        this.processor = new DefaultJWTProcessor<>();
        this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        this.processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(null, Set.of("sub", "iss", "aud", "exp")));
    }

    @Override
    public VerifiedGoogleIdentity verify(String idToken) {
        if (idToken == null || idToken.isBlank() || clientIds.isEmpty()) {
            throw invalid();
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(idToken, null);
        } catch (KeySourceException unreachable) {
            throw new GoogleSignInUnavailableException("The Google signing keys could not be retrieved");
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw invalid();
        }
        if (!ISSUERS.contains(claims.getIssuer()) || claims.getAudience() == null
                || claims.getAudience().stream().noneMatch(clientIds::contains)
                || !Boolean.TRUE.equals(claims.getClaim("email_verified"))) {
            throw invalid();
        }
        return new VerifiedGoogleIdentity(claims.getSubject(), email(claims.getClaim("email")));
    }

    /** El correo de Google, normalizado como el de las cuentas; uno mal formado cuenta como ausente. */
    private static String email(Object claim) {
        if (!(claim instanceof String value)) {
            return null;
        }
        try {
            return new Email(value).value();
        } catch (InvalidEmailFormatException e) {
            return null;
        }
    }

    private static GoogleIdTokenInvalidException invalid() {
        return new GoogleIdTokenInvalidException("The Google idToken is not valid");
    }
}

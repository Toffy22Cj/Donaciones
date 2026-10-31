package com.traceability.app.infrastructure.google;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.traceability.app.support.GoogleTestTokens;
import identity.application.port.out.GoogleIdTokenVerifierPort.VerifiedGoogleIdentity;
import identity.domain.exception.GoogleIdTokenInvalidException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Plan] ADR-050 D9, test 27 a nivel de unidad: la verificación del {@code idToken} con un JWKS en memoria. Nunca llama a
 * Google; las llaves y los ID de cliente son del test (G-1).
 */
class NimbusGoogleIdTokenVerifierTest {

    private final GoogleTestTokens google = new GoogleTestTokens();
    private final NimbusGoogleIdTokenVerifier verifier = new NimbusGoogleIdTokenVerifier(
            new ImmutableJWKSet<SecurityContext>(google.publicJwks()),
            List.of(GoogleTestTokens.WEB_CLIENT, GoogleTestTokens.ANDROID_CLIENT));

    private void invalid(String idToken, String why) {
        assertThatThrownBy(() -> verifier.verify(idToken)).as(why).isInstanceOf(GoogleIdTokenInvalidException.class);
    }

    @Test
    void aValidIdToken_returnsTheSubjectAndTheNormalizedEmail() {
        VerifiedGoogleIdentity id = verifier.verify(google.idToken("sub-1", "Ana@Example.ORG"));

        assertThat(id.subject()).isEqualTo("sub-1");
        assertThat(id.email()).isEqualTo("ana@example.org");
        assertThat(id.toString()).doesNotContain("sub-1").doesNotContain("ana@");
    }

    @Test
    void anyAudienceOfTheList_isAccepted_andTheIssuerWithoutScheme_too() {
        assertThat(verifier.verify(google.sign(google.claims("s", "a@example.org")
                .audience(List.of(GoogleTestTokens.ANDROID_CLIENT)).build())).subject()).isEqualTo("s");
        assertThat(verifier.verify(google.sign(google.claims("s", "a@example.org")
                .issuer("accounts.google.com").build())).subject()).isEqualTo("s");
        assertThat(verifier.verify(google.sign(google.claims("s", "a@example.org")
                .audience(List.of("otro", GoogleTestTokens.WEB_CLIENT)).build())).subject()).isEqualTo("s");
    }

    @Test
    void test27_invalidSignatureIssuerAudienceExpiryOrEmailVerified_areInvalid() throws Exception {
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("otra").generate();
        invalid(GoogleTestTokens.sign(google.claims("s", "a@example.org").build(), otherKey), "firma de otra llave");
        invalid(google.sign(google.claims("s", "a@example.org").issuer("https://evil.example.org").build()), "iss ajeno");
        invalid(google.sign(google.claims("s", "a@example.org").audience(List.of("otro-cliente")).build()), "aud ajeno");
        Instant past = Instant.now().minusSeconds(3600);
        invalid(google.sign(google.claims("s", "a@example.org").issueTime(Date.from(past.minusSeconds(60)))
                .expirationTime(Date.from(past)).build()), "caducado");
        invalid(google.sign(google.claims("s", "a@example.org").claim("email_verified", false).build()), "email_verified false");
        invalid(google.sign(google.claims("s", "a@example.org").claim("email_verified", null).build()), "sin email_verified");
        invalid(google.sign(google.claims("s", "a@example.org").claim("email_verified", "true").build()), "email_verified como texto");
        invalid(new PlainJWT(google.claims("s", "a@example.org").build()).serialize(), "alg none");
        SignedJWT hs = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), google.claims("s", "a@example.org").build());
        hs.sign(new MACSigner(new byte[32]));
        invalid(hs.serialize(), "HS256");
        invalid("no-es-un-jwt", "basura");
        invalid("", "vacío");
        invalid(null, "nulo");
    }

    @Test
    void withoutClientIds_everyIdTokenIsInvalid() {
        NimbusGoogleIdTokenVerifier disabled = new NimbusGoogleIdTokenVerifier(
                new ImmutableJWKSet<SecurityContext>(google.publicJwks()), List.of());

        assertThatThrownBy(() -> disabled.verify(google.idToken("s", "a@example.org")))
                .isInstanceOf(GoogleIdTokenInvalidException.class);
    }

    @Test
    void theSubjectClaimIsRequired() {
        invalid(google.sign(new JWTClaimsSet.Builder(google.claims("s", "a@example.org").build()).subject(null).build()), "sin sub");
    }
}

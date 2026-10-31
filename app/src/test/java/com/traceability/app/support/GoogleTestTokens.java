package com.traceability.app.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [Plan] Google sin Google para los tests (ADR-050 D9; plan de Google §1): llaves RSA generadas en el test,
 * {@code idToken} firmados con Nimbus y un servidor JWKS local de la JDK que cuenta las peticiones y se puede dejar sin
 * responder. Los ID de cliente son ficticios ({@code client-web-test}, {@code client-android-test}; G-1).
 */
public final class GoogleTestTokens {

    public static final String WEB_CLIENT = "client-web-test";
    public static final String ANDROID_CLIENT = "client-android-test";
    public static final String ISSUER = "https://accounts.google.com";

    private final RSAKey key;

    public GoogleTestTokens() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-" + System.nanoTime()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public JWKSet publicJwks() {
        return new JWKSet(key.toPublicJWK());
    }

    /** Un {@code idToken} válido para el {@code aud} web, del {@code sub} y el correo dados, que caduca en una hora. */
    public String idToken(String subject, String email) {
        return sign(claims(subject, email).build());
    }

    public JWTClaimsSet.Builder claims(String subject, String email) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(List.of(WEB_CLIENT))
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("email", email).claim("email_verified", true);
    }

    public String sign(JWTClaimsSet claims) {
        return sign(claims, key);
    }

    public static String sign(JWTClaimsSet claims, RSAKey with) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(with.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(with));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Servidor JWKS local en un puerto libre. */
    public static final class JwksServer implements AutoCloseable {
        private final HttpServer server;
        public final AtomicInteger requests = new AtomicInteger();
        /** Si no es nulo, el servidor no responde hasta que se abra (Google caído). */
        public volatile CountDownLatch hang;

        public JwksServer(JWKSet jwks) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            byte[] body = jwks.toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                requests.incrementAndGet();
                CountDownLatch h = hang;
                if (h != null) {
                    try {
                        h.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
        }

        public String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
        }

        @Override
        public void close() {
            CountDownLatch h = hang;
            if (h != null) h.countDown();
            server.stop(0);
        }
    }
}

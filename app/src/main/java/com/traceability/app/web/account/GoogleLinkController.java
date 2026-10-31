package com.traceability.app.web.account;

import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.GoogleSignInService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * [Plan] Vincular y desvincular Google (ADR-050 D9 y sus ampliaciones, decisión de Manuel en #164), con JWT y sin
 * {@code Command-Id} (DH-34). Las dos exigen {@code currentPassword}: incorrecta o ausente → 400
 * {@code CurrentPasswordIncorrect}, como la desactivación.
 * <ul>
 *   <li>{@code POST /api/v1/account/google/link {idToken, currentPassword}} → {@code 204}; {@code idToken} vacío o
 *       inválido → 400 {@code GoogleIdTokenInvalid}; 409 {@code GoogleAccountAlreadyLinked} o {@code AccountAlreadyLinkedToGoogle}; 503 si Google
 *       no responde.</li>
 *   <li>{@code DELETE /api/v1/account/google/link {currentPassword}} → {@code 204}, también sin vínculo.</li>
 * </ul>
 */
@RestController
public class GoogleLinkController {

    public record LinkRequest(String idToken, String currentPassword) {}

    public record UnlinkRequest(String currentPassword) {}

    private final GoogleSignInService google;

    public GoogleLinkController(GoogleSignInService google) {
        this.google = google;
    }

    @PostMapping("/api/v1/account/google/link")
    public ResponseEntity<Void> link(@CurrentActor AuthorizationPrincipal principal,
                                     @RequestBody(required = false) LinkRequest request) {
        LinkRequest body = request == null ? new LinkRequest(null, null) : request;
        // la contraseña va primero (en el servicio); un idToken vacío después es 400 GoogleIdTokenInvalid
        google.link(principal, body.currentPassword(), body.idToken());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/account/google/link")
    public ResponseEntity<Void> unlink(@CurrentActor AuthorizationPrincipal principal,
                                       @RequestBody(required = false) UnlinkRequest request) {
        google.unlink(principal, request == null ? null : request.currentPassword());
        return ResponseEntity.noContent().build();
    }
}

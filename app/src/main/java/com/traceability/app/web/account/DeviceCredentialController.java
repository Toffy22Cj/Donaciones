package com.traceability.app.web.account;

import com.traceability.api.auth.LoginIssuedAt;
import com.traceability.api.auth.LoginProblems;
import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authentication.AcceptanceRequiredException;
import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.AuthenticatedAccount;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import com.traceability.contracts.authentication.EmailNotVerifiedException;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.DeviceCredentialService;
import identity.application.service.DeviceCredentialService.Enrollment;
import identity.domain.model.AccountId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * Entrar con biometría ({@link DeviceCredentialService}). La huella o el rostro solo se comprueban en el teléfono.
 * <ul>
 *   <li>{@code POST /api/v1/auth/device-credentials} (JWT), {@code {deviceName?}} → {@code 201 {credentialId,
 *       deviceToken}}, {@code no-store}. El token en claro solo sale aquí; 409 con el máximo de dispositivos.</li>
 *   <li>{@code POST /api/v1/auth/device-login} (pública), {@code {deviceToken, acceptedTermsVersion?,
 *       acceptedDataPolicyVersion?}} → {@code 200 {token}}, igual que el login con contraseña y con Google: el
 *       {@code iat} de E1-2 ({@link LoginIssuedAt}); token vacío → 400; inexistente, revocado o de una cuenta
 *       inactiva → el mismo 401; 403 {@code EmailNotVerified} (E2-3); 403 {@code AcceptanceRequired} (D3).</li>
 *   <li>{@code POST /api/v1/auth/device-credentials/{credentialId}/revoke} (JWT) → 204; de otra cuenta, inexistente
 *       o ya revocada → el mismo 404.</li>
 * </ul>
 * Sin {@code Command-Id}, como el resto de Identity (DH-34). Nunca registra el token del dispositivo ni el JWT.
 */
@RestController
public class DeviceCredentialController {

    private static final Logger log = LoggerFactory.getLogger(DeviceCredentialController.class);

    public record EnrollRequest(String deviceName) {}

    public record EnrollResponse(String credentialId, String deviceToken) {}

    public record DeviceLoginRequest(String deviceToken, String acceptedTermsVersion, String acceptedDataPolicyVersion) {}

    public record DeviceLoginResponse(String token) {}

    private final DeviceCredentialService devices;
    private final TokenIssuerPort tokens;
    private final Clock clock;

    public DeviceCredentialController(DeviceCredentialService devices, TokenIssuerPort tokens, ObjectProvider<Clock> clock) {
        this.devices = devices;
        this.tokens = tokens;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @PostMapping("/api/v1/auth/device-credentials")
    public ResponseEntity<EnrollResponse> enroll(@CurrentActor AuthorizationPrincipal principal,
                                                 @RequestBody(required = false) EnrollRequest request) {
        Enrollment e = devices.enroll(new AccountId(principal.accountId()), request == null ? null : request.deviceName());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new EnrollResponse(e.credentialId(), e.token().value()));
    }

    @PostMapping("/api/v1/auth/device-credentials/{credentialId}/revoke")
    public ResponseEntity<Void> revoke(@CurrentActor AuthorizationPrincipal principal,
                                       @PathVariable("credentialId") String credentialId) {
        devices.revoke(new AccountId(principal.accountId()), credentialId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/auth/device-login")
    public ResponseEntity<?> login(@RequestBody(required = false) DeviceLoginRequest request) {
        if (request == null || !StringUtils.hasText(request.deviceToken())) {
            return LoginProblems.problem(HttpStatus.BAD_REQUEST, "deviceToken is required");
        }
        AuthenticatedAccount account;
        try {
            account = devices.authenticate(request.deviceToken(),
                    new AcceptedLegalVersions(request.acceptedTermsVersion(), request.acceptedDataPolicyVersion()));
        } catch (AuthenticationFailedException e) {
            log.debug("Device login rejected");
            return LoginProblems.unauthorized();
        } catch (EmailNotVerifiedException e) {
            return LoginProblems.emailNotVerified();
        } catch (AcceptanceRequiredException e) {
            return LoginProblems.acceptanceRequired(e);
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new DeviceLoginResponse(tokens.issue(account.accountId(), LoginIssuedAt.of(clock, account))));
        } catch (RuntimeException e) {
            log.error("Token issuance failed for a device login: {}", e.getClass().getSimpleName());
            return LoginProblems.problem(HttpStatus.INTERNAL_SERVER_ERROR, "Token could not be issued");
        }
    }
}

package com.traceability.api.auth;

import com.traceability.contracts.authentication.AuthenticateAccountPort;
import com.traceability.contracts.authentication.TokenIssuerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Skeleton B3. */
@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {

    public record LoginRequest(String email, String password) {}

    public record LoginResponse(String token) {}

    public LoginController(AuthenticateAccountPort authenticateAccountPort, TokenIssuerPort tokenIssuerPort) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        throw new UnsupportedOperationException("B3: pendiente");
    }
}

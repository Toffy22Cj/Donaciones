package com.traceability.app.web.account;

import com.traceability.api.web.InvalidRequestFieldException;
import identity.application.service.CreateAccountService;
import identity.domain.model.Account;
import identity.domain.model.Email;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/auth/register} (matriz §3: dominio cerrado, {@code CreateAccountService}; P2.2). Pública.
 * <ul>
 *   <li>{@code 201 {accountId, status}};</li>
 *   <li>email o contraseña vacíos → 400 sin llamar al dominio; email mal formado → 400;</li>
 *   <li>email ya registrado → 409 ({@code DuplicateEmail}).</li>
 * </ul>
 * Sin {@code Command-Id}: Identity no lo usa (DH-34); un reintento recibe 409. Sin política de contraseña: el dominio
 * no la tiene y no se inventa aquí (hallazgo H-P2-1). Nunca registra la contraseña ni el email.
 */
@RestController
public class AccountRegistrationController {

    public record RegisterRequest(String email, String password) {}

    public record RegisterResponse(String accountId, String status) {}

    private final CreateAccountService accounts;

    public AccountRegistrationController(CreateAccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/api/v1/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@RequestBody(required = false) RegisterRequest request) {
        if (request == null || !StringUtils.hasText(request.email())) {
            throw new InvalidRequestFieldException("email");
        }
        if (!StringUtils.hasText(request.password())) {
            throw new InvalidRequestFieldException("password");
        }
        Account account = accounts.createAccount(new Email(request.email()), request.password());
        return new RegisterResponse(account.getAccountId().value(), account.getStatus().name());
    }
}

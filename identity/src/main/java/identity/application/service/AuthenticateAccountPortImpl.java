package identity.application.service;

import com.traceability.contracts.authentication.AuthenticateAccountPort;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.PasswordHasherPort;
import org.springframework.stereotype.Service;

@Service
public class AuthenticateAccountPortImpl implements AuthenticateAccountPort {

    public AuthenticateAccountPortImpl(AccountRepositoryPort accountRepositoryPort, PasswordHasherPort passwordHasherPort) {
    }

    @Override
    public String authenticate(String email, String password) {
        throw new UnsupportedOperationException("B3: pendiente");
    }
}

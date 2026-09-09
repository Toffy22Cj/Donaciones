package identity.infrastructure.security;

import identity.application.port.out.PasswordHasherPort;
import identity.domain.model.PasswordHash;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class BCryptPasswordHasherAdapter implements PasswordHasherPort {

    private final BCryptPasswordEncoder passwordEncoder;

    public BCryptPasswordHasherAdapter() {
        this.passwordEncoder = new BCryptPasswordEncoder(12);
    }

    @Override
    public PasswordHash hash(String plainPassword) {
        if (plainPassword == null || plainPassword.trim().isEmpty()) {
            throw new IllegalArgumentException("Plain password cannot be null or empty");
        }
        return new PasswordHash(passwordEncoder.encode(plainPassword));
    }

    @Override
    public boolean matches(String plainPassword, PasswordHash hash) {
        if (plainPassword == null || hash == null) {
            return false;
        }
        return passwordEncoder.matches(plainPassword, hash.value());
    }
}

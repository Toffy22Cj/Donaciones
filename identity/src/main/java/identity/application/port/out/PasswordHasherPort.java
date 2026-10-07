package identity.application.port.out;

import identity.domain.model.PasswordHash;

public interface PasswordHasherPort {
    /**
     * Hashes a plain text password.
     * @param plainPassword the plain text password to hash
     * @return the resulting password hash object
     */
    PasswordHash hash(String plainPassword);

    /**
     * Verifies if a plain text password matches a given hash.
     * @param plainPassword the plain text password to verify
     * @param hash the existing password hash object to compare against
     * @return true if they match, false otherwise
     */
    boolean matches(String plainPassword, PasswordHash hash);
}

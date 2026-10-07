package identity.infrastructure.security;

import identity.domain.model.PasswordHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BCryptPasswordHasherAdapterTest {

    private BCryptPasswordHasherAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BCryptPasswordHasherAdapter();
    }

    @Test
    void hash_createsDifferentHashesForSamePassword() {
        String plainPassword = "mySecretPassword123";

        PasswordHash hash1 = adapter.hash(plainPassword);
        PasswordHash hash2 = adapter.hash(plainPassword);

        assertNotNull(hash1);
        assertNotNull(hash2);
        // BCrypt includes a random salt, so two hashes of the same password must be different
        assertNotEquals(hash1.value(), hash2.value());
    }

    @Test
    void matches_returnsTrueForCorrectPassword() {
        String plainPassword = "mySecretPassword123";
        PasswordHash hash = adapter.hash(plainPassword);

        assertTrue(adapter.matches(plainPassword, hash));
    }

    @Test
    void matches_returnsFalseForIncorrectPassword() {
        String plainPassword = "mySecretPassword123";
        PasswordHash hash = adapter.hash(plainPassword);

        assertFalse(adapter.matches("wrongPassword", hash));
    }
    
    @Test
    void matches_returnsFalseForNulls() {
        PasswordHash hash = adapter.hash("test");
        assertFalse(adapter.matches(null, hash));
        assertFalse(adapter.matches("test", null));
    }
}

package identity.domain.model;

import identity.domain.exception.InvalidEmailFormatException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValueObjectsTest {

    @Test
    void testAccountId() {
        AccountId id = AccountId.generate();
        assertNotNull(id.value());
        assertFalse(id.value().isBlank());

        assertThrows(IllegalArgumentException.class, () -> new AccountId(null));
        assertThrows(IllegalArgumentException.class, () -> new AccountId(""));
        assertThrows(IllegalArgumentException.class, () -> new AccountId("   "));
        
        AccountId explicit = new AccountId("01F8MECHZX3TBDSZ7XRADM79XE");
        assertEquals("01F8MECHZX3TBDSZ7XRADM79XE", explicit.value());
    }

    @Test
    void testOrganizationId() {
        OrganizationId id = OrganizationId.generate();
        assertNotNull(id.value());
        assertFalse(id.value().isBlank());

        assertThrows(IllegalArgumentException.class, () -> new OrganizationId(null));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationId(""));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationId("   "));
        
        OrganizationId explicit = new OrganizationId("01F8MECHZX3TBDSZ7XRADM79XE");
        assertEquals("01F8MECHZX3TBDSZ7XRADM79XE", explicit.value());
    }

    @Test
    void testEmail() {
        Email valid = new Email("test@example.com");
        assertEquals("test@example.com", valid.value());
        
        assertDoesNotThrow(() -> new Email("user.name+tag@domain.co.uk"));

        // encargo 5 (4): un email es el mismo sin distinguir mayúsculas; se guarda y se compara en minúsculas
        assertEquals("ana.perez@example.org", new Email("Ana.Perez@Example.ORG").value());
        assertEquals(new Email("ana.perez@example.org"), new Email("ANA.PEREZ@EXAMPLE.ORG"));
        // DD-74 ratificada con añadido (Carlos, 2026-10-08): también quita los espacios de los extremos
        assertEquals("ana.perez@example.org", new Email("  Ana.Perez@Example.ORG\t ").value());
        assertThrows(InvalidEmailFormatException.class, () -> new Email("   "));
        assertThrows(InvalidEmailFormatException.class, () -> new Email("ana perez@example.org"));
        assertThrows(InvalidEmailFormatException.class, () -> new Email(null));
        assertThrows(InvalidEmailFormatException.class, () -> new Email(""));
        assertThrows(InvalidEmailFormatException.class, () -> new Email("invalid-email"));
        assertThrows(InvalidEmailFormatException.class, () -> new Email("test@.com"));
        assertThrows(InvalidEmailFormatException.class, () -> new Email("@example.com"));
    }

    @Test
    void testPasswordHash() {
        PasswordHash valid = new PasswordHash("some-opaque-hash-value");
        assertEquals("some-opaque-hash-value", valid.value());

        assertThrows(IllegalArgumentException.class, () -> new PasswordHash(null));
        assertThrows(IllegalArgumentException.class, () -> new PasswordHash(""));
        assertThrows(IllegalArgumentException.class, () -> new PasswordHash("   "));
    }
}

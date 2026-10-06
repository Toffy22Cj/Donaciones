package identity.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogEntryTest {

    @Test
    void rejectsBothNull() {
        assertThrows(IllegalArgumentException.class, () -> new AuditLogEntry(
                "audit-1", Instant.now(), null, null, null, null, AuditAction.ACCOUNT_DEACTIVATED, Map.of()
        ));
    }

    @Test
    void rejectsBothNonNull() {
        assertThrows(IllegalArgumentException.class, () -> new AuditLogEntry(
                "audit-1", Instant.now(), new AuditActor.AccountAuditActor(AccountId.generate()),
                AccountId.generate(), null, null, AuditAction.ACCOUNT_DEACTIVATED, Map.of()
        ));
    }

    @Test
    void regimeReturnsExpectedValues() {
        AuditLogEntry post = AuditLogEntry.record(
                "audit-1", Instant.now(), new AuditActor.SystemAuditActor("proc-1"),
                null, null, AuditAction.ACCOUNT_DEACTIVATED, Map.of()
        );
        assertEquals(AuditRegime.POST_CUTOVER, post.regime());

        AuditLogEntry pre = AuditLogEntry.legacy(
                "audit-2", Instant.now(), AccountId.generate(),
                null, null, AuditAction.ACCOUNT_DEACTIVATED, Map.of()
        );
        assertEquals(AuditRegime.PRE_CUTOVER, pre.regime());
    }

    @Test
    void systemAuditActorRejectsNullOrBlank() {
        assertThrows(IllegalArgumentException.class, () -> new AuditActor.SystemAuditActor(null));
        assertThrows(IllegalArgumentException.class, () -> new AuditActor.SystemAuditActor(""));
        assertThrows(IllegalArgumentException.class, () -> new AuditActor.SystemAuditActor("   "));
    }

    @Test
    void accountAuditActorRejectsNull() {
        assertThrows(NullPointerException.class, () -> new AuditActor.AccountAuditActor(null));
    }
}

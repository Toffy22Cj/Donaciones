package identity.domain.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExceptionsTest {

    @Test
    void testExceptionsAreInstantiableWithMessage() {
        assertExceptionMessage(new InvalidEmailFormatException("invalid email"), "invalid email");
        assertExceptionMessage(new DuplicateEmailException("duplicate email"), "duplicate email");
        assertExceptionMessage(new AccountNotFoundException("account not found"), "account not found");
        assertExceptionMessage(new OrganizationNotFoundException("org not found"), "org not found");
        assertExceptionMessage(new AccountAlreadyBelongsToOrganizationException("already belongs"), "already belongs");
        assertExceptionMessage(new AccountNotMemberOfOrganizationException("not member"), "not member");
        assertExceptionMessage(new CannotRemoveLastRoleException("last role"), "last role");
        assertExceptionMessage(new RepresentativeTransferRequiredException("transfer required"), "transfer required");
        assertExceptionMessage(new TransferTargetNotMemberException("target not member"), "target not member");
        assertExceptionMessage(new SelfTransferNotAllowedException("self transfer"), "self transfer");
    }

    private void assertExceptionMessage(Exception exception, String expectedMessage) {
        assertEquals(expectedMessage, exception.getMessage());
    }
}

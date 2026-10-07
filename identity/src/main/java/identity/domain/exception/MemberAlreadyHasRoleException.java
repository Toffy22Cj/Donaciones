package identity.domain.exception;

/** El miembro ya está en el estado pedido por el cambio de rol (ADR-049 D7, DD-66). */
public class MemberAlreadyHasRoleException extends RuntimeException {
    public MemberAlreadyHasRoleException(String message) {
        super(message);
    }
}

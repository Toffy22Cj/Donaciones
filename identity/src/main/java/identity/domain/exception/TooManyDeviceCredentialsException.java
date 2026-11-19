package identity.domain.exception;

/** Una cuenta ya tiene el máximo de dispositivos con biometría activos; hay que revocar uno antes de añadir otro. */
public class TooManyDeviceCredentialsException extends RuntimeException {
    public TooManyDeviceCredentialsException(String message) {
        super(message);
    }
}

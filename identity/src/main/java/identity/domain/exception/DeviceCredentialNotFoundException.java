package identity.domain.exception;

/** Revocar una credencial de dispositivo que no existe, ya está revocada o es de otra cuenta: el mismo 404. */
public class DeviceCredentialNotFoundException extends RuntimeException {
    public DeviceCredentialNotFoundException(String message) {
        super(message);
    }
}

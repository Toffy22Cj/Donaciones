package identity.application.port.out;

/**
 * [Plan] Verificación del {@code idToken} de Google (ADR-050 D9; plan de Google §2). La implementa {@code app} con Nimbus
 * y el JWKS de Google (P-G-12); {@code identity} no conoce Nimbus.
 */
public interface GoogleIdTokenVerifierPort {

    /**
     * Comprueba la firma con el JWKS, {@code iss}, {@code aud} (uno de los ID de cliente configurados), {@code exp} y
     * {@code email_verified = true}.
     *
     * @throws identity.domain.exception.GoogleIdTokenInvalidException si cualquiera falla
     * @throws identity.domain.exception.GoogleSignInUnavailableException si no se pueden obtener las llaves
     */
    VerifiedGoogleIdentity verify(String idToken);

    /** [Plan] {@code email} ya normalizado como el de las cuentas; {@code toString} no enseña nada. */
    record VerifiedGoogleIdentity(String subject, String email) {
        @Override
        public String toString() {
            return "VerifiedGoogleIdentity[]";
        }
    }
}

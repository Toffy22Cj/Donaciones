package identity.application.port.out;

import java.util.Optional;

/** Tabla cuenta ↔ seudónimo de donante (ADR-048 §7). Solo la usa {@code identity}. */
public interface DonorPseudonymRepositoryPort {

    /** Devuelve el seudónimo guardado o guarda {@code candidate} si no había ninguno; atómico ante llamadas concurrentes. */
    String findOrInsert(String accountId, String candidate);

    Optional<String> find(String accountId);

    /** Supresión (ADR-048 §3): borra el vínculo. Devuelve si existía. */
    boolean delete(String accountId);
}

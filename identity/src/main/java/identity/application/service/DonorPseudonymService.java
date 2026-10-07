package identity.application.service;

import com.traceability.contracts.donor.DonorPseudonymPort;
import identity.application.port.out.DonorPseudonymRepositoryPort;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Seudónimo aleatorio por cuenta (ADR-048 (C)). Nunca registra la cuenta ni el seudónimo en los logs (§7). La
 * supresión borra el vínculo: los {@code donorRef} de los eventos quedan sin enlace con ninguna persona.
 */
@Service
public class DonorPseudonymService implements DonorPseudonymPort {

    private final DonorPseudonymRepositoryPort pseudonyms;
    private final MongoTransactionRetryHelper retryHelper;

    public DonorPseudonymService(DonorPseudonymRepositoryPort pseudonyms, MongoTransactionRetryHelper retryHelper) {
        this.pseudonyms = pseudonyms;
        this.retryHelper = retryHelper;
    }

    @Override
    public String pseudonymFor(String accountId) {
        String account = requireAccount(accountId);
        return retryHelper.executeWithRetry(() -> pseudonyms.findOrInsert(account, UUID.randomUUID().toString()));
    }

    @Override
    public Optional<String> existingPseudonymFor(String accountId) {
        return pseudonyms.find(requireAccount(accountId));
    }

    /** Derecho de supresión (ADR-048 §3): el procedimiento operativo queda fuera de este corte; el diseño lo permite. */
    public boolean forget(String accountId) {
        String account = requireAccount(accountId);
        return retryHelper.executeWithRetry(() -> pseudonyms.delete(account));
    }

    private static String requireAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        return accountId;
    }
}

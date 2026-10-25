package identity.infrastructure.persistence.mongo.documents;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Token de recuperación (ADR-050 D8; plan R-4 §4). Solo el hash del secreto, con índice único; nunca el secreto. El
 * índice único <b>parcial</b> {@value #PENDING_PER_ACCOUNT_INDEX} garantiza una sola {@code PENDING} por cuenta aunque
 * lleguen dos peticiones a la vez. {@code MongoIndexInitializer} crea los dos al arrancar (H-IDX-1).
 */
@Getter
@Setter
@Document(PasswordResetTokenDocument.COLLECTION)
@CompoundIndex(name = PasswordResetTokenDocument.PENDING_PER_ACCOUNT_INDEX, def = "{'accountId': 1}", unique = true,
        partialFilter = "{'status': 'PENDING'}")
public class PasswordResetTokenDocument {

    public static final String COLLECTION = "password_reset_tokens";
    public static final String PENDING_PER_ACCOUNT_INDEX = "uq_pending_per_account";

    @Id
    private String id;
    @Indexed(unique = true)
    private String tokenHash;
    private String accountId;
    private String status;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant usedAt;
}

package identity.infrastructure.persistence.mongo.documents;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * [Plan] Token de verificación del correo (ADR-050 Enmienda 2, E2-2), con el patrón de D8: solo el hash del secreto,
 * con índice único; nunca el secreto. El índice único <b>parcial</b> {@value #PENDING_PER_ACCOUNT_INDEX} garantiza una
 * sola {@code PENDING} por cuenta aunque lleguen dos peticiones a la vez. {@code MongoIndexInitializer} crea los dos al
 * arrancar (H-IDX-1).
 */
@Getter
@Setter
@Document(EmailVerificationTokenDocument.COLLECTION)
@CompoundIndex(name = EmailVerificationTokenDocument.PENDING_PER_ACCOUNT_INDEX, def = "{'accountId': 1}", unique = true,
        partialFilter = "{'status': 'PENDING'}")
public class EmailVerificationTokenDocument {

    public static final String COLLECTION = "email_verification_tokens";
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

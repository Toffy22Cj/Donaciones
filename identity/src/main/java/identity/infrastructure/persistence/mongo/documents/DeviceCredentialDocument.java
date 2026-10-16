package identity.infrastructure.persistence.mongo.documents;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Credencial de dispositivo para entrar con biometría. Solo el hash del token, con índice único; nunca el token. */
@Getter
@Setter
@Document("device_credentials")
public class DeviceCredentialDocument {
    @Id
    private String credentialId;
    @Indexed
    private String accountId;
    @Indexed(unique = true)
    private String tokenHash;
    private String deviceName;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant revokedAt;
}

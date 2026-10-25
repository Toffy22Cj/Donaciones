package identity.infrastructure.persistence.mongo.documents;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Document("accounts")
@Getter
@Setter
public class AccountDocument {
    @Id
    private String accountId;

    @Indexed(unique = true)
    private String email;

    private String passwordHash;
    private String status;
    private String organizationId; // nullable
    private String platformAuthority; // nullable
    private Instant credentialsNotBefore; // nullable (ADR-050 D4)
}

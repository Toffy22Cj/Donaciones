package identity.infrastructure.persistence.mongo.documents;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.Getter;
import lombok.Setter;

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
}

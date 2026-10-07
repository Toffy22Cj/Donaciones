package identity.infrastructure.persistence.mongo.documents;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB document representation for the singleton platform authority state (ADR-038 §2.3).
 */
@Document("platform_authority_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PlatformAuthorityStateDocument {

    @Id
    private String id;

    private long activeAdministratorCount;

    private long version;
}

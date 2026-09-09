package identity.infrastructure.persistence.mongo.documents;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Document("organizations")
@Getter
@Setter
public class OrganizationDocument {
    @Id
    private String organizationId;
    private String type;
    private List<MembershipDocument> members;
}

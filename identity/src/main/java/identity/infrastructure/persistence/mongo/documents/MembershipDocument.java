package identity.infrastructure.persistence.mongo.documents;

import identity.domain.model.Role;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MembershipDocument {
    private String accountId;
    private Set<Role> roles;
}

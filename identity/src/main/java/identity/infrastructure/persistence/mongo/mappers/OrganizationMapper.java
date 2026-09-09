package identity.infrastructure.persistence.mongo.mappers;

import identity.domain.model.AccountId;
import identity.domain.model.Membership;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import identity.infrastructure.persistence.mongo.documents.MembershipDocument;
import identity.infrastructure.persistence.mongo.documents.OrganizationDocument;

import java.util.stream.Collectors;

public class OrganizationMapper {

    public static OrganizationDocument toDocument(Organization org) {
        if (org == null) {
            return null;
        }
        OrganizationDocument doc = new OrganizationDocument();
        doc.setOrganizationId(org.getOrganizationId().value());
        doc.setType(org.getType().name());
        
        doc.setMembers(org.getMembers().stream()
            .map(OrganizationMapper::toMembershipDocument)
            .collect(Collectors.toList()));
            
        return doc;
    }

    public static Organization toDomain(OrganizationDocument doc) {
        if (doc == null) {
            return null;
        }

        return Organization.reconstitute(
            new OrganizationId(doc.getOrganizationId()),
            OrganizationType.valueOf(doc.getType()),
            doc.getMembers().stream()
                .map(OrganizationMapper::toMembershipDomain)
                .collect(Collectors.toList())
        );
    }

    private static MembershipDocument toMembershipDocument(Membership membership) {
        MembershipDocument doc = new MembershipDocument();
        doc.setAccountId(membership.getAccountId().value());
        doc.setRoles(membership.getRoles());
        return doc;
    }

    private static Membership toMembershipDomain(MembershipDocument doc) {
        return new Membership(
            new AccountId(doc.getAccountId()),
            doc.getRoles()
        );
    }
}

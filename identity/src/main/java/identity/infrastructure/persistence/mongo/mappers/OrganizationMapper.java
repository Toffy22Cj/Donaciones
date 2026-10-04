package identity.infrastructure.persistence.mongo.mappers;

import identity.domain.exception.CorruptOrganizationDocumentException;
import identity.domain.exception.InvalidInformationRequestMessageException;
import identity.domain.model.AccountId;
import identity.domain.model.InformationRequestMessage;
import identity.domain.model.Membership;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import identity.domain.model.VerificationStatus;
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

        doc.setVerificationStatus(org.getVerificationStatus().name());
        doc.setVerificationInformationRequest(
            org.getVerificationInformationRequest() != null
                ? org.getVerificationInformationRequest().value()
                : null
        );
            
        return doc;
    }

    public static Organization toDomain(OrganizationDocument doc) {
        if (doc == null) {
            return null;
        }

        String orgId = doc.getOrganizationId();
        String statusStr = doc.getVerificationStatus();
        String messageStr = doc.getVerificationInformationRequest();

        VerificationStatus status;
        InformationRequestMessage infoMessage = null;

        if (statusStr == null) {
            if (messageStr != null) {
                throw new CorruptOrganizationDocumentException(
                        orgId, "Verification status is missing but verification information request message is present");
            }
            // D2: documento sin campo de estado se lee como PENDING_VERIFICATION (solo en lectura)
            status = VerificationStatus.PENDING_VERIFICATION;
        } else {
            try {
                status = VerificationStatus.valueOf(statusStr);
            } catch (IllegalArgumentException e) {
                throw new CorruptOrganizationDocumentException(
                        orgId, "Unknown verification status [" + statusStr + "]");
            }

            if (status == VerificationStatus.NEEDS_MORE_INFORMATION) {
                if (messageStr == null) {
                    throw new CorruptOrganizationDocumentException(
                            orgId, "Verification status is NEEDS_MORE_INFORMATION but verification information request message is missing");
                }
                try {
                    infoMessage = new InformationRequestMessage(messageStr);
                } catch (InvalidInformationRequestMessageException e) {
                    throw new CorruptOrganizationDocumentException(
                            orgId, "Invalid verification information request message: " + e.getMessage(), e);
                }
            } else {
                if (messageStr != null) {
                    throw new CorruptOrganizationDocumentException(
                            orgId, "Verification status is [" + status + "] but verification information request message is present");
                }
            }
        }

        return Organization.reconstitute(
            new OrganizationId(doc.getOrganizationId()),
            OrganizationType.valueOf(doc.getType()),
            doc.getMembers().stream()
                .map(OrganizationMapper::toMembershipDomain)
                .collect(Collectors.toList()),
            status,
            infoMessage
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

package com.traceability.core.application.authorization;

import org.springframework.stereotype.Component;

@Component
public class OrganizationBoundaryPolicy {

    public void assertBelongs(String principalOrganizationId, String resourceOrganizationRef) {
        if (principalOrganizationId == null) {
            throw new CrossOrganizationAccessException(
                    "Principal has no organization assigned. Cannot access resource with organizationRef: "
                            + resourceOrganizationRef
            );
        }

        if (resourceOrganizationRef == null) {
            throw new CrossOrganizationAccessException(
                    "Resource has no organizationRef assigned. Cannot verify boundary for principal organization: "
                            + principalOrganizationId
            );
        }

        if (!principalOrganizationId.equals(resourceOrganizationRef)) {
            throw new CrossOrganizationAccessException(
                    "Principal organization '" + principalOrganizationId
                            + "' does not match resource organization '" + resourceOrganizationRef + "'"
            );
        }
    }
}
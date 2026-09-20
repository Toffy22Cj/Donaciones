package com.traceability.core.application.authorization;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import org.springframework.stereotype.Component;

@Component
public class RoleAuthorizationPolicy {

    public void authorize(AuthorizationPrincipal principal, CommandType commandType) {
        if (principal == null) {
            throw new InsufficientRoleException("Principal is null. Cannot authorize command: " + commandType);
        }

        if (principal.roles() == null || principal.roles().isEmpty()) {
            throw new InsufficientRoleException("Principal has no roles. Cannot authorize command: " + commandType);
        }

        AuthorizationRole requiredRole = switch (commandType) {
            case REGISTER_FUND, CLEAR_FUNDS_AS_GENESIS, CLEAR_FUNDS_FOR_PLEDGE -> AuthorizationRole.ADMINISTRATOR;
            case REGISTER_PHYSICAL_ASSET, REGISTER_PHYSICAL_ASSET_FROM_DONATION, SPLIT_PHYSICAL_ASSET -> AuthorizationRole.EMPLOYEE;
        };

        if (!principal.roles().contains(requiredRole)) {
            throw new InsufficientRoleException(
                    "Principal does not have the required role " + requiredRole + " for command: " + commandType
            );
        }
    }
}

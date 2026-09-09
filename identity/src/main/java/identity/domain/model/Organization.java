package identity.domain.model;

import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.AccountNotMemberOfOrganizationException;
import identity.domain.exception.AccountNotRepresentativeException;
import identity.domain.exception.RepresentativeTransferRequiredException;
import identity.domain.exception.SelfTransferNotAllowedException;
import identity.domain.exception.TransferTargetNotMemberException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class Organization {
    private final OrganizationId organizationId;
    private final OrganizationType type;
    private final List<Membership> members;

    private Organization(OrganizationId organizationId, OrganizationType type, List<Membership> initialMembers) {
        this.organizationId = organizationId;
        this.type = type;
        this.members = new ArrayList<>(initialMembers);
    }

    public static Organization createOrganization(OrganizationType type, AccountId initialRepresentativeAccountId) {
        if (type == null) {
            throw new IllegalArgumentException("OrganizationType cannot be null");
        }
        if (initialRepresentativeAccountId == null) {
            throw new IllegalArgumentException("Initial Representative AccountId cannot be null");
        }
        Membership initialMembership = new Membership(initialRepresentativeAccountId, Set.of(Role.REPRESENTATIVE));
        return new Organization(OrganizationId.generate(), type, List.of(initialMembership));
    }

    public OrganizationId getOrganizationId() {
        return organizationId;
    }

    public OrganizationType getType() {
        return type;
    }

    public List<Membership> getMembers() {
        return Collections.unmodifiableList(members);
    }

    private Optional<Membership> findMembership(AccountId accountId) {
        return members.stream()
            .filter(m -> m.getAccountId().equals(accountId))
            .findFirst();
    }

    public void addEmployee(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        if (findMembership(accountId).isPresent()) {
            throw new AccountAlreadyBelongsToOrganizationException("Account is already a member of this organization");
        }
        members.add(new Membership(accountId, Set.of(Role.EMPLOYEE)));
    }

    /**
     * Assigns the ADMINISTRATOR role to an existing member.
     * @return true if the role was added (mutation occurred), false if the member already had the role (no-op).
     */
    public boolean assignAdministrator(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        Membership membership = findMembership(accountId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Account is not a member of this organization"));

        return membership.addRole(Role.ADMINISTRATOR);
    }

    /**
     * Removes the ADMINISTRATOR role from an existing member.
     * @return true if the role was removed (mutation occurred), false if the member did not have the role (no-op).
     */
    public boolean removeAdministrator(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        Membership membership = findMembership(accountId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Account is not a member of this organization"));

        return membership.removeRole(Role.ADMINISTRATOR);
    }

    /**
     * Removes the EMPLOYEE role from an existing member.
     * @return true if the role was removed (mutation occurred), false if the member did not have the role (no-op).
     */
    public boolean removeEmployee(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        Membership membership = findMembership(accountId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Account is not a member of this organization"));

        return membership.removeRole(Role.EMPLOYEE);
    }

    /**
     * Removes an account from the organization entirely.
     * @throws RepresentativeTransferRequiredException if the member is the REPRESENTATIVE.
     */
    public void removeMemberFromOrganization(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        Membership membership = findMembership(accountId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Account is not a member of this organization"));

        if (membership.hasRole(Role.REPRESENTATIVE)) {
            throw new RepresentativeTransferRequiredException("Cannot remove the representative without transferring the role first");
        }

        // Just remove the membership. Roles are never emptied.
        members.remove(membership);
    }

    /**
     * Atomically transfers the REPRESENTATIVE role to another member, and if the current
     * representative is left without any roles, removes them from the organization entirely.
     */
    public void transferRepresentativeAndRemove(AccountId currentRepId, AccountId newRepId) {
        if (currentRepId == null || newRepId == null) {
            throw new IllegalArgumentException("Account IDs cannot be null");
        }
        if (currentRepId.equals(newRepId)) {
            throw new SelfTransferNotAllowedException("Cannot transfer representative role to the same account");
        }

        Membership currentRepMembership = findMembership(currentRepId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Current representative is not a member of this organization"));
        
        if (!currentRepMembership.hasRole(Role.REPRESENTATIVE)) {
            throw new AccountNotRepresentativeException("Current account does not hold the representative role");
        }

        Membership newRepMembership = findMembership(newRepId)
            .orElseThrow(() -> new TransferTargetNotMemberException("Target account is not a member of this organization"));

        // Assign REPRESENTATIVE to the new representative
        newRepMembership.addRole(Role.REPRESENTATIVE);

        // Handle the current representative
        if (currentRepMembership.getRoles().size() == 1) {
            // It only has REPRESENTATIVE. Removing it would leave roles empty.
            // So we remove the membership entirely without modifying its roles.
            members.remove(currentRepMembership);
        } else {
            // It has other roles, so we can safely remove REPRESENTATIVE
            currentRepMembership.removeRole(Role.REPRESENTATIVE);
        }
    }
}

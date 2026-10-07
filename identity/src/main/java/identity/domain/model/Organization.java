package identity.domain.model;

import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.AccountNotMemberOfOrganizationException;
import identity.domain.exception.AccountNotRepresentativeException;
import identity.domain.exception.InvalidMemberRoleException;
import identity.domain.exception.InvalidVerificationTransitionException;
import identity.domain.exception.MemberAlreadyHasRoleException;
import identity.domain.exception.RepresentativeTransferRequiredException;
import identity.domain.exception.SelfTransferNotAllowedException;
import identity.domain.exception.TransferTargetNotMemberException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class Organization {
    private final OrganizationId organizationId;
    private final OrganizationType type;
    private final List<Membership> members;
    private VerificationStatus verificationStatus;
    private InformationRequestMessage verificationInformationRequest;
    /** Nombre público, opcional (plan B6-a, Q-B6A-1; `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` DD-04). */
    private String name;

    /** Longitud máxima del nombre público (DD-04). */
    public static final int NAME_MAX_LENGTH = 200;

    private Organization(OrganizationId organizationId,
                         OrganizationType type,
                         List<Membership> initialMembers,
                         VerificationStatus verificationStatus,
                         InformationRequestMessage verificationInformationRequest) {
        this.organizationId = organizationId;
        this.type = type;
        this.members = new ArrayList<>(initialMembers);
        this.verificationStatus = verificationStatus;
        this.verificationInformationRequest = verificationInformationRequest;
    }

    /**
     * Uso exclusivo de adaptadores de persistencia — NUNCA invocar desde Application Services ni tests de dominio.
     * No aplica ninguna regla de negocio de creación.
     */
    public static Organization reconstitute(OrganizationId organizationId,
                                            OrganizationType type,
                                            List<Membership> members,
                                            VerificationStatus verificationStatus,
                                            InformationRequestMessage verificationInformationRequest) {
        Objects.requireNonNull(verificationStatus, "verificationStatus must not be null");
        if (verificationStatus == VerificationStatus.NEEDS_MORE_INFORMATION && verificationInformationRequest == null) {
            throw new IllegalArgumentException("verificationInformationRequest must be present when verificationStatus is NEEDS_MORE_INFORMATION");
        }
        if (verificationStatus != VerificationStatus.NEEDS_MORE_INFORMATION && verificationInformationRequest != null) {
            throw new IllegalArgumentException("verificationInformationRequest must be null when verificationStatus is not NEEDS_MORE_INFORMATION");
        }
        return new Organization(organizationId, type, members, verificationStatus, verificationInformationRequest);
    }

    /** Igual que {@link #reconstitute(OrganizationId, OrganizationType, List, VerificationStatus, InformationRequestMessage)}, con el nombre público. */
    public static Organization reconstitute(OrganizationId organizationId,
                                            OrganizationType type,
                                            List<Membership> members,
                                            VerificationStatus verificationStatus,
                                            InformationRequestMessage verificationInformationRequest,
                                            String name) {
        Organization organization = reconstitute(organizationId, type, members, verificationStatus,
                verificationInformationRequest);
        organization.name = name;
        return organization;
    }

    public static Organization createOrganization(OrganizationType type, AccountId initialRepresentativeAccountId) {
        if (type == null) {
            throw new IllegalArgumentException("OrganizationType cannot be null");
        }
        if (initialRepresentativeAccountId == null) {
            throw new IllegalArgumentException("Initial Representative AccountId cannot be null");
        }
        Membership initialMembership = new Membership(initialRepresentativeAccountId, Set.of(Role.REPRESENTATIVE));
        return new Organization(OrganizationId.generate(), type, List.of(initialMembership), VerificationStatus.PENDING_VERIFICATION, null);
    }

    /**
     * Igual que {@link #createOrganization(OrganizationType, AccountId)}, con un nombre público opcional (plan B6-a,
     * Q-B6A-1). Un nombre presente no puede estar en blanco ni superar {@link #NAME_MAX_LENGTH}.
     */
    public static Organization createOrganization(OrganizationType type, AccountId initialRepresentativeAccountId,
                                                  String name) {
        if (name != null && (name.isBlank() || name.length() > NAME_MAX_LENGTH)) {
            throw new IllegalArgumentException("Organization name must not be blank nor exceed " + NAME_MAX_LENGTH);
        }
        Organization organization = createOrganization(type, initialRepresentativeAccountId);
        organization.name = name == null ? null : name.strip();
        return organization;
    }

    public String getName() {
        return name;
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

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public InformationRequestMessage getVerificationInformationRequest() {
        return verificationInformationRequest;
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
     * Cambia el rol de un miembro (ADR-049 D7; operación nueva, no contradice ADR-026). {@code REPRESENTATIVE} nunca se
     * toca aquí: se cambia solo con la transferencia.
     * <ul>
     *   <li>{@code ADMINISTRATOR}: añade {@code ADMINISTRATOR} y conserva el resto;</li>
     *   <li>{@code EMPLOYEE}: añade {@code EMPLOYEE} si falta y quita {@code ADMINISTRATOR}.</li>
     * </ul>
     * Si ya está en el estado pedido, {@link MemberAlreadyHasRoleException} (DD-66: la opción restrictiva).
     */
    public void changeMemberRole(AccountId accountId, Role target) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        if (target != Role.ADMINISTRATOR && target != Role.EMPLOYEE) {
            throw new InvalidMemberRoleException("Role must be ADMINISTRATOR or EMPLOYEE");
        }
        Membership membership = findMembership(accountId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Account is not a member of this organization"));
        if (target == Role.ADMINISTRATOR) {
            if (!membership.addRole(Role.ADMINISTRATOR)) {
                throw new MemberAlreadyHasRoleException("Member already has the ADMINISTRATOR role");
            }
            return;
        }
        if (membership.hasRole(Role.EMPLOYEE) && !membership.hasRole(Role.ADMINISTRATOR)) {
            throw new MemberAlreadyHasRoleException("Member already has the EMPLOYEE role only");
        }
        // primero EMPLOYEE, para que quitar ADMINISTRATOR nunca deje la membresía sin roles (invariante 4)
        membership.addRole(Role.EMPLOYEE);
        membership.removeRole(Role.ADMINISTRATOR);
    }

    /** La membresía de una cuenta, si es miembro. */
    public Optional<Membership> membershipOf(AccountId accountId) {
        return findMembership(accountId);
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

    public void verify() {
        if (this.verificationStatus == VerificationStatus.VERIFIED || this.verificationStatus == VerificationStatus.REJECTED) {
            throw new InvalidVerificationTransitionException(this.verificationStatus, VerificationCommand.VERIFY);
        }
        this.verificationStatus = VerificationStatus.VERIFIED;
        this.verificationInformationRequest = null;
    }

    public void reject() {
        if (this.verificationStatus == VerificationStatus.VERIFIED || this.verificationStatus == VerificationStatus.REJECTED) {
            throw new InvalidVerificationTransitionException(this.verificationStatus, VerificationCommand.REJECT);
        }
        this.verificationStatus = VerificationStatus.REJECTED;
        this.verificationInformationRequest = null;
    }

    public void requestInformation(InformationRequestMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        if (this.verificationStatus == VerificationStatus.VERIFIED || this.verificationStatus == VerificationStatus.REJECTED) {
            throw new InvalidVerificationTransitionException(this.verificationStatus, VerificationCommand.REQUEST_INFORMATION);
        }
        this.verificationStatus = VerificationStatus.NEEDS_MORE_INFORMATION;
        this.verificationInformationRequest = message;
    }
}

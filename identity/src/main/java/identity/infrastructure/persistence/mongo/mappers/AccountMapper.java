package identity.infrastructure.persistence.mongo.mappers;

import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.Email;
import identity.domain.model.OrganizationId;
import identity.domain.model.PasswordHash;
import identity.domain.model.PlatformAuthority;
import identity.infrastructure.persistence.mongo.documents.AccountDocument;

public class AccountMapper {

    public static AccountDocument toDocument(Account account) {
        if (account == null) {
            return null;
        }
        AccountDocument doc = new AccountDocument();
        doc.setAccountId(account.getAccountId().value());
        doc.setEmail(account.getEmail().value());
        doc.setPasswordHash(account.getPasswordHash().value());
        doc.setStatus(account.getStatus().name());
        if (account.getOrganizationId() != null) {
            doc.setOrganizationId(account.getOrganizationId().value());
        }
        if (account.getPlatformAuthority() != null) {
            doc.setPlatformAuthority(account.getPlatformAuthority().name());
        }
        return doc;
    }

    public static Account toDomain(AccountDocument doc) {
        if (doc == null) {
            return null;
        }
        
        OrganizationId orgId = null;
        if (doc.getOrganizationId() != null) {
            orgId = new OrganizationId(doc.getOrganizationId());
        }

        PlatformAuthority platformAuthority = null;
        if (doc.getPlatformAuthority() != null) {
            platformAuthority = PlatformAuthority.valueOf(doc.getPlatformAuthority());
        }

        return Account.reconstitute(
            new AccountId(doc.getAccountId()),
            new Email(doc.getEmail()),
            new PasswordHash(doc.getPasswordHash()),
            AccountStatus.valueOf(doc.getStatus()),
            orgId,
            platformAuthority
        );
    }
}

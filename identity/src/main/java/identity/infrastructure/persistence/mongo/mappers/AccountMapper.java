package identity.infrastructure.persistence.mongo.mappers;

import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.Email;
import identity.domain.model.OrganizationId;
import identity.domain.model.PasswordHash;
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

        return Account.reconstitute(
            new AccountId(doc.getAccountId()),
            new Email(doc.getEmail()),
            new PasswordHash(doc.getPasswordHash()),
            AccountStatus.valueOf(doc.getStatus()),
            orgId
        );
    }
}

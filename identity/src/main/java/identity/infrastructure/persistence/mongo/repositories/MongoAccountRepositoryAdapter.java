package identity.infrastructure.persistence.mongo.repositories;

import identity.application.port.out.AccountRepositoryPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.Email;
import identity.infrastructure.persistence.mongo.mappers.AccountMapper;
import identity.infrastructure.persistence.mongo.repositories.spring.SpringDataAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MongoAccountRepositoryAdapter implements AccountRepositoryPort {

    private final SpringDataAccountRepository repository;
    private final MongoTemplate mongoTemplate;

    @Override
    public void save(Account account) {
        repository.save(AccountMapper.toDocument(account));
    }

    @Override
    public Account findById(AccountId accountId) {
        return repository.findById(accountId.value())
            .map(AccountMapper::toDomain)
            .orElseThrow(() -> new identity.domain.exception.AccountNotFoundException("Account not found: " + accountId.value()));
    }

    @Override
    public Optional<Account> findByEmail(Email email) {
        return repository.findByEmail(email.value())
            .map(AccountMapper::toDomain);
    }

    @Override
    public boolean grantPlatformAuthorityIfAbsent(AccountId accountId) {
        Query query = Query.query(
                Criteria.where("_id").is(accountId.value())
                        .and("status").is("ACTIVE")
                        .and("platformAuthority").is(null)
        );
        Update update = new Update().set("platformAuthority", "ADMINISTRATOR");
        return mongoTemplate.updateFirst(query, update, "accounts").getMatchedCount() == 1L;
    }

    @Override
    public boolean recordLegalAcceptancesIfUnchanged(AccountId accountId, identity.domain.model.PasswordHash validatedHash,
                                                     identity.domain.model.LegalAcceptance terms,
                                                     identity.domain.model.LegalAcceptance dataPolicy) {
        Query query = Query.query(
                Criteria.where("_id").is(accountId.value())
                        .and("status").is("ACTIVE")
                        .and("passwordHash").is(validatedHash.value())
        );
        Update update = new Update();
        if (terms != null) {
            update.set("termsVersion", terms.version()).set("termsAcceptedAt", terms.acceptedAt());
        }
        if (dataPolicy != null) {
            update.set("dataPolicyVersion", dataPolicy.version()).set("dataPolicyAcceptedAt", dataPolicy.acceptedAt());
        }
        return mongoTemplate.updateFirst(query, update, "accounts").getMatchedCount() == 1L;
    }

    @Override
    public boolean markEmailVerifiedIfUnverified(AccountId accountId, java.time.Instant verifiedAt) {
        Query query = Query.query(
                Criteria.where("_id").is(accountId.value())
                        .and("status").is("ACTIVE")
                        .and("emailVerifiedAt").is(null)
        );
        Update update = new Update().set("emailVerifiedAt", verifiedAt);
        return mongoTemplate.updateFirst(query, update, "accounts").getModifiedCount() == 1L;
    }

    @Override
    public Optional<Account> findByGoogleSubject(String googleSubject) {
        if (googleSubject == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(Criteria.where("googleSubject").is(googleSubject)),
                identity.infrastructure.persistence.mongo.documents.AccountDocument.class, "accounts"))
                .map(AccountMapper::toDomain);
    }

    @Override
    public boolean linkGoogleIfUnlinked(AccountId accountId, String googleSubject) {
        Query query = Query.query(Criteria.where("_id").is(accountId.value()).and("status").is("ACTIVE")
                .and("googleSubject").is(null));
        try {
            return mongoTemplate.updateFirst(query, new Update().set("googleSubject", googleSubject), "accounts")
                    .getModifiedCount() == 1L;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new identity.domain.exception.GoogleAccountAlreadyLinkedException("That Google account is linked to another account");
        }
    }

    @Override
    public boolean unlinkGoogleIfLinked(AccountId accountId, String googleSubject) {
        Query query = Query.query(Criteria.where("_id").is(accountId.value()).and("googleSubject").is(googleSubject));
        return mongoTemplate.updateFirst(query, new Update().unset("googleSubject"), "accounts").getModifiedCount() == 1L;
    }

    @Override
    public boolean revokePlatformAuthorityIfHeld(AccountId accountId) {
        Query query = Query.query(
                Criteria.where("_id").is(accountId.value())
                        .and("platformAuthority").is("ADMINISTRATOR")
        );
        Update update = new Update().unset("platformAuthority");
        return mongoTemplate.updateFirst(query, update, "accounts").getMatchedCount() == 1L;
    }

    @Override
    public boolean existsAnyWithPlatformAuthority() {
        Query query = Query.query(Criteria.where("platformAuthority").ne(null));
        return mongoTemplate.exists(query, "accounts");
    }

    @Override
    public java.util.List<Account> findPlatformAdministrators(int limit) {
        Query query = Query.query(Criteria.where("platformAuthority").is("ADMINISTRATOR"))
                .with(org.springframework.data.domain.Sort.by("_id")).limit(limit);
        return mongoTemplate.find(query, identity.infrastructure.persistence.mongo.documents.AccountDocument.class, "accounts")
                .stream().map(AccountMapper::toDomain).toList();
    }
}

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
    public boolean revokePlatformAuthorityIfHeld(AccountId accountId) {
        Query query = Query.query(
                Criteria.where("_id").is(accountId.value())
                        .and("platformAuthority").is("ADMINISTRATOR")
        );
        Update update = new Update().unset("platformAuthority");
        return mongoTemplate.updateFirst(query, update, "accounts").getMatchedCount() == 1L;
    }
}

package identity.infrastructure.persistence.mongo.repositories;

import identity.application.port.out.AccountRepositoryPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.Email;
import identity.infrastructure.persistence.mongo.mappers.AccountMapper;
import identity.infrastructure.persistence.mongo.repositories.spring.SpringDataAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MongoAccountRepositoryAdapter implements AccountRepositoryPort {

    private final SpringDataAccountRepository repository;

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
}

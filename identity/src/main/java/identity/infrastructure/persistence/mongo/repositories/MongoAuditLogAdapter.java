package identity.infrastructure.persistence.mongo.repositories;

import identity.application.port.out.AuditLogPort;
import identity.domain.model.AuditLogEntry;
import identity.infrastructure.persistence.mongo.mappers.AuditLogEntryMapper;
import identity.infrastructure.persistence.mongo.repositories.spring.SpringDataAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MongoAuditLogAdapter implements AuditLogPort {

    private final SpringDataAuditLogRepository repository;

    @Override
    public void record(AuditLogEntry entry) {
        repository.insert(AuditLogEntryMapper.toDocument(entry));
    }
}

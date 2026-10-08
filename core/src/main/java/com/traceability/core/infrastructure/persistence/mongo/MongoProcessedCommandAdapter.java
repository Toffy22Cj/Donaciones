package com.traceability.core.infrastructure.persistence.mongo;

import com.mongodb.MongoException;
import com.traceability.core.application.exception.ConcurrencyConflictException;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
public class MongoProcessedCommandAdapter implements ProcessedCommandRepositoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoProcessedCommandAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void save(String commandId) {
        ProcessedCommandDocument doc = new ProcessedCommandDocument(commandId, Instant.now());
        mongoTemplate.insert(doc);
    }

    @Override
    public boolean exists(String commandId) {
        return mongoTemplate.findById(commandId, ProcessedCommandDocument.class) != null;
    }

    @Override
    public boolean tryClaim(String commandId) {
        return claim(commandId, new Update().setOnInsert("processedAt", Instant.now()));
    }

    /**
     * Mismo reclamo que {@link #tryClaim(String)} guardando el resultado que lo ganó. Dentro de una transacción, dos
     * reclamos concurrentes del mismo {@code _id} no pueden confirmarse los dos: el segundo recibe un conflicto de
     * escritura, que se traduce a {@link ConcurrencyConflictException} para que {@code CommandRetryTemplate} lo
     * reintente y encuentre el reclamo hecho.
     */
    @Override
    public boolean tryClaim(String commandId, String outcome) {
        return claim(commandId, new Update().setOnInsert("processedAt", Instant.now()).setOnInsert("outcome", outcome));
    }

    @Override
    public Optional<String> findOutcome(String commandId) {
        return Optional.ofNullable(mongoTemplate.findById(commandId, ProcessedCommandDocument.class))
                .map(ProcessedCommandDocument::getOutcome);
    }

    private boolean claim(String commandId, Update update) {
        try {
            Query query = new Query(Criteria.where("_id").is(commandId));
            FindAndModifyOptions options = new FindAndModifyOptions().upsert(true).returnNew(false);
            ProcessedCommandDocument prev = mongoTemplate.findAndModify(query, update, options, ProcessedCommandDocument.class);
            return prev == null;
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            throw new ConcurrencyConflictException("Write conflict claiming command " + commandId, e);
        } catch (DataAccessException e) {
            if (isTransientTransactionError(e)) {
                throw new ConcurrencyConflictException("Transient transaction error claiming command " + commandId, e);
            }
            throw e;
        }
    }

    private static boolean isTransientTransactionError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof MongoException me && (me.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                    || me.getCode() == 112)) {
                return true;
            }
        }
        return false;
    }
}

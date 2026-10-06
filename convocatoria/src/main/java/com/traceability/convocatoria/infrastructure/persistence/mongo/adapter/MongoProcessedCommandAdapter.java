package com.traceability.convocatoria.infrastructure.persistence.mongo.adapter;

import com.traceability.convocatoria.application.idempotency.CommandClaimCollisionException;
import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.application.idempotency.ProcessedCommand;
import com.traceability.convocatoria.application.port.out.ProcessedCommandPort;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ProcessedCommandDocument;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Adaptador Mongo del registro de comandos procesados del módulo (Enmienda §3.5, N12; implementation_plan.md §7.1).
 * Reclamo atómico por {@code findAndModify} con {@code upsert}, igual que el patrón de {@code core}, pero una
 * colisión se señala como {@link CommandClaimCollisionException} (reintento), no como conflicto de dominio.
 * <p>
 * Comandos de cliente: {@code _id = commandId} (texto), espacio compartido con la regla I1. Comandos de sistema:
 * {@code _id = {commandType, commandId}} (subdocumento) en la misma colección y con el mismo reclamo atómico. Un
 * {@code _id} de tipo documento nunca es igual a uno de tipo texto, así que ninguna clave de cliente puede ocupar una
 * de sistema (ADR-037 Enmienda 2 §3.3).
 */
@Component
public class MongoProcessedCommandAdapter implements ProcessedCommandPort {

    private final MongoTemplate mongoTemplate;

    public MongoProcessedCommandAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<ProcessedCommand> claim(String commandId, CommandType commandType) {
        if (commandType.isSystem()) {
            throw new IllegalArgumentException(commandType + " is a system command; use claimSystemCommand");
        }
        Query query = Query.query(Criteria.where("_id").is(commandId));
        Update update = new Update()
                .setOnInsert("commandType", commandType.name())
                .setOnInsert("processedAt", Instant.now());
        try {
            ProcessedCommandDocument previous = mongoTemplate.findAndModify(query, update,
                    FindAndModifyOptions.options().upsert(true).returnNew(false), ProcessedCommandDocument.class);
            return Optional.ofNullable(previous).map(MongoProcessedCommandAdapter::toDomain);
        } catch (DuplicateKeyException e) {
            throw new CommandClaimCollisionException(commandId, e);
        }
    }

    @Override
    public void saveResult(String commandId, Map<String, String> result) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(commandId)),
                new Update().set("result", result), ProcessedCommandDocument.class);
    }

    @Override
    public Optional<ProcessedCommand> find(String commandId) {
        return Optional.ofNullable(mongoTemplate.findById(commandId, ProcessedCommandDocument.class))
                .map(MongoProcessedCommandAdapter::toDomain);
    }

    @Override
    public Optional<ProcessedCommand> claimSystemCommand(CommandType commandType, String commandId) {
        Document key = systemKey(commandType, commandId);
        Update update = new Update()
                .setOnInsert("commandType", commandType.name())
                .setOnInsert("processedAt", Instant.now());
        try {
            Document previous = mongoTemplate.findAndModify(Query.query(Criteria.where("_id").is(key)), update,
                    FindAndModifyOptions.options().upsert(true).returnNew(false), Document.class,
                    ProcessedCommandDocument.COLLECTION);
            return Optional.ofNullable(previous).map(d -> systemToDomain(commandType, commandId, d));
        } catch (DuplicateKeyException e) {
            throw new CommandClaimCollisionException(commandType + "/" + commandId, e);
        }
    }

    @Override
    public void saveSystemCommandResult(CommandType commandType, String commandId, Map<String, String> result) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(systemKey(commandType, commandId))),
                new Update().set("result", result), ProcessedCommandDocument.COLLECTION);
    }

    @Override
    public Optional<ProcessedCommand> findSystemCommand(CommandType commandType, String commandId) {
        return Optional.ofNullable(mongoTemplate.findOne(
                        Query.query(Criteria.where("_id").is(systemKey(commandType, commandId))), Document.class,
                        ProcessedCommandDocument.COLLECTION))
                .map(d -> systemToDomain(commandType, commandId, d));
    }

    /** Clave de un comando de sistema: subdocumento con orden de campos fijo ({@code commandType}, {@code commandId}). */
    public static Document systemKey(CommandType commandType, String commandId) {
        if (!commandType.isSystem()) {
            throw new IllegalArgumentException(commandType + " is not a system command");
        }
        return new Document("commandType", commandType.name()).append("commandId", commandId);
    }

    @SuppressWarnings("unchecked")
    private static ProcessedCommand systemToDomain(CommandType commandType, String commandId, Document d) {
        return new ProcessedCommand(commandId, commandType, (Map<String, String>) d.get("result"));
    }

    private static ProcessedCommand toDomain(ProcessedCommandDocument d) {
        return new ProcessedCommand(d.commandId, CommandType.valueOf(d.commandType), d.result);
    }
}

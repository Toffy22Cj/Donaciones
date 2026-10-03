package com.traceability.convocatoria.infrastructure.persistence.mongo.adapter;

import com.traceability.convocatoria.application.port.out.CampaignAssignmentRepositoryPort;
import com.traceability.convocatoria.domain.exception.EmployeeAlreadyAssignedException;
import com.traceability.convocatoria.domain.model.AssignmentStatus;
import com.traceability.convocatoria.domain.model.CampaignAssignment;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignAssignmentDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.mapper.CampaignAssignmentMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador Mongo de {@link CampaignAssignmentRepositoryPort} (ADR-037 §2.4; Enmienda §4.2):
 * {@code DuplicateKeyException} sobre el índice único parcial → {@link EmployeeAlreadyAssignedException}.
 */
@Component
public class MongoCampaignAssignmentRepositoryAdapter implements CampaignAssignmentRepositoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoCampaignAssignmentRepositoryAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void insert(CampaignAssignment assignment) {
        try {
            mongoTemplate.insert(CampaignAssignmentMapper.toDocument(assignment));
        } catch (DuplicateKeyException e) {
            if (e.getMessage() != null && e.getMessage().contains(CampaignAssignmentDocument.ACTIVE_EMPLOYEE_INDEX)) {
                throw new EmployeeAlreadyAssignedException(
                        "EMPLOYEE " + assignment.getEmployeeRef() + " is already responsible of an active campaign");
            }
            throw e;
        }
    }

    @Override
    public List<CampaignAssignment> findActiveByCampaignRefAndResponsible(String campaignRef, String responsibleRef) {
        Query query = Query.query(Criteria.where("campaignRef").is(campaignRef)
                .and("employeeRef").is(responsibleRef)
                .and("status").is(AssignmentStatus.ACTIVE.name()));
        return mongoTemplate.find(query, CampaignAssignmentDocument.class).stream()
                .map(CampaignAssignmentMapper::toDomain).toList();
    }

    @Override
    public List<CampaignAssignment> findByCampaignRef(String campaignRef) {
        Query query = Query.query(Criteria.where("campaignRef").is(campaignRef)).with(Sort.by("assignedAt"));
        return mongoTemplate.find(query, CampaignAssignmentDocument.class).stream()
                .map(CampaignAssignmentMapper::toDomain).toList();
    }

    @Override
    public Optional<CampaignAssignment> findById(String assignmentId) {
        return Optional.ofNullable(mongoTemplate.findById(assignmentId, CampaignAssignmentDocument.class))
                .map(CampaignAssignmentMapper::toDomain);
    }

    @Override
    public boolean markRemovedIfActive(String assignmentId, Instant removedAt) {
        Query query = Query.query(Criteria.where("_id").is(assignmentId).and("status").is(AssignmentStatus.ACTIVE.name()));
        Update update = new Update().set("status", AssignmentStatus.REMOVED.name()).set("removedAt", removedAt);
        return mongoTemplate.updateFirst(query, update, CampaignAssignmentDocument.class).getMatchedCount() == 1;
    }
}

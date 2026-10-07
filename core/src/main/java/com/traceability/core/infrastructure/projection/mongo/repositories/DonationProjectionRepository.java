package com.traceability.core.infrastructure.projection.mongo.repositories;

import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DonationProjectionRepository extends MongoRepository<DonationProjectionDocument, String> {

    /** Proyección que contiene la asignación (Camino A: un activo raíz se proyecta en el Fund de su asignación). */
    Optional<DonationProjectionDocument> findFirstByAllocationsAllocationId(String allocationId);
}

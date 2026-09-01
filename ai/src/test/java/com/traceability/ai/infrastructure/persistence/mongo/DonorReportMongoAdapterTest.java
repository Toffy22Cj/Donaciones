package com.traceability.ai.infrastructure.persistence.mongo;

import com.traceability.ai.application.config.AiNarrativeProperties;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import com.traceability.ai.domain.narrative.NarrativeConstants;
import com.traceability.ai.domain.narrative.NarrativeSource;
import com.traceability.ai.infrastructure.persistence.mongo.documents.DonorReportDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.ai.openai.api-key=dummy")
@Testcontainers
class DonorReportMongoAdapterTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Configuration
    @SpringBootApplication(scanBasePackages = "com.traceability.ai.infrastructure.persistence.mongo")
    @EnableMongoRepositories(basePackages = "com.traceability.ai.infrastructure.persistence.mongo.repositories")
    static class TestConfig {
    }

    @Autowired
    private DonorReportMongoAdapter adapter;

    @Autowired
    private MongoTemplate mongoTemplate;

    @AfterEach
    void clean() {
        mongoTemplate.dropCollection(DonorReportDocument.class);
    }

    @Test
    void testSaveAndFind_ProducesCorrectDTO() {
        Instant generated = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        DonorReportDTO report = new DonorReportDTO(
                "Test narrative",
                NarrativeSource.LLM_GENERATED,
                "gpt-4o",
                "v1",
                NarrativeConstants.SNAPSHOT_HASH_V1,
                10L,
                generated,
                null
        );

        DonorReportDTO saved = adapter.save("fund-123", report);
        assertNotNull(saved);

        Optional<DonorReportDTO> found = adapter.findByLogicalKey("fund-123", 10L, NarrativeConstants.SNAPSHOT_HASH_V1, "v1", "gpt-4o");
        assertTrue(found.isPresent());
        DonorReportDTO dto = found.get();
        assertEquals("Test narrative", dto.narrativeText());
        assertEquals(NarrativeSource.LLM_GENERATED, dto.source());
        assertEquals(10L, dto.auditFactsSequence());
        assertEquals(generated, dto.generatedAt());
    }

    @Test
    void testSaveAndFind_Fallback_WithRetryAt() {
        Instant generated = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant nextRetry = generated.plus(java.time.Duration.ofMinutes(15)).truncatedTo(ChronoUnit.MILLIS);

        DonorReportDTO report = new DonorReportDTO(
                "Fallback narrative",
                NarrativeSource.FALLBACK_TEMPLATE,
                "FALLBACK",
                "v1",
                NarrativeConstants.SNAPSHOT_HASH_V1,
                11L,
                generated,
                nextRetry
        );

        adapter.save("fund-999", report);

        Optional<DonorReportDTO> found = adapter.findByLogicalKey("fund-999", 11L, NarrativeConstants.SNAPSHOT_HASH_V1, "v1", "FALLBACK");
        assertTrue(found.isPresent());
        DonorReportDTO dto = found.get();
        assertEquals(NarrativeSource.FALLBACK_TEMPLATE, dto.source());
        assertEquals("FALLBACK", dto.modelIdentifier());
        assertEquals(nextRetry, dto.nextRetryAt());
    }
}

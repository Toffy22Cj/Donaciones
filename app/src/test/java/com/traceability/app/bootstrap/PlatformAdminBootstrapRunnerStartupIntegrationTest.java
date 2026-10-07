package com.traceability.app.bootstrap;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.traceability.app.TraceabilityApplication;
import identity.domain.exception.BootstrapTargetAccountNotFoundException;
import identity.domain.model.AuditAction;
import identity.infrastructure.persistence.mongo.repositories.MongoPlatformAuthorityStateAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PlatformAdminBootstrapRunnerStartupIntegrationTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @Test
    void startupFails_whenBootstrapEnabled_andTargetAccountNotFound_andWritesNothing() {
        Throwable thrown = assertThrows(Throwable.class, () -> {
            new SpringApplicationBuilder(TraceabilityApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.data.mongodb.uri=" + mongoDBContainer.getReplicaSetUrl(),
                            "traceability.bootstrap.platform-admin.enabled=true",
                            "traceability.bootstrap.platform-admin.email=nonexistent@example.com",
                            "crypto.anchor.poll.delay=9999999",
                            "crypto.anchor.submit.delay=9999999",
                            "crypto.anchor.stuck-monitor.delay=9999999",
                            "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                            "crypto.web3j.node-url=http://dummy-node",
                            "spring.ai.openai.api-key=dummy-api-key"
                    )
                    .run();
        });

        // 1. Verify cause chain contains BootstrapTargetAccountNotFoundException
        boolean foundExpected = false;
        Throwable current = thrown;
        while (current != null) {
            if (current instanceof BootstrapTargetAccountNotFoundException) {
                foundExpected = true;
                break;
            }
            current = current.getCause();
        }
        assertTrue(foundExpected, "Cause chain must contain BootstrapTargetAccountNotFoundException but was: " + thrown);

        // 2. Ajuste 5: verify nothing was written to MongoDB
        try (MongoClient mongoClient = MongoClients.create(mongoDBContainer.getReplicaSetUrl())) {
            MongoTemplate mongoTemplate = new MongoTemplate(mongoClient, "test");

            // a. Singleton does not exist
            Object stateDoc = mongoTemplate.findById(
                    MongoPlatformAuthorityStateAdapter.SINGLETON_ID,
                    Object.class,
                    MongoPlatformAuthorityStateAdapter.COLLECTION_NAME
            );
            assertNull(stateDoc, "platform_authority_state singleton must not exist");

            // b. No accounts with platform authority
            Query authAccountsQuery = Query.query(Criteria.where("platformAuthority").ne(null));
            long authAccountsCount = mongoTemplate.count(authAccountsQuery, "accounts");
            assertEquals(0L, authAccountsCount, "No account should have platform authority");

            // c. Zero BOOTSTRAP_PLATFORM_AUTHORITY audit entries
            Query auditQuery = Query.query(Criteria.where("action").is(AuditAction.BOOTSTRAP_PLATFORM_AUTHORITY.name()));
            long auditCount = mongoTemplate.count(auditQuery, "identity_audit_log");
            assertEquals(0L, auditCount, "There must be 0 BOOTSTRAP_PLATFORM_AUTHORITY audit entries");
        }
    }
}

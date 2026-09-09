package identity.infrastructure.persistence.mongo;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@EnableMongoRepositories(basePackages = "identity.infrastructure.persistence.mongo.repositories.spring")
public class IdentityTestApplication {
}

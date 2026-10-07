package identity;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.Container;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class MongoReplicaSetTest {

    @Test
    public void testReplicaSetStatus() throws Exception {
        try (MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))) {
            mongoDBContainer.start();
            
            // Execute rs.status() inside the container
            Container.ExecResult result = mongoDBContainer.execInContainer("mongosh", "--quiet", "--eval", "rs.status().ok");
            
            String output = result.getStdout().trim();
            System.out.println("REPLICA SET STATUS OK: " + output);
            
            // Output docker inspect or command line arguments
            System.out.println("CONTAINER COMMAND: " + String.join(" ", mongoDBContainer.getCommandParts()));

            // According to Testcontainers docs since 1.16+, MongoDBContainer is automatically a replica set
            assertTrue(output.equals("1") || output.contains("1"));
        }
    }
}

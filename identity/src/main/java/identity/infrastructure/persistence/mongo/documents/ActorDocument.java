package identity.infrastructure.persistence.mongo.documents;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Plain persistence document for AuditActor subdocument in MongoDB.
 * Avoids storing _class of sealed domain interface.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ActorDocument {
    private String type; // "ACCOUNT" | "SYSTEM"
    private String accountId; // nullable
    private String processId; // nullable
}

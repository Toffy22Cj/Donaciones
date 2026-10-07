package identity.infrastructure.persistence.mongo.documents;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Fila de la tabla cuenta ↔ seudónimo (ADR-048 §7: "solo la lee identity, nunca sale por la API ni a los logs").
 * Un ArchUnit impide que otra capa la use.
 */
@Document(DonorPseudonymDocument.COLLECTION)
@Getter
@Setter
public class DonorPseudonymDocument {
    public static final String COLLECTION = "donor_pseudonyms";

    @Id
    private String accountId;
    @Indexed(unique = true)
    private String pseudonym;
}

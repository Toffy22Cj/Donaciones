package identity.infrastructure.persistence.mongo.documents;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Document("accounts")
// ADR-050 D9 (P-G-12 del plan de Google): un sub de Google, una cuenta; parcial, porque las demás no lo guardan
@org.springframework.data.mongodb.core.index.CompoundIndex(name = AccountDocument.GOOGLE_SUBJECT_INDEX,
        def = "{'googleSubject': 1}", unique = true, partialFilter = "{'googleSubject': {'$exists': true}}")
@Getter
@Setter
public class AccountDocument {
    public static final String GOOGLE_SUBJECT_INDEX = "uq_google_subject";

    @Id
    private String accountId;

    @Indexed(unique = true)
    private String email;

    private String passwordHash;
    private String status;
    private String organizationId; // nullable
    private String platformAuthority; // nullable
    private Instant credentialsNotBefore; // nullable (ADR-050 D4)
    // ADR-050 D1 (perfil) y D2 (aceptaciones), nulos en las cuentas existentes
    private String givenName;
    private String familyName;
    private String termsVersion;
    private Instant termsAcceptedAt;
    private String dataPolicyVersion;
    private Instant dataPolicyAcceptedAt;
    private Instant emailVerifiedAt; // nullable (ADR-050 Enmienda 2, E2-1)
    private String googleSubject; // nullable (ADR-050 D9): el sub de Google, único entre las que lo tienen
}

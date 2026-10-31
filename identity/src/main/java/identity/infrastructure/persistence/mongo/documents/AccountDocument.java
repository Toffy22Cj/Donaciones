package identity.infrastructure.persistence.mongo.documents;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Document("accounts")
@Getter
@Setter
public class AccountDocument {
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
}

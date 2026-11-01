package identity.infrastructure.persistence.mongo.documents;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Document("organizations")
// ficha R-7, P5: un NIT, una organización; parcial, porque las que no lo tienen no lo guardan
@CompoundIndex(name = OrganizationDocument.NIT_INDEX, def = "{'nit': 1}", unique = true, partialFilter = "{'nit': {'$exists': true}}")
@Getter
@Setter
public class OrganizationDocument {
    public static final String NIT_INDEX = "uq_organization_nit";

    @Id
    private String organizationId;
    private String type;
    private List<MembershipDocument> members;
    private String verificationStatus;
    private String verificationInformationRequest;
    /** Nombre público, opcional (plan B6-a, Q-B6A-1). */
    private String name;
    /** Referencia del logo, opcional (ADR-051 F9). Los documentos sin el campo se leen sin logo. */
    private String logoObjectKey;
    // ADR-050 D2: términos de organización aceptados al crearla (nulos en las existentes)
    private String organizationTermsVersion;
    private String organizationTermsAcceptedBy;
    private java.time.Instant organizationTermsAcceptedAt;
    // ficha R-7 §3: el perfil, ausente en las existentes (un campo nulo no se escribe)
    private String description;
    private String website;
    private String departmentCode;
    private String municipalityCode;
    private String contactEmail;
    private String legalName;
    private String nit;
    private String nitCheckDigit;
    private Boolean specialTaxRegime;
    private String contactPhone;
}

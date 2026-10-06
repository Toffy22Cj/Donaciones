package identity.domain.model;

import identity.domain.exception.InvalidInformationRequestMessageException;

/**
 * Value Object representing an information request message for an organization.
 *
 * References: ADR-038 §2.6.
 */
public record InformationRequestMessage(String value) {

    public InformationRequestMessage {
        if (value == null) {
            throw new InvalidInformationRequestMessageException("Information request message cannot be null");
        }
        value = value.strip();
        if (value.isEmpty()) {
            throw new InvalidInformationRequestMessageException("Information request message cannot be blank");
        }
        if (value.codePointCount(0, value.length()) > 2000) {
            throw new InvalidInformationRequestMessageException("Information request message exceeds 2000 code points");
        }
    }
}

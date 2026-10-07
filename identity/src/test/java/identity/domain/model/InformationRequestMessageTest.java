package identity.domain.model;

import identity.domain.exception.InvalidInformationRequestMessageException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InformationRequestMessageTest {

    @Test
    void nullValue_throwsException() {
        InvalidInformationRequestMessageException ex = assertThrows(
                InvalidInformationRequestMessageException.class,
                () -> new InformationRequestMessage(null)
        );
        assertEquals("Information request message cannot be null", ex.getMessage());
    }

    @Test
    void emptyValue_throwsException() {
        InvalidInformationRequestMessageException ex = assertThrows(
                InvalidInformationRequestMessageException.class,
                () -> new InformationRequestMessage("")
        );
        assertEquals("Information request message cannot be blank", ex.getMessage());
    }

    @Test
    void whitespaceOnlyValue_throwsException_andDoesNotLeakWhitespaceInMessage() {
        String whitespaceInput = "   ";
        InvalidInformationRequestMessageException ex = assertThrows(
                InvalidInformationRequestMessageException.class,
                () -> new InformationRequestMessage(whitespaceInput)
        );
        assertEquals("Information request message cannot be blank", ex.getMessage());
        assertFalse(ex.getMessage().contains(whitespaceInput));
    }

    @Test
    void validValueWithSurroundingSpaces_isStripped() {
        InformationRequestMessage message = new InformationRequestMessage("  hola  ");
        assertEquals("hola", message.value());
    }

    @Test
    void exactly2000Characters_isAccepted() {
        String input2000 = "a".repeat(2000);
        InformationRequestMessage message = new InformationRequestMessage(input2000);
        assertEquals(input2000, message.value());
    }

    @Test
    void exactly2001Characters_throwsException_andDoesNotLeakContentInExceptionMessage() {
        String distinctivePrefix = "SECRETO-PII-";
        String input2001 = (distinctivePrefix.repeat(200)).substring(0, 2001);
        assertEquals(2001, input2001.codePointCount(0, input2001.length()));

        InvalidInformationRequestMessageException ex = assertThrows(
                InvalidInformationRequestMessageException.class,
                () -> new InformationRequestMessage(input2001)
        );
        assertEquals("Information request message exceeds 2000 code points", ex.getMessage());
        assertFalse(ex.getMessage().contains(distinctivePrefix), "Exception message must not contain any part of the sensitive input");
    }

    @Test
    void exactly2000SupplementaryEmojis_isAcceptedByCodePointCount() {
        // "😀" is U+1F600, 2 UTF-16 code units (chars), but 1 Unicode code point
        String emoji = "😀";
        assertEquals(2, emoji.length());
        assertEquals(1, emoji.codePointCount(0, emoji.length()));

        String emojis2000 = emoji.repeat(2000);
        assertEquals(4000, emojis2000.length());
        assertEquals(2000, emojis2000.codePointCount(0, emojis2000.length()));

        InformationRequestMessage message = new InformationRequestMessage(emojis2000);
        assertEquals(emojis2000, message.value());
    }

    @Test
    void exactly2001SupplementaryEmojis_throwsException() {
        String emoji = "😀";
        String emojis2001 = emoji.repeat(2001);
        assertEquals(2001, emojis2001.codePointCount(0, emojis2001.length()));

        InvalidInformationRequestMessageException ex = assertThrows(
                InvalidInformationRequestMessageException.class,
                () -> new InformationRequestMessage(emojis2001)
        );
        assertEquals("Information request message exceeds 2000 code points", ex.getMessage());
    }
}

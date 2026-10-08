package com.traceability.app.web.campaign;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** DD-53 rehecha (T-35): cursor opaco, autenticado y con un IV distinto cada vez. */
class DiscoveryCursorCodecTest {

    static final String CODE = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    private final DiscoveryCursorCodec codec = DiscoveryCursorCodec.withRandomKey();

    @Test
    void roundTrip() {
        assertThat(codec.decode(codec.encode(CODE))).hasValue(CODE);
    }

    @Test
    void theCursorDoesNotRevealThePublicCode_andChangesEveryTime() {
        String a = codec.encode(CODE);
        String b = codec.encode(CODE);

        assertThat(a).isNotEqualTo(b);
        assertThat(new String(Base64.getUrlDecoder().decode(a), StandardCharsets.ISO_8859_1)).doesNotContain(CODE);
        assertThat(a).doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(CODE.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void tamperedForgedOrForeignCursors_areInvalid() {
        String cursor = codec.encode(CODE);
        char[] chars = cursor.toCharArray();
        chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';

        assertThat(codec.decode(new String(chars))).isEmpty();
        assertThat(codec.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(CODE.getBytes(StandardCharsets.US_ASCII))))
                .isEmpty();
        assertThat(DiscoveryCursorCodec.withRandomKey().decode(cursor)).as("otra clave").isEqualTo(Optional.empty());
        assertThat(codec.decode("!!")).isEmpty();
        assertThat(codec.decode("")).isEmpty();
    }

    @Test
    void aConfiguredKey_mustBe32BytesInBase64() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        DiscoveryCursorCodec a = DiscoveryCursorCodec.fromConfiguredKey(key);
        DiscoveryCursorCodec b = DiscoveryCursorCodec.fromConfiguredKey(key);
        assertThat(b.decode(a.encode(CODE))).as("misma clave en dos instancias").hasValue(CODE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DiscoveryCursorCodec.fromConfiguredKey("c2hvcnQ="))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("c2hvcnQ=");
    }
}

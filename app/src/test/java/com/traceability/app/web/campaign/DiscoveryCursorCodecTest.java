package com.traceability.app.web.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-53 rehecha (T-35): cursor opaco, autenticado y con un IV distinto cada vez. Ficha R-1 §5 y §8, test 2: el texto
 * cifrado va en UTF-8 (lleva {@code q} con tildes), la carga v2 hace ida y vuelta, y un cursor v1 sigue valiendo.
 */
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
    // --- ficha R-1, test 2 ---

    /** Emitido por el códec anterior (US-ASCII, en {@code 993c3e8}) con {@link #LEGACY_KEY}, para {@link #CODE}. */
    static final String LEGACY_CURSOR = "FkbQRXg6vJw0H9o3rjb4x_pjA4cDnqqKQ5BxoaJVJD2Q_RFaXyf1bEYATXJC_7b7VMhDZWs1";
    static final String LEGACY_KEY = "cjEtbGVnYWN5LWN1cnNvci10ZXN0LWtleS0zMmJ5dGU=";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aCursorEmittedBeforeTheChange_stillDecodesToItsPublicCode() {
        assertThat(DiscoveryCursorCodec.fromConfiguredKey(LEGACY_KEY).decode(LEGACY_CURSOR)).hasValue(CODE);
    }

    @Test
    void textWithAccents_roundTripsInUtf8() {
        String text = "{\"q\":\"educación niños ñ 𝔸\"}";
        assertThat(codec.decode(codec.encode(text))).hasValue(text);
    }

    @Test
    void aV2Payload_roundTripsWithAllItsFields_throughTheCodec() {
        DiscoveryCursorPayload payload = new DiscoveryCursorPayload(2, "educacion rural", "IN_KIND", "ending_soon",
                "2027-01-11T00:00:00Z", CODE);

        String cursor = codec.encode(payload.toJson(json));

        assertThat(codec.decode(cursor).flatMap(text -> DiscoveryCursorPayload.fromJson(text, json))).hasValue(payload);
        DiscoveryCursorPayload defaults = new DiscoveryCursorPayload(2, null, null, null, null, CODE);
        assertThat(DiscoveryCursorPayload.fromJson(defaults.toJson(json), json)).hasValue(defaults);
    }

    @Test
    void aTamperedOrForeignV2Cursor_isEmpty_andAV1TextIsNotAV2Payload() {
        String cursor = codec.encode(new DiscoveryCursorPayload(2, "educacion", null, "recent",
                "2027-01-11T00:00:00Z", CODE).toJson(json));
        char[] chars = cursor.toCharArray();
        chars[chars.length - 3] = chars[chars.length - 3] == 'A' ? 'B' : 'A';

        assertThat(codec.decode(new String(chars))).isEmpty();
        assertThat(DiscoveryCursorCodec.withRandomKey().decode(cursor)).isEmpty();
        assertThat(DiscoveryCursorPayload.fromJson(CODE, json)).isEmpty();
        assertThat(DiscoveryCursorPayload.fromJson("{\"v\":1,\"lastPublicCode\":\"" + CODE + "\"}", json)).isEmpty();
        assertThat(DiscoveryCursorPayload.fromJson("{\"v\":2}", json)).isEmpty();
        assertThat(DiscoveryCursorPayload.fromJson("[2]", json)).isEmpty();
        assertThat(DiscoveryCursorPayload.fromJson("{\"v\":2,\"lastPublicCode\":\"" + CODE + "\",\"otro\":1}", json))
                .isEmpty();
    }
}

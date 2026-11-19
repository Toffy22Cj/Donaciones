package com.traceability.core.application.projection;

import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV4Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV5Payload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-051 Enmienda 2, plan de la Tarea 1, test 13: la vista común de un {@code ASSET_REGISTERED} es la misma con la
 * 4.0 y con la 5.0 (con y sin foto). Si {@link AssetProjectionRouting#registration} no conoce la 5.0, el registro se
 * trataría como «no es un registro» y el activo no se indexaría.
 */
class AssetProjectionRoutingVersionsTest {

    private static AssetRegisteredV4Payload v4() {
        return new AssetRegisteredV4Payload("a-1", "FOOD", new BigDecimal("10.0000"), "KG", "Bodega", "cust-1", null, "a-1",
                "alloc-1", null, "org-1", "donor-1", "donation-1", "camp-1", "05001", "MEDELLÍN, ANTIOQUIA", "DIVIPOLA-T");
    }

    private static AssetRegisteredV5Payload v5(String photo) {
        return new AssetRegisteredV5Payload("a-1", "FOOD", new BigDecimal("10.0000"), "KG", "Bodega", "cust-1", null, "a-1",
                "alloc-1", null, "org-1", "donor-1", "donation-1", "camp-1", "05001", "MEDELLÍN, ANTIOQUIA", "DIVIPOLA-T",
                photo);
    }

    @Test
    void theCommonViewOfARegistration_isTheSameFor4And5_withAndWithoutAPhoto() {
        var old = AssetProjectionRouting.registration(v4());
        assertThat(old).isPresent();
        assertThat(AssetProjectionRouting.registration(v5(null))).isEqualTo(old);
        assertThat(AssetProjectionRouting.registration(v5("sha256:" + "a".repeat(64)))).isEqualTo(old);
    }
}

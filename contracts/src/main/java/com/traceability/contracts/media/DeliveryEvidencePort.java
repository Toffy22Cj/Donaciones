package com.traceability.contracts.media;

/**
 * Vínculo de una foto de evidencia con el activo que se entrega (ADR-051 F3; plan de la Tarea 4, §3). Declarado en
 * contracts (ADR-051, regla 3.5 cubierta en el ADR) para que lo llame {@code core} desde
 * {@code PhysicalAssetCommandService.deliverAsset}; lo implementa {@code app}, dueño de {@code media_objects}.
 * <p>
 * Escritura condicional: vincula si la foto no está vinculada o ya lo está a ese mismo {@code assetId} (reintentar la
 * entrega del mismo activo se permite sin error). Si está vinculada a otro activo, gana la primera: dos entregas
 * simultáneas de activos distintos con la misma foto no pueden vincularla las dos.
 */
public interface DeliveryEvidencePort {

    /**
     * @param evidenceRef {@code sha256:<64 hex>}, ya validado por quien llama
     * @throws EvidenceAlreadyBoundException la foto está vinculada a otro activo (409)
     * @throws EvidenceNotFoundException     la foto no existe, no es de evidencia, es de otra organización o está
     *                                       borrada; el mismo para todos esos casos (400)
     */
    void bindToAsset(String organizationId, String evidenceRef, String assetId);
}

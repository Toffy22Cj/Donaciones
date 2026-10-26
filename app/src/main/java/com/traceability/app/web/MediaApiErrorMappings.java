package com.traceability.app.web;

import com.traceability.api.web.ApiErrorMapping;
import com.traceability.api.web.ApiErrorMappings;
import com.traceability.api.web.ApiExceptionHandler;
import com.traceability.app.application.media.ImageTooLargeException;
import com.traceability.app.application.media.CampaignCoverNotFoundException;
import com.traceability.app.application.media.OrganizationLogoNotFoundException;
import com.traceability.app.application.media.UnsupportedImageFormatException;
import com.traceability.contracts.media.MediaStorageUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

/**
 * Excepciones de las imágenes (ADR-051 F2, F4 y F6; plan de la Tarea 2 §4.5, P-2) en el formato de ADR-041 §2.5. El
 * límite de Spring (F8: 5 MB por archivo, 6 MB por petición) responde lo mismo que el del propósito: 413
 * {@code ImageTooLarge}.
 */
@Component
public class MediaApiErrorMappings implements ApiErrorMappings {

    static final String IMAGE_TOO_LARGE = "ImageTooLarge";

    @Override
    public String module() {
        return "media";
    }

    @Override
    public List<ApiErrorMapping> mappings() {
        return List.of(
                new ApiErrorMapping(ImageTooLargeException.class, HttpStatus.PAYLOAD_TOO_LARGE, IMAGE_TOO_LARGE),
                new ApiErrorMapping(MaxUploadSizeExceededException.class, HttpStatus.PAYLOAD_TOO_LARGE, IMAGE_TOO_LARGE),
                new ApiErrorMapping(UnsupportedImageFormatException.class, HttpStatus.UNSUPPORTED_MEDIA_TYPE, null),
                new ApiErrorMapping(MediaStorageUnavailableException.class, HttpStatus.SERVICE_UNAVAILABLE, null),
                // D-T2-1: la única causa de 404 de las dos rutas del logo
                new ApiErrorMapping(OrganizationLogoNotFoundException.class, HttpStatus.NOT_FOUND, ApiExceptionHandler.NOT_FOUND),
                // ADR-051, Tarea 3: el mismo 404 en todos los casos de la portada pública
                new ApiErrorMapping(CampaignCoverNotFoundException.class, HttpStatus.NOT_FOUND, ApiExceptionHandler.NOT_FOUND),
                // ADR-051 F3 (Tarea 4): una foto, un activo; y el mismo 400 para inexistente, ajena, no evidencia o borrada
                new ApiErrorMapping(com.traceability.contracts.media.EvidenceAlreadyBoundException.class, HttpStatus.CONFLICT, null),
                new ApiErrorMapping(com.traceability.contracts.media.EvidenceNotFoundException.class, HttpStatus.BAD_REQUEST, null));
    }
}

package com.traceability.api.web;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;

import java.util.List;

/** Errores de la forma de la petición → 400 (plan B6-0 §2.3). Sin eco del valor recibido. */
@Component
public class ApiBaseErrorMappings implements ApiErrorMappings {

    @Override
    public String module() {
        return "api";
    }

    @Override
    public List<ApiErrorMapping> mappings() {
        return List.of(
                new ApiErrorMapping(InvalidCommandIdException.class, HttpStatus.BAD_REQUEST, ApiExceptionHandler.BAD_REQUEST),
                new ApiErrorMapping(InvalidRequestFieldException.class, HttpStatus.BAD_REQUEST, ApiExceptionHandler.BAD_REQUEST),
                new ApiErrorMapping(IllegalArgumentException.class, HttpStatus.BAD_REQUEST, ApiExceptionHandler.BAD_REQUEST),
                new ApiErrorMapping(HttpMessageNotReadableException.class, HttpStatus.BAD_REQUEST, ApiExceptionHandler.BAD_REQUEST),
                // un @PathVariable o @RequestParam que no convierte al tipo pedido
                new ApiErrorMapping(TypeMismatchException.class, HttpStatus.BAD_REQUEST, ApiExceptionHandler.BAD_REQUEST));
    }
}

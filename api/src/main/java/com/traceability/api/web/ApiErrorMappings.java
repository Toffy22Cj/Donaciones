package com.traceability.api.web;

import java.util.List;

/**
 * Las traducciones de un módulo (Q-B60-4: un solo manejador; cada módulo aporta un bean de este tipo). Si dos módulos
 * declaran la misma excepción, la aplicación no arranca.
 */
public interface ApiErrorMappings {

    /** Nombre del módulo, para el mensaje de error cuando hay duplicados. */
    String module();

    List<ApiErrorMapping> mappings();
}

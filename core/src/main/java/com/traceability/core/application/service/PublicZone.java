package com.traceability.core.application.service;

/**
 * Lo público de una referencia de lugar: el nombre de su zona y, si el catálogo lo tiene, su punto (ADR-052 D1).
 *
 * @param point {@code null} si la entrada no tiene coordenadas válidas
 */
public record PublicZone(String zoneName, ZonePoint point) {
}

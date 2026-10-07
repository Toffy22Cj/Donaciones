package com.traceability.core.application.port.out;

import java.util.List;

/** Fondos de una organización, por su génesis en el event store (plan P1.1, DD-31). */
public interface FundDirectoryPort {

    /** Ids de los fondos cuya génesis lleva esa organización, en orden de creación, como mucho {@code limit}. */
    List<String> findFundIdsByOrganization(String organizationRef, int limit);
}

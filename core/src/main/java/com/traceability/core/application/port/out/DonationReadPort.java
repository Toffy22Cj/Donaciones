package com.traceability.core.application.port.out;

import java.util.Optional;

public interface DonationReadPort {
    Optional<DonationReadModel> findByFundId(String fundId);
}

package identity.domain.archfixture;

import identity.application.service.CreateAccountService;

public class ViolatesDomainRule {
    private CreateAccountService service;

    public CreateAccountService getService() {
        return service;
    }
}

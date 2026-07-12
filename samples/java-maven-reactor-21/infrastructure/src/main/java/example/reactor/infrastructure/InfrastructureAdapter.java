package example.reactor.infrastructure;

import example.reactor.application.ApplicationService;

public final class InfrastructureAdapter {
    public ApplicationService service() {
        return new ApplicationService();
    }
}

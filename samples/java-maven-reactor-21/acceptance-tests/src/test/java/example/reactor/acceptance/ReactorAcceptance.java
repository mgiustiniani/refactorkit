package example.reactor.acceptance;

import example.reactor.domain.DomainValue;
import example.reactor.infrastructure.InfrastructureAdapter;
import example.reactor.testsupport.AcceptanceSupport;

public final class ReactorAcceptance {
    Object exercise() {
        return new InfrastructureAdapter().service().apply(new DomainValue(AcceptanceSupport.expected()));
    }
}

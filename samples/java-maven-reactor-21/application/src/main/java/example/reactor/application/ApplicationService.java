package example.reactor.application;

import example.reactor.domain.DomainValue;

public final class ApplicationService {
    public String apply(DomainValue value) {
        return switch (value) {
            case DomainValue(String text) -> text;
        };
    }
}

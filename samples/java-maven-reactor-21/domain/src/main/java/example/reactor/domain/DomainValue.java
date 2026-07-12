package example.reactor.domain;

public record DomainValue(String value) {
    public enum Kind { PRIMARY, SECONDARY }

    public String display() {
        String prefix = """
                value:
                """;
        return switch (value) {
            case "" -> prefix;
            default -> prefix + value;
        };
    }
}

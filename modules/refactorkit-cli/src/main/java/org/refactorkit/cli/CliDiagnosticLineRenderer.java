package org.refactorkit.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;
import org.refactorkit.core.Diagnostic;

/** Deterministic package-private renderer for one CLI refusal diagnostic line. */
final class CliDiagnosticLineRenderer {
    private CliDiagnosticLineRenderer() {
    }

    static List<String> renderSelected(List<Diagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        boolean structuredAuthorityRefusal =
            !diagnostics.isEmpty() && "authorityLease.evidenceDrift".equals(diagnostics.get(0).getCode());
        List<Diagnostic> selected = structuredAuthorityRefusal
            ? Collections.singletonList(diagnostics.get(0))
            : diagnostics;
        List<String> rendered = new ArrayList<>(selected.size());
        selected.forEach(diagnostic -> rendered.add(
            structuredAuthorityRefusal ? render(diagnostic) : renderLegacy(diagnostic)
        ));
        return Collections.unmodifiableList(rendered);
    }

    static String render(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        String code = diagnostic.getCode() == null ? "" : " [" + diagnostic.getCode() + "]";
        Map<String, String> details = new TreeMap<>(diagnostic.getDetails().getFields());
        String renderedDetails = "";
        if (!details.isEmpty()) {
            StringJoiner joiner = new StringJoiner(", ", " (", ")");
            details.forEach((key, value) -> joiner.add(key + "=" + value));
            renderedDetails = joiner.toString();
        }
        return "  " + diagnostic.getSeverity() + code + ": " + diagnostic.getMessage() + renderedDetails;
    }

    private static String renderLegacy(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        return "  " + diagnostic.getSeverity() + ": " + diagnostic.getMessage();
    }
}

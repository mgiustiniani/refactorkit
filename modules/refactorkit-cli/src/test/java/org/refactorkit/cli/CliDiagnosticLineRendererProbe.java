package org.refactorkit.cli;

import java.util.List;
import org.refactorkit.core.Diagnostic;

/** Test-only package peer for executing the non-public production renderer. */
public final class CliDiagnosticLineRendererProbe {
    private CliDiagnosticLineRendererProbe() {
    }

    public static String render(Diagnostic diagnostic) {
        return CliDiagnosticLineRenderer.render(diagnostic);
    }

    public static List<String> renderSelected(List<Diagnostic> diagnostics) {
        return CliDiagnosticLineRenderer.renderSelected(diagnostics);
    }
}

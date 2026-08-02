package org.refactorkit.cli;

import org.refactorkit.core.PatchFaultInjector;
import org.refactorkit.java.JavaMoveClassReviewOnlyGuidance;

/** Non-public immutable construction token used only by the private refusal-CLI factory. */
final class CliPatchFaultConstruction implements JavaMoveClassGuidanceOutputPort {
    private final PatchFaultInjector injector;

    CliPatchFaultConstruction(PatchFaultInjector injector) {
        this.injector = injector;
    }

    PatchFaultInjector value() {
        return injector;
    }

    @Override
    public String render(JavaMoveClassReviewOnlyGuidance guidance) {
        return new JavaMoveClassGuidanceJsonRenderer().render(guidance);
    }
}

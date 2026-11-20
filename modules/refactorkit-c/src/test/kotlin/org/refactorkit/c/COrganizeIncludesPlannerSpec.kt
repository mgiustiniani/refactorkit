package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Path

/** Pure-domain Spec BDD for the bounded C include-removal and local-order rules. */
class COrganizeIncludesPlannerSpec : StringSpec({
    val source = Path.of("src/main.c")
    val header = Path.of("src/api.h")
    listOf("cpp", "objective-c").forEach { languageId ->
        "$languageId classification is refused before producing C include edits" {
            val snapshot = ProjectSnapshot(Workspace(Path.of("/workspace")), emptyList(), listOf(
                SourceFile(source, "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n", languageId),
            ))
            val plan = COrganizeIncludesPlanner().preview(snapshot, source)
            plan.status shouldBe PatchStatus.REFUSED
            plan.refusalCode shouldBe "c.includeOrder.refused"
            plan.summary shouldBe "Organize includes currently supports C sources only"
            plan.workspaceEdit.edits shouldBe emptyList()
            plan.affectedFiles shouldBe emptySet()
        }
    }
    val originalSource = """#include "api.h"
#include "api.h" // duplicate declaration
int main(void) { return c_include_order_value(); }
"""

    "a repeated include is removed only for a captured pragma-once header and keeps the source line" {
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            listOf(
                SourceFile(source, originalSource, "c"),
                SourceFile(header, "#pragma once\nint c_include_order_value(void);\n", "c"),
            ),
        )

        val plan = COrganizeIncludesPlanner().preview(snapshot, source)

        plan.status shouldBe PatchStatus.PREVIEW
        plan.warnings shouldContain "The duplicate include was removed because the captured local header begins with #pragma once."
        val rewritten = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files
            .single { it.path.normalize() == source }.content
        rewritten shouldBe """#include "api.h"
 // duplicate declaration
int main(void) { return c_include_order_value(); }
"""
        rewritten.count { it == '\n' } shouldBe originalSource.count { it == '\n' }
    }

    "removing a repeated pragma-once include leaves other unique include directives intact" {
        val sourceWithOtherInclude = """#include "api.h"
#include <stdio.h>
#include "api.h" // duplicate declaration
int main(void) { return c_include_order_value(); }
"""
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            listOf(
                SourceFile(source, sourceWithOtherInclude, "c"),
                SourceFile(header, "#pragma once\nint c_include_order_value(void);\n", "c"),
            ),
        )

        val plan = COrganizeIncludesPlanner().preview(snapshot, source)

        plan.status shouldBe PatchStatus.PREVIEW
        val rewritten = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files
            .single { it.path.normalize() == source }.content
        rewritten shouldBe """#include "api.h"
#include <stdio.h>
 // duplicate declaration
int main(void) { return c_include_order_value(); }
"""
    }

    "two captured local headers with distinct void prototypes can be reordered as original source lines" {
        val original = """  #include "zeta.h" // Zeta API
#include "alpha.h" /* Alpha API */
int main(void) { zeta_api(); alpha_api(); return 0; }
"""
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            listOf(
                SourceFile(source, original, "c"),
                SourceFile(Path.of("src/zeta.h"), "#pragma once\nvoid zeta_api(void);\n", "c"),
                SourceFile(Path.of("src/alpha.h"), "#pragma once\nvoid alpha_api(void);\n", "c"),
            ),
        )

        val plan = COrganizeIncludesPlanner().preview(snapshot, source)

        plan.status shouldBe PatchStatus.PREVIEW
        val rewritten = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files
            .single { it.path.normalize() == source }.content
        rewritten shouldBe """#include "alpha.h" /* Alpha API */
  #include "zeta.h" // Zeta API
int main(void) { zeta_api(); alpha_api(); return 0; }
"""
    }

    "two captured local headers with recognized built-in scalar prototypes can be reordered" {
        val original = """#include "zeta.h"
#include "alpha.h"
int main(void) { return 0; }
"""
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            listOf(
                SourceFile(source, original, "c"),
                SourceFile(Path.of("src/zeta.h"), "#pragma once\nint zeta_api(void);\n", "c"),
                SourceFile(Path.of("src/alpha.h"), "#pragma once\nlong alpha_api(void);\n", "c"),
            ),
        )

        val plan = COrganizeIncludesPlanner().preview(snapshot, source)

        plan.status shouldBe PatchStatus.PREVIEW
        plan.warnings.single().contains("recognized built-in return-type prototype") shouldBe true
        val rewritten = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files
            .single { it.path.normalize() == source }.content
        rewritten shouldBe """#include "alpha.h"
#include "zeta.h"
int main(void) { return 0; }
"""
    }

    "captured local headers with multiple distinct built-in prototypes can be reordered" {
        val original = """#include "zeta.h"
#include "alpha.h"
int main(void) { return zeta_api() + (int) zeta_aux() + (int) alpha_api() + alpha_aux(); }
"""
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            listOf(
                SourceFile(source, original, "c"),
                SourceFile(Path.of("src/zeta.h"), "#pragma once\nint zeta_api(void);\nlong zeta_aux(void);\n", "c"),
                SourceFile(Path.of("src/alpha.h"), "#pragma once\nlong alpha_api(void);\nint alpha_aux(void);\n", "c"),
            ),
        )

        val plan = COrganizeIncludesPlanner().preview(snapshot, source)

        plan.status shouldBe PatchStatus.PREVIEW
        val rewritten = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files
            .single { it.path.normalize() == source }.content
        rewritten shouldBe """#include "alpha.h"
#include "zeta.h"
int main(void) { return zeta_api() + (int) zeta_aux() + (int) alpha_api() + alpha_aux(); }
"""
    }

    "captured local headers with a duplicate prototype name remain refused without edits" {
        val original = """#include "zeta.h"
#include "alpha.h"
int main(void) { return shared_api(); }
"""
        val originalFiles = mapOf(
            source to original,
            Path.of("src/zeta.h") to "#pragma once\nint shared_api(void);\n",
            Path.of("src/alpha.h") to "#pragma once\nint shared_api(void);\n",
        )
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            originalFiles.map { (path, content) -> SourceFile(path, content, "c") },
        )

        val plan = COrganizeIncludesPlanner().preview(snapshot, source)

        plan.status shouldBe PatchStatus.REFUSED
        plan.summary shouldBe "Quoted includes can be order-sensitive; reordering is refused without proof"
        plan.workspaceEdit.edits.isEmpty() shouldBe true
        WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files
            .associate { it.path.normalize() to it.content } shouldBe originalFiles
    }

    "typedef-dependent local headers return a typed include-order refusal without edits" {
        val original = """#include "zeta.h"
#include "alpha.h"
int main(void) { return 0; }
"""
        val originalFiles = mapOf(
            source to original,
            Path.of("src/zeta.h") to "#pragma once\nrk_value_t zeta_api(void);\n",
            Path.of("src/alpha.h") to "#pragma once\ntypedef int rk_value_t;\n",
        )
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            originalFiles.map { (path, content) -> SourceFile(path, content, "c") },
        )

        val plan = COrganizeIncludesPlanner().preview(snapshot, source)

        plan.status shouldBe PatchStatus.REFUSED
        plan.refusalCode shouldBe "c.includeOrder.refused"
        plan.workspaceEdit.edits.isEmpty() shouldBe true
        WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files
            .associate { it.path.normalize() to it.content } shouldBe originalFiles
    }

    "a blank line inside a standard include block is preserved by refusing the reorder" {
        val original = """#include <stdlib.h>

#include <stdio.h>
int main(void) { return 0; }
"""
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            listOf(SourceFile(source, original, "c")),
        )

        val plan = COrganizeIncludesPlanner().preview(snapshot, source)

        plan.status shouldBe PatchStatus.REFUSED
        plan.summary.contains("blank line") shouldBe true
        plan.workspaceEdit.edits.isEmpty() shouldBe true
        WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files
            .single { it.path.normalize() == source }.content shouldBe original
    }

    "a captured declaration that depends on a typedef from another header is not reordered" {
        val source = Path.of("src/main.c")
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            listOf(
                SourceFile(source, "#include \"zeta.h\"\n#include \"alpha.h\"\n", "c"),
                SourceFile(Path.of("src/zeta.h"), "#pragma once\nrk_value_t zeta_api(void);\n", "c"),
                SourceFile(Path.of("src/alpha.h"), "#pragma once\ntypedef int rk_value_t;\n", "c"),
            ),
        )

        COrganizeIncludesPlanner().preview(snapshot, source).status shouldBe PatchStatus.REFUSED
    }

    "a repeated local header without a leading pragma once remains refused" {
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            listOf(
                SourceFile(source, originalSource, "c"),
                SourceFile(header, "int c_include_order_value(void);\n", "c"),
            ),
        )

        COrganizeIncludesPlanner().preview(snapshot, source).status shouldBe PatchStatus.REFUSED
    }

    "a pragma-once header absent from the snapshot remains refused" {
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            listOf(SourceFile(source, originalSource, "c")),
        )

        COrganizeIncludesPlanner().preview(snapshot, source).status shouldBe PatchStatus.REFUSED
    }

    "an include guard is not inferred to be equivalent to pragma once" {
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            listOf(
                SourceFile(source, originalSource, "c"),
                SourceFile(header, "#ifndef API_H\n#define API_H\nint c_include_order_value(void);\n#endif\n", "c"),
            ),
        )

        COrganizeIncludesPlanner().preview(snapshot, source).status shouldBe PatchStatus.REFUSED
    }

    "multiple repeated targets remain refused even when both headers start with pragma once" {
        val otherHeader = Path.of("src/other.h")
        val sourceWithTwoRepeatedGroups = """#include "api.h"
#include "api.h"
#include "other.h"
#include "other.h"
"""
        val snapshot = ProjectSnapshot(
            Workspace(Path.of("/workspace")),
            emptyList(),
            listOf(
                SourceFile(source, sourceWithTwoRepeatedGroups, "c"),
                SourceFile(header, "#pragma once\n", "c"),
                SourceFile(otherHeader, "#pragma once\n", "c"),
            ),
        )

        val plan = COrganizeIncludesPlanner().preview(snapshot, source)

        plan.status shouldBe PatchStatus.REFUSED
        plan.summary.contains("Only one repeated pair in one include group") shouldBe true
    }
})

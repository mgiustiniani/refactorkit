package org.refactorkit.java

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RiskLevel
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaChangeSignaturePlannerTest {

    private val planner = JavaChangeSignaturePlanner(JavaLanguageAdapter())

    @Test
    fun renamesParameterInDeclarationAndBody() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) {
                        String value = id.trim();
                        return value + id;
                    }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewRenameParameter(snap, "com.example.UserService#findName", "id", "userId")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals(RiskLevel.MEDIUM, plan.riskLevel)
        assertTrue(plan.summary.contains("userId"))

        val applied = PatchEngine(root).apply(plan, snap)
        assertIs<ApplyResult.Applied>(applied)
        val content = root.resolve("src/main/java/com/example/UserService.java").readText()
        assertTrue(content.contains("findName(String userId)"), content)
        assertTrue(content.contains("userId.trim()"), content)
        assertTrue(content.contains("value + userId"), content)
        assertFalse(content.contains("String id"), content)
    }

    @Test
    fun acceptsSignedMethodSelectorForSingleMethodChangeSignature() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) { return id.trim(); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)

        val plan = planner.previewRenameParameter(snap, "com.example.UserService#findName(java.lang.String)", "id", "userId")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.summary.contains("userId"), plan.summary)
    }

    @Test
    fun doesNotRenameStringLiteralOrComment() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) {
                        // id should stay in comment
                        String literal = "id should stay in string";
                        return id + literal;
                    }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewRenameParameter(snap, "com.example.UserService#findName", "id", "userId")
        val applied = PatchEngine(root).apply(plan, snap)
        assertIs<ApplyResult.Applied>(applied)
        val content = root.resolve("src/main/java/com/example/UserService.java").readText()
        assertTrue(content.contains("// id should stay in comment"), content)
        assertTrue(content.contains("\"id should stay in string\""), content)
        assertTrue(content.contains("return userId + literal;"), content)
    }

    @Test
    fun refusesOverloadedMethod() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) { return id; }
                    String findName(Long id) { return id.toString(); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewRenameParameter(snap, "com.example.UserService#findName", "id", "userId")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("overloaded", ignoreCase = true), plan.summary)
    }

    @Test
    fun signedSelectorRenamesOnlyOneJdtBoundOverloadParameter() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    private String value = "field";
                    String findName(String value) { return value.trim() + this.value; }
                    String findName(Long value) { return value.toString(); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)

        val plan = planner.previewRenameParameter(
            snap,
            "com.example.UserService#findName(java.lang.String)",
            "value",
            "input",
        )

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.warnings.any { it.contains("JDT variable identity") }, plan.warnings.toString())
        val content = org.refactorkit.core.TextEdits.apply(
            snap.files.single().content,
            (plan.workspaceEdit.edits.single() as org.refactorkit.core.FileEdit.Modify).textEdits,
        )
        assertTrue(content.contains("findName(String input) { return input.trim() + this.value; }"), content)
        assertTrue(content.contains("findName(Long value) { return value.toString(); }"), content)
        assertTrue(content.contains("private String value"), content)
    }

    @Test
    fun refusesMissingParameter() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) { return id; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewRenameParameter(snap, "com.example.UserService#findName", "missing", "userId")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("missing"), plan.summary)
    }

    @Test
    fun refusesInvalidNewParameterName() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) { return id; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewRenameParameter(snap, "com.example.UserService#findName", "id", "123bad")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesSameParameterName() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) { return id; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewRenameParameter(snap, "com.example.UserService#findName", "id", "id")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun handlesGenericParameterType() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                import java.util.List;
                public class UserService {
                    public void save(List<String> names) {
                        names.clear();
                    }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewRenameParameter(snap, "com.example.UserService#save", "names", "userNames")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val applied = PatchEngine(root).apply(plan, snap)
        assertIs<ApplyResult.Applied>(applied)
        val content = root.resolve("src/main/java/com/example/UserService.java").readText()
        assertTrue(content.contains("List<String> userNames"), content)
        assertTrue(content.contains("userNames.clear()"), content)
    }

    @Test
    fun changesOneJdtBoundParameterTypeWithoutEditingCallArguments() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                class UserService {
                    int size(CharSequence value) { return value.length(); }
                    int run() { return size("abc"); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)

        val plan = planner.previewChangeParameterType(
            snap,
            "com.example.UserService#size(java.lang.CharSequence)",
            "value",
            "String",
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        val modify = plan.workspaceEdit.edits.single() as org.refactorkit.core.FileEdit.Modify
        assertEquals(1, modify.textEdits.size)
        val content = org.refactorkit.core.TextEdits.apply(snap.files.single().content, modify.textEdits)
        assertTrue(content.contains("size(String value)"), content)
        assertTrue(content.contains("return size(\"abc\");"), content)
    }

    @Test
    fun parameterTypeChangeRefusesIncompatibleBodyOrCallSite() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                class UserService {
                    String clean(String value) { return value.trim(); }
                    String run() { return clean("abc"); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)

        val plan = planner.previewChangeParameterType(
            snap,
            "com.example.UserService#clean(java.lang.String)",
            "value",
            "int",
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("introduced"), plan.summary)
    }

    @Test
    fun addsParameterAcrossCompleteJdtOverrideFamilyWithExplicitRiskAcceptance() {
        val root = tempProject(
            "src/main/java/com/example/Lookup.java" to """
                package com.example;
                public interface Lookup { String find(String key); }
            """.trimIndent() + "\n",
            "src/main/java/com/example/DefaultLookup.java" to """
                package com.example;
                public class DefaultLookup implements Lookup {
                    @Override public String find(String value) { return value.trim(); }
                }
            """.trimIndent() + "\n",
            "src/main/java/com/example/Caller.java" to """
                package com.example;
                class Caller {
                    String viaApi(Lookup lookup) { return lookup.find("api"); }
                    String viaImpl(DefaultLookup lookup) { return lookup.find("impl"); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)

        val refused = planner.previewAddParameter(
            snap, "com.example.Lookup#find(java.lang.String)", "int", "limit", "10",
            includeHierarchy = true,
        )
        assertEquals(PatchStatus.REFUSED, refused.status)
        assertTrue(refused.summary.contains("acceptExternalConsumerRisk=true"), refused.summary)

        val plan = planner.previewAddParameter(
            snap, "com.example.Lookup#find(java.lang.String)", "int", "limit", "10",
            includeHierarchy = true, acceptExternalConsumerRisk = true,
        )
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(RiskLevel.HIGH, plan.riskLevel)
        assertEquals(3, plan.affectedFiles.size)
        val applied = PatchEngine(root).apply(plan, snap)
        assertIs<ApplyResult.Applied>(applied)
        assertTrue(root.resolve("src/main/java/com/example/Lookup.java").readText().contains("find(String key, int limit)"))
        assertTrue(root.resolve("src/main/java/com/example/DefaultLookup.java").readText().contains("find(String value, int limit)"))
        val caller = root.resolve("src/main/java/com/example/Caller.java").readText()
        assertTrue(caller.contains("find(\"api\", 10)"), caller)
        assertTrue(caller.contains("find(\"impl\", 10)"), caller)
    }

    @Test
    fun hierarchyAddParameterRefusesExternalSuperDeclaration() {
        val root = tempProject(
            "src/main/java/com/example/Upper.java" to """
                package com.example;
                import java.util.function.UnaryOperator;
                public class Upper implements UnaryOperator<String> {
                    @Override public String apply(String value) { return value.toUpperCase(); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)

        val plan = planner.previewAddParameter(
            snap, "com.example.Upper#apply(java.lang.String)", "int", "limit", "10",
            includeHierarchy = true, acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("outside the editable source workspace"), plan.summary)
    }

    @Test
    fun addParameterUpdatesDeclarationAndInScopeCallSites() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) {
                        return normalize(id);
                    }
                    private String normalize(String id) {
                        return findName(id);
                    }
                }
            """.trimIndent() + "\n",
            "src/main/java/com/example/App.java" to """
                package com.example;
                public class App {
                    public String run() {
                        UserService service = new UserService();
                        return service.findName("42");
                    }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewAddParameter(
            snap,
            "com.example.UserService#findName",
            "boolean",
            "activeOnly",
            "true",
        )

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals("changeSignature.addParameter", plan.operation)
        assertTrue(plan.summary.contains("2 in-scope call site"), plan.summary)

        val applied = PatchEngine(root).apply(plan, snap)
        assertIs<ApplyResult.Applied>(applied)
        val service = root.resolve("src/main/java/com/example/UserService.java").readText()
        val app = root.resolve("src/main/java/com/example/App.java").readText()
        assertTrue(service.contains("findName(String id, boolean activeOnly)"), service)
        assertTrue(service.contains("return findName(id, true);"), service)
        assertTrue(app.contains("service.findName(\"42\", true)"), app)
    }

    @Test
    fun addParameterHandlesNoArgumentCallSites() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName() { return "user"; }
                    String current() { return findName(); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewAddParameter(snap, "com.example.UserService#findName", "String", "fallback", "\"guest\"")
        assertEquals(PatchStatus.PREVIEW, plan.status)

        val applied = PatchEngine(root).apply(plan, snap)
        assertIs<ApplyResult.Applied>(applied)
        val content = root.resolve("src/main/java/com/example/UserService.java").readText()
        assertTrue(content.contains("findName(String fallback)"), content)
        assertTrue(content.contains("return findName(\"guest\");"), content)
    }

    @Test
    fun addParameterDoesNotTouchUnrelatedSamePackageMethodWhenOwnerIsNotReferenced() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) { return id; }
                }
            """.trimIndent() + "\n",
            "src/main/java/com/example/Other.java" to """
                package com.example;
                public class Other {
                    String findName(String id) { return id; }
                    public String run() { return findName("local"); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewAddParameter(snap, "com.example.UserService#findName", "int", "limit", "10")
        assertEquals(PatchStatus.PREVIEW, plan.status)

        val applied = PatchEngine(root).apply(plan, snap)
        assertIs<ApplyResult.Applied>(applied)
        val service = root.resolve("src/main/java/com/example/UserService.java").readText()
        val other = root.resolve("src/main/java/com/example/Other.java").readText()
        assertTrue(service.contains("findName(String id, int limit)"), service)
        assertTrue(other.contains("findName(String id)"), other)
        assertTrue(other.contains("findName(\"local\")"), other)
    }

    @Test
    fun addParameterRefusesUnsafeDefaultExpression() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) { return id; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewAddParameter(snap, "com.example.UserService#findName", "int", "limit", "1, 2")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun addParameterRefusesMethodReferences() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                import java.util.function.Function;
                public class UserService {
                    String findName(String id) { return id; }
                    public Function<String, String> fn() { return this::findName; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewAddParameter(snap, "com.example.UserService#findName", "int", "limit", "10")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("Method reference"), plan.summary)
    }

    @Test
    fun reorderParametersUpdatesDeclarationAndCallSites() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String label(String first, int count, boolean active) {
                        return first + count + active;
                    }
                    String local() { return label("a", 1, true); }
                }
            """.trimIndent() + "\n",
            "src/main/java/com/example/App.java" to """
                package com.example;
                public class App {
                    public String run(UserService service) {
                        return service.label("b", 2, false);
                    }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewReorderParameters(
            snap,
            "com.example.UserService#label",
            listOf("active", "first", "count"),
        )

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals("changeSignature.reorderParameters", plan.operation)

        val applied = PatchEngine(root).apply(plan, snap)
        assertIs<ApplyResult.Applied>(applied)
        val service = root.resolve("src/main/java/com/example/UserService.java").readText()
        val app = root.resolve("src/main/java/com/example/App.java").readText()
        assertTrue(service.contains("label(boolean active, String first, int count)"), service)
        assertTrue(service.contains("return label(true, \"a\", 1);"), service)
        assertTrue(app.contains("service.label(false, \"b\", 2)"), app)
    }

    @Test
    fun reorderParametersRefusesIncompleteOrder() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String label(String first, int count) { return first + count; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewReorderParameters(snap, "com.example.UserService#label", listOf("count"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun reorderParametersRefusesMethodReferences() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                import java.util.function.BiFunction;
                public class UserService {
                    String label(String first, String second) { return first + second; }
                    public BiFunction<String, String, String> fn() { return this::label; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewReorderParameters(snap, "com.example.UserService#label", listOf("second", "first"))
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("Method reference"), plan.summary)
    }

    @Test
    fun addParameterRefusesExistingVarargs() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    void log(String... names) {}
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewAddParameter(snap, "com.example.UserService#log", "boolean", "debug", "false")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("varargs", ignoreCase = true), plan.summary)
    }

    @Test
    fun addParameterRefusesStringLiteralMethodNameRisk() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) { return id; }
                    public String reflectiveName() { return "findName"; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewAddParameter(snap, "com.example.UserService#findName", "int", "limit", "10")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("String literal"), plan.summary)
    }

    @Test
    fun addParameterRefusesGeneratedDeclaration() {
        val root = tempProject(
            "src/main/java/com/example/generated/UserService.java" to """
                package com.example.generated;
                @javax.annotation.Generated("tool")
                public class UserService {
                    String findName(String id) { return id; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewAddParameter(snap, "com.example.generated.UserService#findName", "int", "limit", "10")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("generated", ignoreCase = true), plan.summary)
    }

    @Test
    fun reorderParametersKeepsVarargsLast() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    void log(String prefix, String... names) {}
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewReorderParameters(snap, "com.example.UserService#log", listOf("names", "prefix"))
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("Varargs"), plan.summary)
    }

    @Test
    fun addParameterDoesNotTouchUnqualifiedSameNameMethodInReferencingFile() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id) { return id; }
                }
            """.trimIndent() + "\n",
            "src/main/java/com/example/Other.java" to """
                package com.example;
                public class Other {
                    private UserService service = new UserService();
                    String findName(String id) { return id; }
                    public String run() { return findName("local") + service.findName("target"); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewAddParameter(snap, "com.example.UserService#findName", "int", "limit", "10")
        assertEquals(PatchStatus.PREVIEW, plan.status)

        val applied = PatchEngine(root).apply(plan, snap)
        assertIs<ApplyResult.Applied>(applied)
        val other = root.resolve("src/main/java/com/example/Other.java").readText()
        assertTrue(other.contains("findName(\"local\")"), other)
        assertTrue(other.contains("service.findName(\"target\", 10)"), other)
    }

    @Test
    fun removeParameterUpdatesDeclarationAndCallSitesWhenUnused() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id, boolean unused) { return id; }
                    String local() { return findName("a", true); }
                }
            """.trimIndent() + "\n",
            "src/main/java/com/example/App.java" to """
                package com.example;
                public class App {
                    public String run(UserService service) { return service.findName("b", false); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewRemoveParameter(snap, "com.example.UserService#findName", "unused")
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals("changeSignature.removeParameter", plan.operation)

        val applied = PatchEngine(root).apply(plan, snap)
        assertIs<ApplyResult.Applied>(applied)
        val service = root.resolve("src/main/java/com/example/UserService.java").readText()
        val app = root.resolve("src/main/java/com/example/App.java").readText()
        assertTrue(service.contains("findName(String id)"), service)
        assertTrue(service.contains("return findName(\"a\");"), service)
        assertTrue(app.contains("service.findName(\"b\")"), app)
    }

    @Test
    fun signedSelectorRemovesOnlyOneJdtBoundOverloadParameterAndArguments() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                class UserService {
                    String label(String value, boolean unused) { return value; }
                    String label(int value, boolean keep) { return String.valueOf(value + (keep ? 1 : 0)); }
                    String run() { return label("x", true) + label(1, false); }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)

        val plan = planner.previewRemoveParameter(
            snap,
            "com.example.UserService#label(java.lang.String,boolean)",
            "unused",
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        val content = org.refactorkit.core.TextEdits.apply(
            snap.files.single().content,
            (plan.workspaceEdit.edits.single() as org.refactorkit.core.FileEdit.Modify).textEdits,
        )
        assertTrue(content.contains("label(String value)"), content)
        assertTrue(content.contains("label(\"x\")"), content)
        assertTrue(content.contains("label(int value, boolean keep)"), content)
        assertTrue(content.contains("label(1, false)"), content)
    }

    @Test
    fun removeParameterRefusesWhenParameterIsUsedInBody() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    String findName(String id, boolean active) { return active ? id : ""; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewRemoveParameter(snap, "com.example.UserService#findName", "active")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("used in the method body"), plan.summary)
    }

    @Test
    fun removeParameterRefusesMethodReferences() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                import java.util.function.BiFunction;
                public class UserService {
                    String join(String first, String unused) { return first; }
                    public BiFunction<String, String, String> fn() { return this::join; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewRemoveParameter(snap, "com.example.UserService#join", "unused")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("Method reference"), plan.summary)
    }

    @Test
    fun structuralSignatureChangeRefusesOverrideMethods() {
        val root = tempProject(
            "src/main/java/com/example/Base.java" to """
                package com.example;
                public class Base { String label(String value, boolean unused) { return value; } }
            """.trimIndent() + "\n",
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService extends Base {
                    @Override
                    String label(String value, boolean unused) { return value; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val add = planner.previewAddParameter(snap, "com.example.UserService#label", "int", "limit", "10")
        val remove = planner.previewRemoveParameter(snap, "com.example.UserService#label", "unused")
        assertEquals(PatchStatus.REFUSED, add.status)
        assertEquals(PatchStatus.REFUSED, remove.status)
        assertTrue(add.summary.contains("@Override"), add.summary)
        assertTrue(remove.summary.contains("@Override"), remove.summary)
    }

    @Test
    fun structuralSignatureChangeRefusesInterfaceMethods() {
        val root = tempProject(
            "src/main/java/com/example/UserApi.java" to """
                package com.example;
                public interface UserApi {
                    default String label(String value, boolean unused) { return value; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewReorderParameters(snap, "com.example.UserApi#label", listOf("unused", "value"))
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("interface"), plan.summary)
    }

    @Test
    fun structuralSignatureChangeRefusesPublicMethods() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    public String label(String value, boolean unused) { return value; }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.previewRemoveParameter(snap, "com.example.UserService#label", "unused")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("public method"), plan.summary)
    }

    private fun tempProject(vararg entries: Pair<String, String>): java.nio.file.Path {
        val root = Files.createTempDirectory("change-signature-test")
        for ((rel, content) in entries) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root
    }
}

package org.refactorkit.java

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.SymbolId
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaRenameMemberPlannerTest {

    private val adapter = JavaLanguageAdapter()
    private val planner = JavaRenameMemberPlanner(adapter)

    @Test
    fun previewRenamesMethodAcrossProject() {
        val root = createTempProject(
            "src/main/java/com/example/UserManager.java" to """
                package com.example;
                public class UserManager {
                    public String displayName(String u) { return u; }
                }
            """.trimIndent(),
            "src/main/java/com/example/Client.java" to """
                package com.example;
                public class Client {
                    UserManager m;
                    public String run() { return m.displayName("ada"); }
                }
            """.trimIndent(),
            "src/main/java/com/other/Unrelated.java" to """
                package com.other;
                public class Unrelated {
                    public String displayName() { return "x"; }
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.UserManager#displayName", "getDisplayName")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.summary.contains("getDisplayName"))
        assertTrue(plan.warnings.any { it.contains("Experimental JDT evidence matched") })
        val affected = plan.affectedFiles.map { it.toString() }
        assertTrue(affected.any { it.endsWith("UserManager.java") })
        assertTrue(affected.any { it.endsWith("Client.java") })
        // Unrelated.java is in a different package with no import → not in scope
        assertTrue(affected.none { it.endsWith("Unrelated.java") })
    }

    @Test
    fun signedMemberSelectorRenamesExactOverloadWithJdtEvidence() {
        val root = createTempProject(
            "src/main/java/com/example/Lookup.java" to """
                package com.example;
                public class Lookup {
                    public String find(String key) { return key; }
                    public String find(int id) { return String.valueOf(id); }
                }
            """.trimIndent(),
            "src/main/java/com/example/LookupClient.java" to """
                package com.example;
                public class LookupClient {
                    String run(Lookup lookup) { return lookup.find("ada") + lookup.find(7); }
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.Lookup#find(java.lang.String)", "lookup")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.warnings.any { it.contains("JDT binding selected exact member signature") })

        val result = PatchEngine(root).apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(result)

        val declaration = root.resolve("src/main/java/com/example/Lookup.java").readText()
        val client = root.resolve("src/main/java/com/example/LookupClient.java").readText()
        assertTrue(declaration.contains("String lookup(String key)"), "String overload declaration renamed")
        assertTrue(declaration.contains("String find(int id)"), "int overload declaration remains unchanged")
        assertTrue(client.contains("lookup.lookup(\"ada\")"), "String overload call renamed")
        assertTrue(client.contains("lookup.find(7)"), "int overload call remains unchanged")
    }

    @Test
    fun signedMemberSelectorRenamesStaticImportAndCallWithJdtEvidence() {
        val root = createTempProject(
            "src/main/java/com/example/Text.java" to """
                package com.example;
                public class Text {
                    public static String format(String value) { return value.trim(); }
                }
            """.trimIndent(),
            "src/main/java/com/example/App.java" to """
                package com.example;
                import static com.example.Text.format;
                public class App {
                    String run() { return format(" value "); }
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.Text#format(java.lang.String)", "normalize")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        val result = PatchEngine(root).apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(result)

        val declaration = root.resolve("src/main/java/com/example/Text.java").readText()
        val client = root.resolve("src/main/java/com/example/App.java").readText()
        assertTrue(declaration.contains("String normalize(String value)"), declaration)
        assertTrue(client.contains("import static com.example.Text.normalize;"), client)
        assertTrue(client.contains("return normalize(\" value \");"), client)
    }

    @Test
    fun signedMemberSelectorPropagatesAcrossClassOverrideFamily() {
        val root = createTempProject(
            "src/main/java/com/example/Base.java" to """
                package com.example;
                public class Base {
                    public String find(String key) { return key; }
                }
            """.trimIndent(),
            "src/main/java/com/example/Mid.java" to """
                package com.example;
                public class Mid extends Base {
                    @Override public String find(String key) { return key.toLowerCase(); }
                }
            """.trimIndent(),
            "src/main/java/com/example/Child.java" to """
                package com.example;
                public class Child extends Mid {
                    @Override public String find(String key) { return key.toUpperCase(); }
                }
            """.trimIndent(),
            "src/main/java/com/example/Clients.java" to """
                package com.example;
                class Clients {
                    String base(Base value) { return value.find("base"); }
                    String child(Child value) { return value.find("child"); }
                }
                class Unrelated {
                    String find(String key) { return key; }
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.Base#find(java.lang.String)", "lookup")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.warnings.any { it.contains("Override-aware propagation selected 3 source declarations") })
        val result = PatchEngine(root).apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(result)

        assertTrue(root.resolve("src/main/java/com/example/Base.java").readText().contains("String lookup(String key)"))
        assertTrue(root.resolve("src/main/java/com/example/Mid.java").readText().contains("String lookup(String key)"))
        assertTrue(root.resolve("src/main/java/com/example/Child.java").readText().contains("String lookup(String key)"))
        val clients = root.resolve("src/main/java/com/example/Clients.java").readText()
        assertTrue(clients.contains("value.lookup(\"base\")"), clients)
        assertTrue(clients.contains("value.lookup(\"child\")"), clients)
        assertTrue(clients.contains("String find(String key)"), "unrelated method must remain unchanged: $clients")
    }

    @Test
    fun signedMemberSelectorPropagatesAcrossInterfaceImplementationFamily() {
        val root = createTempProject(
            "src/main/java/com/example/LookupApi.java" to """
                package com.example;
                public interface LookupApi {
                    String find(String key);
                }
            """.trimIndent(),
            "src/main/java/com/example/DefaultLookup.java" to """
                package com.example;
                public class DefaultLookup implements LookupApi {
                    @Override public String find(String key) { return key; }
                }
            """.trimIndent(),
            "src/main/java/com/example/LookupClient.java" to """
                package com.example;
                class LookupClient {
                    String api(LookupApi value) { return value.find("api"); }
                    String implementation(DefaultLookup value) { return value.find("implementation"); }
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.LookupApi#find(java.lang.String)", "lookup")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        val result = PatchEngine(root).apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(result)
        assertTrue(root.resolve("src/main/java/com/example/LookupApi.java").readText().contains("String lookup(String key)"))
        assertTrue(root.resolve("src/main/java/com/example/DefaultLookup.java").readText().contains("String lookup(String key)"))
        val client = root.resolve("src/main/java/com/example/LookupClient.java").readText()
        assertTrue(client.contains("value.lookup(\"api\")"), client)
        assertTrue(client.contains("value.lookup(\"implementation\")"), client)
    }

    @Test
    fun signedMemberSelectorRefusesOverrideFamilyOutsideSourceWorkspace() {
        val root = createTempProject(
            "src/main/java/com/example/Label.java" to """
                package com.example;
                public class Label {
                    @Override
                    public String toString() { return "label"; }
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)

        val plan = planner.preview(snap, "com.example.Label#toString()", "render")

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("outside the scanned source workspace"), plan.summary)
    }

    @Test
    fun signedMemberSelectorRefusesWhenJdtReportsParseWarnings() {
        val root = createTempProject(
            "src/main/java/com/example/Lookup.java" to """
                package com.example;
                public class Lookup {
                    private MissingDependency dependency;
                    public String find(String key) { return key; }
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.Lookup#find(java.lang.String)", "lookup")

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("requires clean JDT semantic evidence"))
    }

    @Test
    fun appliedRenameUpdatesCallSites() {
        val root = createTempProject(
            "src/main/java/com/example/UserManager.java" to """
                package com.example;
                public class UserManager {
                    public String displayName(String u) { return u; }
                }
            """.trimIndent(),
            "src/test/java/com/example/UserManagerTest.java" to """
                package com.example;
                public class UserManagerTest {
                    public void test() {
                        UserManager m = new UserManager();
                        m.displayName("ada");
                    }
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.UserManager#displayName", "getDisplayName")
        assertEquals(PatchStatus.PREVIEW, plan.status)

        val result = PatchEngine(root).apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(result)

        val src = root.resolve("src/main/java/com/example/UserManager.java").readText()
        val test = root.resolve("src/test/java/com/example/UserManagerTest.java").readText()
        assertTrue(src.contains("getDisplayName"), "declaration renamed")
        assertTrue(test.contains("getDisplayName"), "call site renamed")
        assertTrue(!src.contains("displayName"), "old name gone from source")
        assertTrue(!test.contains("displayName"), "old name gone from test")
    }

    @Test
    fun refusedForConstructorRename() {
        val root = createTempProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.Foo#<init>", "NewFoo")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusedForUnknownOwnerType() {
        val root = createTempProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.Bar#doSomething", "doOther")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusedForInvalidTargetName() {
        val root = createTempProject(
            "src/main/java/com/example/Foo.java" to """
                package com.example;
                public class Foo { public void doIt() {} }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.Foo#doIt", "123invalid")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusedForSameName() {
        val root = createTempProject(
            "src/main/java/com/example/Foo.java" to """
                package com.example;
                public class Foo { public void doIt() {} }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.Foo#doIt", "doIt")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun scopingExcludesUnrelatedClassesWithSameMethodName() {
        val root = createTempProject(
            "src/main/java/com/alpha/A.java" to "package com.alpha;\npublic class A { public void go() {} }",
            "src/main/java/com/beta/B.java" to "package com.beta;\npublic class B { public void go() {} }",
        )
        val snap = JavaProjectScanner().scan(root)
        val planA = planner.preview(snap, "com.alpha.A#go", "goNow")

        val affected = planA.affectedFiles.map { it.toString() }
        assertTrue(affected.any { it.endsWith("A.java") })
        assertTrue(affected.none { it.endsWith("B.java") }, "B.java must not be affected by A#go rename")
    }

    @Test
    fun signedRenameUpdatesAnnotationElementDeclarationAndNamedUsages() {
        val root = createTempProject(
            "src/main/java/com/acme/Route.java" to """
                package com.acme;
                public @interface Route { String path(); }
            """.trimIndent(),
            "src/main/java/com/acme/Service.java" to """
                package com.acme;
                @Route(path = "/users")
                public class Service {}
            """.trimIndent(),
            "src/main/java/com/other/Route.java" to """
                package com.other;
                public @interface Route { String path(); }
            """.trimIndent(),
            "src/main/java/com/other/Service.java" to """
                package com.other;
                @Route(path = "/other")
                public class Service {}
            """.trimIndent(),
        )
        val snapshot = JavaProjectScanner().scan(root)
        val member = adapter.findSymbol(snapshot, SymbolId("com.acme.Route#path()"))
        val references = adapter.findReferences(snapshot, SymbolId("com.acme.Route#path()"))
        assertEquals(org.refactorkit.core.Symbol.Kind.METHOD, member?.kind)
        assertEquals(listOf("src/main/java/com/acme/Service.java"), references.map { it.location.path.toString() })

        val plan = planner.preview(snapshot, "com.acme.Route#path()", "value")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.warnings.any { it.contains("JDT declaration/reference ranges") }, plan.warnings.toString())
        assertIs<ApplyResult.Applied>(PatchEngine(root).apply(plan, snapshot.hash))
        val route = root.resolve("src/main/java/com/acme/Route.java").readText()
        val service = root.resolve("src/main/java/com/acme/Service.java").readText()
        assertTrue(route.contains("String value()"), route)
        assertTrue(service.contains("@Route(value = \"/users\")"), service)
        assertTrue(root.resolve("src/main/java/com/other/Route.java").readText().contains("String path()"))
        assertTrue(root.resolve("src/main/java/com/other/Service.java").readText().contains("@Route(path = \"/other\")"))
    }

    private fun createTempProject(vararg entries: Pair<String, String>): java.nio.file.Path {
        val root = Files.createTempDirectory("refactorkit-rename-member-test")
        for ((rel, content) in entries) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root
    }
}

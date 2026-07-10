package org.refactorkit.java

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
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
    fun signedMemberSelectorProducesPreviewWithOverloadEvidence() {
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
        assertTrue(plan.warnings.any { it.contains("Multiple overloads of 'find'") })
        assertTrue(plan.warnings.any { it.contains("find(int)") && it.contains("find(java.lang.String)") })
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

package org.refactorkit.treesitter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericOutlineTest {

    @Test
    fun outlineJavaClass() {
        val code = """
            package com.example;
            public class UserManager {
                public void doSomething() {}
            }
        """.trimIndent()
        val items = GenericOutline.outline(code, "java")
        assertTrue(items.any { it.name == "UserManager" && it.kind == GenericOutline.OutlineItem.Kind.CLASS })
    }

    @Test
    fun outlineJavaInterface() {
        val code = "public interface Saveable {}"
        val items = GenericOutline.outline(code, "java")
        assertTrue(items.any { it.name == "Saveable" && it.kind == GenericOutline.OutlineItem.Kind.INTERFACE })
    }

    @Test
    fun outlineTypescriptClass() {
        val code = """
            export class AuthService {
                async login(user: string): Promise<void> {}
            }
            export function hashPassword(pw: string): string { return pw; }
        """.trimIndent()
        val items = GenericOutline.outline(code, "typescript")
        assertTrue(items.any { it.name == "AuthService" && it.kind == GenericOutline.OutlineItem.Kind.CLASS })
        assertTrue(items.any { it.name == "hashPassword" && it.kind == GenericOutline.OutlineItem.Kind.FUNCTION })
    }

    @Test
    fun outlinePython() {
        val code = """
            class Animal:
                def speak(self):
                    pass

            def helper():
                pass
        """.trimIndent()
        val items = GenericOutline.outline(code, "python")
        assertTrue(items.any { it.name == "Animal" && it.kind == GenericOutline.OutlineItem.Kind.CLASS })
        assertTrue(items.any { it.name == "helper" && it.kind == GenericOutline.OutlineItem.Kind.FUNCTION })
    }

    @Test
    fun outlineRust() {
        val code = """
            pub struct User { name: String }
            pub trait Greet { fn greet(&self); }
            pub fn run() {}
        """.trimIndent()
        val items = GenericOutline.outline(code, "rust")
        assertTrue(items.any { it.name == "User" && it.kind == GenericOutline.OutlineItem.Kind.STRUCT })
        assertTrue(items.any { it.name == "Greet" && it.kind == GenericOutline.OutlineItem.Kind.TRAIT })
        assertTrue(items.any { it.name == "run" && it.kind == GenericOutline.OutlineItem.Kind.FUNCTION })
    }

    @Test
    fun structuralSearchFindsPattern() {
        val code = "UserManager manager = new UserManager();"
        val results = GenericStructuralSearch.search(code, "UserManager", wholeWord = true)
        assertEquals(2, results.size)
    }

    @Test
    fun structuralSearchDoesNotMatchPartial() {
        val code = "class UserManagerImpl {}"
        val results = GenericStructuralSearch.search(code, "UserManager", wholeWord = true)
        assertEquals(0, results.size)
    }

    @Test
    fun localRenameReplacesWholeWord() {
        val code = "UserManager mgr = new UserManager(); // UserManagerImpl"
        val result = GenericStructuralSearch.localRename(code, "UserManager", "AccountManager")
        assertTrue(result.contains("AccountManager mgr = new AccountManager()"))
        assertTrue(result.contains("UserManagerImpl"), "Partial name must NOT be renamed")
    }

    @Test
    fun treeSitterAdapterDelegatesToOutline() {
        val adapter = TreeSitterAdapter()
        val items = adapter.outline("public class Foo {}", "java")
        assertTrue(items.any { it.name == "Foo" })
    }
}

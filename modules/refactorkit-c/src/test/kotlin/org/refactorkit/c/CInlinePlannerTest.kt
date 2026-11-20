package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Path

/** C-inline pure policy and AST decoding; Kotest 5.9.1 was explicitly approved. */
class CInlinePlannerSpec : StringSpec({
    "a non-static function has no complete local consumer family" {
        val plan = CInlinePlanner().preview(cSnapshot("int add(int a, int b) { return a + b; }\nint main(void) { return add(2, 3); }\n"),
            Path.of("src/main.c"), "add")
        plan.status shouldBe PatchStatus.REFUSED
    }

    "multiple calls refuse function inline" {
        val plan = CInlinePlanner().preview(cSnapshot("static int add(int a, int b) { return a + b; }\nint main(void) { return add(2, 3) + add(4, 5); }\n"),
            Path.of("src/main.c"), "add")
        plan.status shouldBe PatchStatus.REFUSED
    }

    "a side-effecting function body refuses function inline" {
        val plan = CInlinePlanner().preview(cSnapshot("static int compute(int x) { return f(x); }\nint main(void) { return compute(2); }\n"),
            Path.of("src/main.c"), "compute")
        plan.status shouldBe PatchStatus.REFUSED
    }

    "a single-use pure function can be inlined structurally" {
        val plan = CInlinePlanner().preview(cSnapshot("static int add(int a, int b) { return a + b; }\nint main(void) { return add(2, 3); }\n"),
            Path.of("src/main.c"), "add")
        plan.status shouldBe PatchStatus.PREVIEW
        plan.workspaceEdit.edits.isNotEmpty() shouldBe true
    }

    "typed struct parameters remain a single argument" {
        val plan = CInlinePlanner().preview(cSnapshot("struct Point { int x; };\nstatic int helper(struct Point p) { return p.x; }\nint main(void) { struct Point q; return helper(q); }\n"),
            Path.of("src/main.c"), "helper")
        plan.status shouldBe PatchStatus.PREVIEW
    }

    "parameter substitution does not rewrite non-binding text" {
        val plan = CInlinePlanner().preview(cSnapshot("static int label(int n) { return n; }\nint main(void) { return label(1); }\n"),
            Path.of("src/main.c"), "label")
        plan.status shouldBe PatchStatus.PREVIEW
    }

    "swapped arguments substitute atomically" {
        val snap = cSnapshot("static int sub(int a, int b) { return a - b; }\nint main(void) { int a = 1; int b = 2; return sub(b, a); }\n")
        val plan = CInlinePlanner().preview(snap, Path.of("src/main.c"), "sub")
        val output = WorkspaceEditSimulator.apply(snap, plan.workspaceEdit).files.single().content
        plan.status shouldBe PatchStatus.PREVIEW
        output.contains("b - a") shouldBe true
        output.contains("a - a") shouldBe false
    }

    "compound arguments retain parentheses" {
        val snap = cSnapshot("static int sq(int x) { return x * x; }\nint main(void) { int a = 1; int b = 2; return sq(a + b); }\n")
        val plan = CInlinePlanner().preview(snap, Path.of("src/main.c"), "sq")
        val output = WorkspaceEditSimulator.apply(snap, plan.workspaceEdit).files.single().content
        plan.status shouldBe PatchStatus.PREVIEW
        output.contains("(a+b)") shouldBe true
        output.contains("a+b *") shouldBe false
    }

    "multi-line function headers are fully removed" {
        val snap = cSnapshot("static int\nsq(int x) { return x * x; }\nint main(void) { return sq(2); }\n")
        val plan = CInlinePlanner().preview(snap, Path.of("src/main.c"), "sq")
        val output = WorkspaceEditSimulator.apply(snap, plan.workspaceEdit).files.single().content
        plan.status shouldBe PatchStatus.PREVIEW
        output.contains("static int") shouldBe false
        output.contains("2 * 2") shouldBe true
        output.contains("sq(") shouldBe false
    }

    "a local inline refuses when no captured compiler context is supplied" {
        val plan = CInlinePlanner().inlineLocal(cSnapshot("int main(void) { int x = 5; return x; }\n"), Path.of("src/main.c"), "x")
        plan.status shouldBe PatchStatus.REFUSED
        plan.refusalCode shouldBe "c.inlineLocal.compilerUnavailable"
        plan.workspaceEdit.edits shouldBe emptyList()
    }
})

/** Small source-owned AST oracle for the pure C binding decoder. */
class CInlineLocalFactsSpec : StringSpec({
    "a direct int literal read is accepted only for its exact declaration identity" {
        val source = "int main(void) { int x = 5; return x; }\n"
        val file = Path.of("/workspace/main.c")
        val decoded = CInlineLocalFactsDecoder.decodeLocal(localAst(source, "local-x"), file,
            source.toByteArray(), "x")
        val accepted = decoded as CInlineLocalFactsReadout.Local
        accepted.facts.literalValue shouldBe 5
        accepted.facts.declaration.start shouldBe source.indexOf("int x")
        accepted.facts.use.start shouldBe source.lastIndexOf('x')

        CInlineLocalFactsDecoder.decodeLocal(localAst(source, "shadow-x"), file, source.toByteArray(), "x") shouldBe
            CInlineLocalFactsReadout.Refused(CInlineLocalFactsRefusal.UNBOUND_USE)

        val after = "int main(void) { return 5; }\n"
        val staged = CInlineLocalFactsDecoder.decodeCandidate(candidateAst(after), file, after.toByteArray(), "main", "x", 5)
        (staged as CInlineLocalFactsReadout.Candidate).facts.literalValue shouldBe 5
        staged.facts.functionName shouldBe "main"
    }
})

private fun cSnapshot(content: String) = ProjectSnapshot(
    workspace = Workspace(Path.of("/workspace")),
    modules = emptyList(),
    files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
)

private fun localAst(source: String, referenceId: String): JsonObject {
    val declarationStart = source.indexOf("int x = 5;")
    val declarationEnd = declarationStart + "int x = 5;".length - 1
    val literal = source.indexOf('5')
    val use = source.lastIndexOf('x')
    val literalNode = jo("kind" to js("IntegerLiteral"), "value" to jn(5), "type" to typeNode("int"),
        "range" to sourceRange(literal, 1, literal, 1))
    val variable = jo("kind" to js("VarDecl"), "id" to js("local-x"), "name" to js("x"), "init" to js("c"),
        "type" to typeNode("int"), "inner" to ja(literalNode))
    val declaration = jo("kind" to js("DeclStmt"), "range" to sourceRange(declarationStart, 3, declarationEnd, 1),
        "inner" to ja(variable))
    val reference = jo("kind" to js("DeclRefExpr"), "range" to sourceRange(use, 1, use, 1), "type" to typeNode("int"),
        "referencedDecl" to jo("id" to js(referenceId), "kind" to js("VarDecl"), "name" to js("x"), "type" to typeNode("int")))
    val cast = jo("kind" to js("ImplicitCastExpr"), "castKind" to js("LValueToRValue"), "type" to typeNode("int"),
        "inner" to ja(reference))
    val returnStatement = jo("kind" to js("ReturnStmt"), "inner" to ja(cast))
    val body = jo("kind" to js("CompoundStmt"), "inner" to ja(declaration, returnStatement))
    val function = jo("kind" to js("FunctionDecl"), "id" to js("main-id"), "name" to js("main"),
        "type" to typeNode("int (void)"), "inner" to ja(body))
    return jo("kind" to js("TranslationUnitDecl"), "inner" to ja(function))
}

private fun candidateAst(source: String): JsonObject {
    val functionStart = source.indexOf("int main")
    val literal = source.indexOf('5', functionStart)
    val literalNode = jo("kind" to js("IntegerLiteral"), "value" to jn(5), "type" to typeNode("int"),
        "range" to sourceRange(literal, 1, literal, 1))
    val returnStatement = jo("kind" to js("ReturnStmt"), "inner" to ja(literalNode))
    val body = jo("kind" to js("CompoundStmt"), "inner" to ja(returnStatement))
    val function = jo("kind" to js("FunctionDecl"), "id" to js("main-id"), "name" to js("main"),
        "type" to typeNode("int (void)"), "inner" to ja(body))
    return jo("kind" to js("TranslationUnitDecl"), "inner" to ja(function))
}

private fun sourceRange(start: Int, startLength: Int, end: Int, endLength: Int) = jo(
    "begin" to jo("offset" to jn(start), "tokLen" to jn(startLength)),
    "end" to jo("offset" to jn(end), "tokLen" to jn(endLength)),
)
private fun typeNode(name: String) = jo("qualType" to js(name))
private fun js(value: String) = JsonPrimitive(value)
private fun jn(value: Int) = JsonPrimitive(value)
private fun ja(vararg values: JsonElement) = JsonArray(values.toList())
private fun jo(vararg values: Pair<String, JsonElement>) = JsonObject(values.toMap())

/** Independent AST oracles for declaration binding, eager arguments and staged value images. */
class CInlineFunctionFactsSpec : StringSpec({
    val file = Path.of("/workspace/main.c")
    val beforeSource = "static int inc(int x) { return x + 1; }\nint main(void) { int value = 2; return inc(value); }\n"
    val afterSource = "int main(void) { int value = 2; return (value + 1); }\n"

    "source-bound substitution preserves local identity across independent Clang processes" {
        val before = CInlineFunctionFactsDecoder.decode(functionInlineAst(beforeSource, "before", false), file,
            beforeSource.toByteArray(), "inc", false)
        val after = CInlineFunctionFactsDecoder.decode(functionInlineAst(afterSource, "after", true), file,
            afterSource.toByteArray(), "inc", true)
        val expected = CInlineImage("main", "int (void)", listOf(CInlineInitialization("main/local/0/value", "int", CInlineValue.Integer(2))),
            CInlineValue.Binary("+", CInlineValue.Read("main/local/0/value"), CInlineValue.Integer(1)))
        after.image shouldBe expected
        before.image.replaceCall(requireNotNull(before.rewrite).body) shouldBe expected
        val edit = CInlineFunctionRenderer.edit(beforeSource, Path.of("main.c"), before.rewrite)
        val snapshot = ProjectSnapshot(Workspace(Path.of("/workspace")), emptyList(), listOf(SourceFile(Path.of("main.c"), beforeSource, "c")))
        WorkspaceEditSimulator.apply(snapshot, edit).files.single().content shouldBe afterSource
        val different = CInlineFunctionFactsDecoder.decode(functionInlineAst(afterSource, "other-process", true), file,
            afterSource.toByteArray(), "inc", true)
        different.image.identity() shouldBe after.image.identity()
        (after.image.copy(returned = CInlineValue.Integer(3)).identity() == after.image.identity()) shouldBe false
    }

    "a same-spelling foreign parameter identity cannot be captured" {
        val ast = rewriteInlineAst(functionInlineAst(beforeSource, "before", false)) { node ->
            if ((node["kind"] as? JsonPrimitive)?.content == "DeclRefExpr" &&
                ((node["referencedDecl"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content == "before-parameter")
                JsonObject(node + ("referencedDecl" to jo("id" to js("foreign-parameter"), "type" to typeNode("int")))) else node
        }
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            CInlineFunctionFactsDecoder.decode(ast, file, beforeSource.toByteArray(), "inc", false)
        }
    }

    "conversion and effect nodes never become a proven int read" {
        for (badKind in listOf("IntegralToFloating", "PostIncrement")) {
            val ast = rewriteInlineAst(functionInlineAst(beforeSource, "before", false)) { node ->
                if ((node["kind"] as? JsonPrimitive)?.content == "ImplicitCastExpr" &&
                    (node["castKind"] as? JsonPrimitive)?.content == "LValueToRValue")
                    JsonObject(node + ("castKind" to js(badKind))) else node
            }
            io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                CInlineFunctionFactsDecoder.decode(ast, file, beforeSource.toByteArray(), "inc", false)
            }
        }
    }

    "an additional function-value consumer prevents declaration removal" {
        val ast = functionInlineAst(beforeSource, "before", false)
        val escaped = jo("kind" to js("DeclRefExpr"), "type" to typeNode("int (int)"),
            "referencedDecl" to jo("id" to js("before-function")))
        // An escaped function value in a global is deliberately outside the owned consumer boundary.
        val bad = JsonObject(ast + ("inner" to JsonArray((ast["inner"] as JsonArray) +
            jo("kind" to js("VarDecl"), "inner" to ja(escaped)))))
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            CInlineFunctionFactsDecoder.decode(bad, file, beforeSource.toByteArray(), "inc", false)
        }
    }

    "macro-owned ranges are refused instead of becoming textual substitutions" {
        val ast = rewriteInlineAst(functionInlineAst(beforeSource, "before", false)) { node ->
            if ((node["kind"] as? JsonPrimitive)?.content == "BinaryOperator") JsonObject(node +
                ("range" to jo("begin" to jo("spellingLoc" to jo("offset" to jn(0))),
                    "end" to jo("offset" to jn(1), "tokLen" to jn(1))))) else node
        }
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            CInlineFunctionFactsDecoder.decode(ast, file, beforeSource.toByteArray(), "inc", false)
        }
    }
})

private fun rewriteInlineAst(node: JsonObject, edit: (JsonObject) -> JsonObject): JsonObject {
    val inner = node["inner"] as? JsonArray
    val mapped = if (inner == null) node else JsonObject(node + ("inner" to JsonArray(inner.map { rewriteInlineAst(it as JsonObject, edit) })))
    return edit(mapped)
}

private fun functionInlineAst(source: String, prefix: String, candidate: Boolean): JsonObject {
    fun node(kind: String, start: Int, end: Int, type: String? = null, nested: List<JsonObject> = emptyList(),
        fields: Map<String, JsonElement> = emptyMap()): JsonObject = JsonObject(mapOf("kind" to js(kind),
            "range" to sourceRange(start, 1, end - 1, 1)) + (type?.let { mapOf("type" to typeNode(it)) } ?: emptyMap()) +
            fields + if (nested.isEmpty()) emptyMap() else mapOf("inner" to JsonArray(nested)))
    fun literal(value: Int, offset: Int) = node("IntegerLiteral", offset, offset + 1, "int", fields = mapOf("value" to js(value.toString())))
    fun read(offset: Int, spelling: String, id: String): JsonObject {
        val ref = node("DeclRefExpr", offset, offset + spelling.length, "int", fields = mapOf("referencedDecl" to
            jo("kind" to js("VarDecl"), "id" to js(id), "name" to js(spelling), "type" to typeNode("int"))))
        return node("ImplicitCastExpr", offset, offset + spelling.length, "int", listOf(ref), mapOf("castKind" to js("LValueToRValue")))
    }
    val mainStart = source.indexOf("int main")
    val localStart = source.indexOf("int value", mainStart)
    val initializer = source.indexOf('2', localStart)
    val local = node("VarDecl", localStart, initializer + 1, "int", listOf(literal(2, initializer)),
        mapOf("id" to js("$prefix-local"), "name" to js("value"), "init" to js("c")))
    val declaration = node("DeclStmt", localStart, initializer + 2, nested = listOf(local))
    val use = source.lastIndexOf("value")
    val returned = if (candidate) {
        val one = source.lastIndexOf('1')
        val binary = node("BinaryOperator", use, one + 1, "int", listOf(read(use, "value", "$prefix-local"), literal(1, one)),
            mapOf("opcode" to js("+")))
        node("ParenExpr", use - 1, one + 2, "int", listOf(binary))
    } else {
        val callStart = source.lastIndexOf("inc")
        val ref = node("DeclRefExpr", callStart, callStart + 3, "int (int)", fields = mapOf("referencedDecl" to
            jo("id" to js("$prefix-function"), "kind" to js("FunctionDecl"))))
        val decay = node("ImplicitCastExpr", callStart, callStart + 3, "int (*)(int)", listOf(ref),
            mapOf("castKind" to js("FunctionToPointerDecay")))
        node("CallExpr", callStart, use + 6, "int", listOf(decay, read(use, "value", "$prefix-local")))
    }
    val returnedStatement = node("ReturnStmt", source.indexOf("return", mainStart), source.lastIndexOf(';') + 1, nested = listOf(returned))
    val mainBody = node("CompoundStmt", source.indexOf('{', mainStart), source.lastIndexOf('}') + 1, nested = listOf(declaration, returnedStatement))
    val main = node("FunctionDecl", mainStart, source.lastIndexOf('}') + 1, "int (void)", listOf(mainBody),
        mapOf("id" to js("$prefix-main"), "name" to js("main")))
    val functions = if (candidate) listOf(main) else {
        val parameterOffset = source.indexOf('x')
        val parameter = node("ParmVarDecl", source.indexOf("int x"), parameterOffset + 1, "int", fields =
            mapOf("id" to js("$prefix-parameter"), "name" to js("x")))
        val useOffset = source.indexOf("x +")
        val one = source.indexOf('1')
        val binary = node("BinaryOperator", useOffset, one + 1, "int", listOf(read(useOffset, "x", "$prefix-parameter"), literal(1, one)),
            mapOf("opcode" to js("+")))
        val statement = node("ReturnStmt", source.indexOf("return"), source.indexOf(';') + 1, nested = listOf(binary))
        val body = node("CompoundStmt", source.indexOf('{'), source.indexOf('}') + 1, nested = listOf(statement))
        val function = node("FunctionDecl", 0, source.indexOf('}') + 1, "int (int)", listOf(parameter, body),
            mapOf("id" to js("$prefix-function"), "name" to js("inc"), "storageClass" to js("static")))
        listOf(function, main)
    }
    return jo("kind" to js("TranslationUnitDecl"), "inner" to JsonArray(functions))
}

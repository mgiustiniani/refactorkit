package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.*
import kotlinx.serialization.json.*
import org.refactorkit.core.*
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaEditorMutationPreviewSteps {
    private lateinit var owned: Path
    private lateinit var root: Path
    private lateinit var sdk: Path
    private var session: DaemonSession? = null
    private lateinit var saved: Map<String, String>
    private lateinit var dirty: Map<String, String>
    private lateinit var hash: String
    private lateinit var context: JsonObject
    private var result: JsonObject? = null
    private var refusal: JsonRpcException? = null
    private lateinit var operation: String
    private var cursorSelection: JsonObject? = null
    private var cursorTarget: String? = null
    private var editorRequestedMode: String? = null
    private val runner="src/main/java/pkg/Runner.java"
    private val caller="src/main/java/pkg/Consumer.java"

    @Given("closed dirty Java mutation buffers with explicit SDK authority")
    fun fixture() {
        owned=Files.createTempDirectory("rk-editor-preview-");root=owned.resolve("project");sdk=owned.resolve("sdk")
        val originalSdk=Path.of("/usr/lib/jvm/java-21-openjdk");Files.createDirectories(sdk.resolve("lib"));Files.copy(originalSdk.resolve("release"), sdk.resolve("release"))
        listOf("ct.sym","modules","jrt-fs.jar").forEach { Files.createSymbolicLink(sdk.resolve("lib/$it"), originalSdk.resolve("lib/$it")) }
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties></project>")
        write(runner, "package pkg;\npublic class Runner { public String call(String value) { return value; } public int call(int value) { return value; } }\n")
        write(caller, "package pkg;\nclass Consumer { Runner runner = new Runner(); String text = runner.call(\"saved\"); int number = runner.call(2); }\n")
        saved=sources();dirty=mapOf(runner to saved.getValue(runner).replace("public int call", "public String overlayMarker(){return \"dirty\";} public int call"),caller to saved.getValue(caller).replace("\"saved\"","\"unsaved\""))
        session=DaemonSession();hash=rpc("project.open", buildJsonObject { put("root", root.toString()) }).getValue("snapshotHash").jsonPrimitive.content
        val c=rpc("project.context", buildJsonObject { put("expectedSnapshotHash", hash);put("sdkHome", sdk.toString()) });assertEquals("available", c.getValue("status").jsonPrimitive.content)
        context=buildJsonObject { put("sdkHome", sdk.toString());put("expectedContextHash", c.getValue("contextHash")) }
    }
    @When("editor preview requests {string} after {string}")
    fun preview(requested: String, event: String) {
        operation=requested
        if (event == "older document version") rpc("intelligence.query", buildJsonObject { put("requestId", "editor-version");put("kind", "documentSymbols");put("languageId", "java");put("path", runner);put("expectedSnapshotHash", hash);put("javaContext", context);put("sourceAuthority", sourceAuthority(31)) })
        if (event == "SDK drift") Files.writeString(sdk.resolve("release"), Files.readString(sdk.resolve("release")) + "\nEDITOR_SDK_DRIFT=true\n")
        try { result=rpc("refactor.editor.preview", buildJsonObject {
            put("languageId", "java");put("operation", requested);put("expectedSnapshotHash", hash);put("javaContext", context)
            put("sourceAuthority", sourceAuthority(30,event == "missing affected document"))
            editorRequestedMode?.let { put("editorMutationMode",it) }
            if (requested !in setOf("formatFile","organizeImports")) put("symbol", if (requested == "renameClass") "pkg.Runner" else "pkg.Runner#call(java.lang.String)")
            put("arguments", buildJsonObject { if (requested in setOf("formatFile","organizeImports")) put("file", runner) else put("newName", if (requested == "renameClass") "Worker" else "invoke") })
        }) } catch (failure: JsonRpcException) { refusal=failure }
    }
    @Then("editor preview reports {string} with exact versioned edit authority")
    fun outcome(expected: String) {
        if (expected != "preview") { assertNotNull(refusal);assertEquals(expected, refusal!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content);return }
        assertNull(refusal, refusal?.message);val p=assertNotNull(result)
        assertEquals(expected, p.getValue("status").jsonPrimitive.content);assertEquals("java-editor-refactoring-v1", p.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(hash,p.getValue("baseSnapshotHash").jsonPrimitive.content);assertEquals(context.getValue("expectedContextHash"),p.getValue("contextHash"))
        assertEquals("editor",p.getValue("editOwnership").jsonPrimitive.content);assertEquals(false,p.getValue("applyAvailable").jsonPrimitive.boolean)
        assertTrue(p.getValue("planHash").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
        val receipt=p.getValue("sourceAuthority").jsonObject
        assertEquals(hash,receipt.getValue("baseSnapshotHash").jsonPrimitive.content)
        assertEquals(2,receipt.getValue("documents").jsonArray.size)
        val wire=p.getValue("workspaceEdit").jsonObject.getValue("documentChanges").jsonArray
        val working=dirty.toMutableMap()
        wire.forEach { item -> val change=item.jsonObject
            if (change["kind"]?.jsonPrimitive?.content == "rename") {
                val from=relative(change.getValue("oldUri").jsonPrimitive.content);val to=relative(change.getValue("newUri").jsonPrimitive.content)
                working[to]=assertNotNull(working.remove(from));assertFalse(change.getValue("options").jsonObject.getValue("overwrite").jsonPrimitive.boolean)
            } else {
                val document=change.getValue("textDocument").jsonObject;val path=relative(document.getValue("uri").jsonPrimitive.content)
                assertEquals(if (path == runner) 30L else 12L,document.getValue("version").jsonPrimitive.long)
                val edits=change.getValue("edits").jsonArray.map { e -> val v=e.jsonObject;val r=v.getValue("range").jsonObject
                    fun point(name:String):SourcePosition { val x=r.getValue(name).jsonObject;return SourcePosition(x.getValue("line").jsonPrimitive.int,x.getValue("character").jsonPrimitive.int) }
                    TextEdit(SourceRange(point("start"),point("end")),v.getValue("newText").jsonPrimitive.content)
                }
                working[path]=TextEdits.apply(working.getValue(path),edits)
            }
        }
        when(operation) {
            "renameClass" -> { assertFalse(runner in working);assertTrue(working.getValue("src/main/java/pkg/Worker.java").contains("overlayMarker"));assertTrue(working.getValue(caller).contains("Worker runner")) }
            "renameMember" -> { assertTrue(working.getValue(caller).contains("runner.invoke(\"unsaved\")"));assertTrue(working.getValue(caller).contains("runner.call(2)"));assertTrue(working.getValue(runner).contains("int call(int")) }
            "formatFile" -> { assertNotEquals(dirty.getValue(runner),working.getValue(runner));assertTrue(working.getValue(runner).contains("overlayMarker")) }
        }
        val base=org.refactorkit.java.JavaProjectScanner().scan(root)
        val post=base.copy(files=base.files.map { file ->
            val path=if(operation=="renameClass"&&file.path.toString()==runner)Path.of("src/main/java/pkg/Worker.java") else file.path
            file.copy(path=path,content=working[path.toString()] ?: file.content)
        })
        assertEquals(p.getValue("expectedProviderSnapshotHash").jsonPrimitive.content,post.hash)
        assertFalse(p.getValue("diffTruncated").jsonPrimitive.boolean);assertTrue(p.getValue("renderedDiff").jsonPrimitive.content.isNotBlank())
    }
    @Then("editor preview preserves saved source bytes and refuses ordinary managed apply")
    fun bytes() {
        assertEquals(saved,sources());assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        result?.let { p ->
            assertFailsWith<JsonRpcException> {
                rpc("refactor.apply", buildJsonObject { put("planId",p.getValue("planId"));put("javaContext",context);put("expectedSnapshotHash",hash) })
            }
        }
        assertEquals(saved,sources())
    }
    @When("dirty cursor {string} passes its selector to preview after {string}")
    fun cursor(target:String,event:String) {
        cursorTarget=target
        if(target=="dirty field") dirty=dirty + mapOf(runner to dirty.getValue(runner).replace("public class Runner {","public class Runner { public int draftCount;"),caller to dirty.getValue(caller).replace("class Consumer {","class Consumer { int draftTotal = new Runner().draftCount;"))
        val path=if(target=="dirty method")runner else caller
        val text=dirty.getValue(path);val token=when(target){"dirty method"->"overlayMarker";"dirty field"->"draftCount";else->"Runner"}
        val line=text.lines().indexOfFirst { token in it };val column=text.lines()[line].indexOf(token)+1
        val q=rpc("intelligence.query",buildJsonObject { put("requestId","dirty-cursor");put("kind","mutationSelection");put("languageId","java");put("path",path);put("position",buildJsonObject { put("line",line);put("character",column) });put("expectedSnapshotHash",hash);put("javaContext",context);put("sourceAuthority",sourceAuthority(30)) })
        if(q.getValue("status").jsonPrimitive.content!="ready") { refusal=JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED,"Dirty cursor refused",buildJsonObject { put("refusalCode",q.getValue("error").jsonObject.getValue("code")) });return }
        var claim=q.getValue("selection").jsonObject;cursorSelection=claim
        if(event=="altered binding") {
            val binding=claim.getValue("selection").jsonObject
            claim=JsonObject(claim+ ("selection" to JsonObject(binding+("bindingKey" to JsonPrimitive("altered-binding")))))
        }
        if(event=="same version changed") dirty=dirty+(runner to (dirty.getValue(runner)+"\n// changed buffer\n"))
        operation=if(target=="class")"renameClass" else "renameMember"
        try { result=rpc("refactor.editor.preview",buildJsonObject {
            put("languageId","java");put("operation",operation);put("expectedSnapshotHash",hash);put("javaContext",context);put("javaEditorSelection",claim)
            put("sourceAuthority",sourceAuthority(if(event=="new document version")31 else 30))
            put("arguments",buildJsonObject { put("newName",if(target=="class")"Worker" else "draftRenamed") })
        }) } catch(failure:JsonRpcException){refusal=failure}
    }
    @Then("dirty cursor preview reports {string} with current compiler and version identity")
    fun cursorOutcome(expected:String) {
        if(expected!="preview") { assertNotNull(refusal);assertEquals(expected,refusal!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content);return }
        assertNull(refusal,refusal?.message);val claim=assertNotNull(cursorSelection);val p=assertNotNull(result)
        assertEquals("java-editor-mutation-selection-v1",claim.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(hash,claim.getValue("baseSnapshotHash").jsonPrimitive.content)
        assertEquals(claim.getValue("overlayHash"),p.getValue("sourceAuthority").jsonObject.getValue("overlayHash"))
        assertEquals(claim.getValue("selectorHash"),p.getValue("selectionHash"))
        val selected=claim.getValue("selection").jsonObject
        assertEquals(when(cursorTarget){"dirty method"->"pkg.Runner#overlayMarker()";"dirty field"->"pkg.Runner#draftCount";else->"pkg.Runner"},selected.getValue("symbolId").jsonPrimitive.content)
        assertEquals(context.getValue("expectedContextHash"),selected.getValue("contextHash"))
        assertEquals(p.getValue("providerSnapshotHash"),selected.getValue("snapshotHash"))
        assertEquals("preview",p.getValue("status").jsonPrimitive.content)
        val edits=p.getValue("workspaceEdit").jsonObject.getValue("documentChanges").jsonArray
        assertTrue(edits.any { "textDocument" in it.jsonObject });assertTrue(p.getValue("renderedDiff").jsonPrimitive.content.contains(if(cursorTarget=="class")"Worker" else "draftRenamed"))
    }
    private var editorTransaction: JsonObject? = null
    private var editorBuffers: MutableMap<String, Pair<Long, String>>? = null
    private var editorOriginalBuffers: Map<String, String>? = null
    @When("the editor prepares {string} and confirms its own edits after {string}")
    fun transaction(requested:String,event:String) {
        preview(requested,"none");assertNull(refusal,refusal?.message);val p=assertNotNull(result)
        val buffers=dirty.mapValues { (path,content)-> (if(path==runner)30L else 12L) to content }.toMutableMap()
        editorBuffers=buffers;editorOriginalBuffers=dirty.toMap()
        if(event=="advanced buffer")buffers[runner]=31L to buffers.getValue(runner).second
        if(event=="SDK drift")Files.writeString(sdk.resolve("release"),Files.readString(sdk.resolve("release"))+"\nEDITOR_TRANSACTION_DRIFT=true\n")
        fun parameters(id:JsonElement?=null)=buildJsonObject {
            put("languageId","java");put("planId",p.getValue("planId"));put("approvedPlanHash",if(event=="altered approval")JsonPrimitive("0".repeat(64)) else p.getValue("planHash"))
            put("expectedSnapshotHash",org.refactorkit.java.JavaProjectScanner().scan(root).hash);put("javaContext",context)
            id?.let { put("transactionId",it) };put("sourceAuthority",editorSource(buffers))
        }
        try {
            val prepared=rpc("refactor.editor.prepare",parameters());assertEquals("prepared",prepared.getValue("status").jsonPrimitive.content)
            assertEquals("editor",prepared.getValue("editOwnership").jsonPrimitive.content)
            applyEditorChanges(prepared.getValue("workspaceEdit").jsonObject,buffers)
            if(event=="external post edit")buffers[caller]= (buffers.getValue(caller).first+1) to (buffers.getValue(caller).second+"\n// external change\n")
            val confirmed=rpc("refactor.editor.confirm",parameters(prepared.getValue("transactionId")))
            editorTransaction=confirmed
            if(event=="external post edit")return
            assertEquals("applied",confirmed.getValue("status").jsonPrimitive.content)
            val undo=rpc("refactor.editor.undo.prepare",parameters(prepared.getValue("transactionId")))
            assertEquals("undo-prepared",undo.getValue("status").jsonPrimitive.content)
            applyEditorChanges(undo.getValue("workspaceEdit").jsonObject,buffers)
            editorTransaction=rpc("refactor.editor.undo.confirm",parameters(prepared.getValue("transactionId")))
        } catch(failure:JsonRpcException) { refusal=failure }
    }
    @Then("the editor transaction reports {string} and preserves guarded buffer ownership")
    fun transactionOutcome(expected:String) {
        if(expected.startsWith("java.")){assertNotNull(refusal);assertEquals(expected,refusal!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content);return}
        assertNull(refusal,refusal?.message);val t=assertNotNull(editorTransaction)
        assertEquals("java-editor-transaction-v1",t.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(expected,t.getValue("status").jsonPrimitive.content)
        assertEquals("editor",t.getValue("editOwnership").jsonPrimitive.content)
        assertEquals(context.getValue("expectedContextHash"),t.getValue("contextHash"))
        if(expected=="undone")assertEquals(editorOriginalBuffers,assertNotNull(editorBuffers).mapValues {it.value.second})
        else assertTrue("workspaceEdit" !in t,"Unknown external buffer edits must not be overwritten")
    }
    private fun editorSource(buffers:Map<String,Pair<Long,String>>)=buildJsonObject {
        put("kind","immutable-editor-overlay");put("documents",buildJsonArray {buffers.forEach { (path,value)->add(buildJsonObject {put("path",path);put("version",value.first);put("content",value.second)})}})
    }
    private fun applyEditorChanges(edit:JsonObject,buffers:MutableMap<String,Pair<Long,String>>) {
        edit.getValue("documentChanges").jsonArray.forEach { value->val c=value.jsonObject
            if(c["kind"]?.jsonPrimitive?.content=="rename"){
                val old=relative(c.getValue("oldUri").jsonPrimitive.content);val new=relative(c.getValue("newUri").jsonPrimitive.content)
                val content=assertNotNull(buffers.remove(old)).second;assertTrue(new !in buffers);buffers[new]=1L to content
                Files.move(root.resolve(old),root.resolve(new))
            } else {
                val doc=c.getValue("textDocument").jsonObject;val path=relative(doc.getValue("uri").jsonPrimitive.content)
                val current=buffers.getValue(path);assertEquals(current.first,doc.getValue("version").jsonPrimitive.long)
                val edits=c.getValue("edits").jsonArray.map { element->val item=element.jsonObject;val range=item.getValue("range").jsonObject
                    fun point(key:String)=range.getValue(key).jsonObject.let {SourcePosition(it.getValue("line").jsonPrimitive.int,it.getValue("character").jsonPrimitive.int)}
                    TextEdit(SourceRange(point("start"),point("end")),item.getValue("newText").jsonPrimitive.content)
                }
                buffers[path]=(current.first+1) to TextEdits.apply(current.second,edits)
            }
        }
    }
    @When("the daemon restarts with an editor transaction after {string}")
    fun restartRecovery(event:String) {
        val member=event in setOf("unknown external content","corrupt journal")
        preview(if(member)"renameMember" else "renameClass","none");assertNull(refusal,refusal?.message);val p=assertNotNull(result)
        val buffers=dirty.mapValues { (path,content)-> (if(path==runner)30L else 12L) to content }.toMutableMap()
        editorBuffers=buffers;editorOriginalBuffers=dirty.toMap()
        fun parameters(tx:JsonElement?=null)=buildJsonObject {
            put("languageId","java");put("planId",p.getValue("planId"));put("approvedPlanHash",p.getValue("planHash"))
            put("expectedSnapshotHash",org.refactorkit.java.JavaProjectScanner().scan(root).hash);put("javaContext",context)
            tx?.let {put("transactionId",it)};put("sourceAuthority",editorSource(buffers))
        }
        val prepared=rpc("refactor.editor.prepare",parameters());val tx=prepared.getValue("transactionId")
        val journal=root.resolve(".refactorkit/editor-transactions").resolve(tx.jsonPrimitive.content+".json")
        assertTrue(Files.isRegularFile(journal),"Editor preparation requires a durable receipt before returning an edit")
        if(event in setOf("prepared full application","confirmed application","unknown external content"))applyEditorChanges(prepared.getValue("workspaceEdit").jsonObject,buffers)
        if(event=="partial application"){
            val first=prepared.getValue("workspaceEdit").jsonObject.getValue("documentChanges").jsonArray.first { "textDocument" in it.jsonObject }
            applyEditorChanges(buildJsonObject {put("documentChanges",buildJsonArray {add(first)})},buffers)
        }
        if(event=="confirmed application")assertEquals("applied",rpc("refactor.editor.confirm",parameters(tx)).getValue("status").jsonPrimitive.content)
        if(event=="unknown external content")buffers[caller]=(buffers.getValue(caller).first+1) to (buffers.getValue(caller).second+"\n// unknown change\n")
        session!!.close();session=DaemonSession()
        if(event=="corrupt journal")Files.writeString(journal,Files.readString(journal).replaceFirst(p.getValue("planHash").jsonPrimitive.content,"0".repeat(64)))
        try {
            rpc("project.open",buildJsonObject {put("root",root.toString())})
            val listed=rpc("refactor.editor.transactions",buildJsonObject {})
            assertTrue(listed.getValue("transactions").jsonArray.any {it.jsonObject.getValue("transactionId")==tx})
            val recovered=if(event=="confirmed application")rpc("refactor.editor.undo.prepare",parameters(tx)) else rpc("refactor.editor.recover",parameters(tx))
            editorTransaction=recovered
            if("workspaceEdit" in recovered){
                assertEquals("undo-prepared",recovered.getValue("status").jsonPrimitive.content)
                applyEditorChanges(recovered.getValue("workspaceEdit").jsonObject,buffers)
                editorTransaction=rpc("refactor.editor.undo.confirm",parameters(tx))
            }
        } catch(failure:JsonRpcException){refusal=failure}
    }
    @Then("durable editor recovery reports {string} without overwriting unknown changes")
    fun recoveryOutcome(expected:String) {
        if(expected.startsWith("java.")){assertNotNull(refusal);assertEquals(expected,refusal!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content);return}
        assertNull(refusal,refusal?.message);val result=assertNotNull(editorTransaction)
        assertEquals(expected,result.getValue("status").jsonPrimitive.content)
        assertTrue(result.getValue("durableRecoveryAvailable").jsonPrimitive.boolean)
        if(expected in setOf("undone","aborted"))assertEquals(editorOriginalBuffers,assertNotNull(editorBuffers).mapValues {it.value.second})
        else assertTrue("workspaceEdit" !in result)
    }
    @When("an editor transaction encounters {string} at its guarded boundary")
    fun authorityFault(event:String) {
        preview(if(event=="restart after inverse")"renameClass" else "renameMember","none");assertNull(refusal,refusal?.message);val p=assertNotNull(result)
        val buffers=dirty.mapValues { (path,content)-> (if(path==runner)30L else 12L) to content }.toMutableMap()
        editorBuffers=buffers;editorOriginalBuffers=dirty.toMap()
        fun parameters(tx:JsonElement?=null)=buildJsonObject {
            put("languageId","java");put("planId",p.getValue("planId"));put("approvedPlanHash",p.getValue("planHash"))
            put("expectedSnapshotHash",org.refactorkit.java.JavaProjectScanner().scan(root).hash);put("javaContext",context)
            tx?.let {put("transactionId",it)};put("sourceAuthority",editorSource(buffers))
        }
        val prepared=rpc("refactor.editor.prepare",parameters());val tx=prepared.getValue("transactionId")
        applyEditorChanges(prepared.getValue("workspaceEdit").jsonObject,buffers)
        if(event=="SDK after edit")Files.writeString(sdk.resolve("release"),Files.readString(sdk.resolve("release"))+"\nEDITOR_LATE_SDK_DRIFT=true\n")
        if(event=="build inputs after edit")write("pom.xml",saved.getValue("pom.xml")+"\n<!-- late build change -->\n")
        if(event=="classpath rebuild after edit"){val output=root.resolve("target/classes/Changed.class");Files.createDirectories(output.parent);Files.write(output,byteArrayOf(1,2,3))}
        if(event=="journal after prepare"){
            val journal=root.resolve(".refactorkit/editor-transactions").resolve(tx.jsonPrimitive.content+".json")
            Files.writeString(journal,Files.readString(journal).replaceFirst(p.getValue("planHash").jsonPrimitive.content,"0".repeat(64)))
        }
        try {
            val confirmed=rpc("refactor.editor.confirm",parameters(tx));editorTransaction=confirmed
            if(event !in setOf("buffer before undo","restart after inverse"))return
            assertEquals("applied",confirmed.getValue("status").jsonPrimitive.content)
            if(event=="buffer before undo")buffers[caller]=(buffers.getValue(caller).first+1) to (buffers.getValue(caller).second+"\n// late edit\n")
            val inverse=rpc("refactor.editor.undo.prepare",parameters(tx))
            applyEditorChanges(inverse.getValue("workspaceEdit").jsonObject,buffers)
            session!!.close();session=DaemonSession();rpc("project.open",buildJsonObject {put("root",root.toString())})
            editorTransaction=rpc("refactor.editor.undo.confirm",parameters(tx))
        }catch(failure:JsonRpcException){refusal=failure}
        finally {
            if(event=="build inputs after edit"){assertTrue(Files.readString(root.resolve("pom.xml")).contains("late build change"));write("pom.xml",saved.getValue("pom.xml"))}
        }
    }
    @Then("the guarded transaction reports {string} with {string} and never overwrites external state")
    fun authorityFaultOutcome(expected:String,code:String) {
        if(expected=="refused"){assertNotNull(refusal);assertEquals(code,refusal!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content);return}
        assertNull(refusal,refusal?.message);val observed=assertNotNull(editorTransaction)
        assertEquals(expected,observed.getValue("status").jsonPrimitive.content)
        if(code!="none")assertEquals(code,observed.getValue("refusalCode").jsonPrimitive.content)
        if(expected=="undone")assertEquals(editorOriginalBuffers,assertNotNull(editorBuffers).mapValues {it.value.second})
        else assertTrue("workspaceEdit" !in observed)
    }
    @When("the durable editor contract executes {string} after {string}")
    fun qualifiedOperation(requested:String,event:String) {
        if(requested=="organizeImports")dirty=dirty+(runner to dirty.getValue(runner).replace("package pkg;","package pkg;\nimport java.util.List;\n"))
        if(event=="unsupported"){preview(requested,"none");return}
        transaction(requested,event)
    }
    @Then("qualified editor operation reports {string} and restores its original dirty content")
    fun qualifiedOperationOutcome(expected:String) {
        transactionOutcome(expected)
        result?.let { preview->assertTrue(preview.getValue("planHash").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}"))) }
    }
    @When("the editor requests {string} in {string} admission mode")
    fun admission(requested:String,mode:String) {
        editorRequestedMode=mode.takeUnless {it=="default"}
        if(requested=="organizeImports")dirty=dirty+(runner to dirty.getValue(runner).replace("package pkg;","package pkg;\nimport java.util.List;\n"))
        if(mode=="durable-editor-owned")transaction(requested,"complete roundtrip") else preview(requested,"none")
    }
    @Then("editor mutation admission reports {string} with the qualified operation catalogue")
    fun admissionOutcome(expected:String) {
        val caps=rpc("server.capabilities",buildJsonObject {}).getValue("javaContextRefactoring").jsonObject
        assertTrue(caps.getValue("dirtyEditorMutationAuthority").jsonPrimitive.boolean)
        assertEquals(setOf("renameClass","renameMember","formatFile","organizeImports"),caps.getValue("editorMutationOperations").jsonArray.map {it.jsonPrimitive.content}.toSet())
        assertTrue(caps.getValue("durableEditorRecovery").jsonPrimitive.boolean)
        if(expected=="invalid"){assertNotNull(refusal);assertEquals(JsonRpcErrorCodes.INVALID_PARAMS,refusal!!.code);return}
        assertNull(refusal,refusal?.message);val p=assertNotNull(result)
        assertEquals(expected=="available",p.getValue("applyAvailable").jsonPrimitive.boolean)
        if(expected=="available"){
            assertEquals("durable-editor-owned",p.getValue("editorMutationMode").jsonPrimitive.content)
            assertEquals("undone",assertNotNull(editorTransaction).getValue("status").jsonPrimitive.content)
        } else assertTrue("editorMutationMode" !in p)
    }
    private var editorDiscovery: JsonObject? = null
    private var editorDiscoveryId: JsonElement? = null
    private var editorRestartDrafts: Map<String, String> = emptyMap()
    @When("the IDE loses its captured inventory after {string} reaches {string}")
    fun discoverOriginalDocuments(requested:String,state:String) {
        val clean="src/main/java/pkg/Unrelated.java"
        write(clean,"package pkg;\nclass Unrelated { String text = \"clean untouched tab\"; }\n")
        saved=sources();dirty=dirty+(clean to saved.getValue(clean))
        if(requested=="organizeImports")dirty=dirty+(runner to dirty.getValue(runner).replace("package pkg;","package pkg;\nimport java.util.List;\n"))
        hash=rpc("workspace.refresh",buildJsonObject {}).getValue("snapshotHash").jsonPrimitive.content
        val acquired=rpc("project.context",buildJsonObject {put("expectedSnapshotHash",hash);put("sdkHome",sdk.toString())})
        assertEquals("available",acquired.getValue("status").jsonPrimitive.content)
        context=buildJsonObject {put("sdkHome",sdk.toString());put("expectedContextHash",acquired.getValue("contextHash"))}
        editorRequestedMode="durable-editor-owned";preview(requested,"none");assertNull(refusal,refusal?.message)
        val p=assertNotNull(result);val buffers=dirty.mapValues {(path,content)->(if(path==runner)30L else 12L) to content}.toMutableMap()
        editorOriginalBuffers=dirty.toMap();editorBuffers=buffers
        fun parameters(tx:JsonElement?=null)=buildJsonObject {
            put("languageId","java");put("planId",p.getValue("planId"));put("approvedPlanHash",p.getValue("planHash"))
            put("expectedSnapshotHash",org.refactorkit.java.JavaProjectScanner().scan(root).hash);put("javaContext",context)
            tx?.let {put("transactionId",it)};put("sourceAuthority",editorSource(buffers))
        }
        val prepared=rpc("refactor.editor.prepare",parameters());val tx=prepared.getValue("transactionId");editorDiscoveryId=tx
        val edit=prepared.getValue("workspaceEdit").jsonObject
        val changedPaths=edit.getValue("documentChanges").jsonArray.mapNotNull {it.jsonObject["textDocument"]?.jsonObject?.get("uri")?.jsonPrimitive?.content?.let(::relative)}
        assertFalse(clean in changedPaths,"The clean captured tab must be unaffected by the approved edit")
        if(state=="partial")applyEditorChanges(buildJsonObject {put("documentChanges",buildJsonArray {add(edit.getValue("documentChanges").jsonArray.first {"textDocument" in it.jsonObject})})},buffers)
        else if(state in setOf("applied","confirmed"))applyEditorChanges(edit,buffers)
        if(state=="confirmed")assertEquals("applied",rpc("refactor.editor.confirm",parameters(tx)).getValue("status").jsonPrimitive.content)
        editorRestartDrafts=buffers.filter {(path,value)->value.second!=Files.readString(root.resolve(path))}.mapValues {it.value.second}
        assertFalse(clean in editorRestartDrafts,"Clean tabs are absent from the IDE dirty checkpoint")
        buffers.clear();session!!.close();session=DaemonSession()
        rpc("project.open",buildJsonObject {put("root",root.toString())})
        val listed=rpc("refactor.editor.transactions",buildJsonObject {})
        assertEquals("java-editor-transactions-v1",listed.getValue("schemaVersion").jsonPrimitive.content)
        editorDiscovery=listed.getValue("transactions").jsonArray.single {it.jsonObject.getValue("transactionId")==tx}.jsonObject
    }
    @Then("discovery reconstructs all original documents including clean tabs and permits {string}")
    fun recoverDiscoveredDocuments(expected:String) {
        val discovered=assertNotNull(editorDiscovery);val inventory=discovered.getValue("documents").jsonArray.map {it.jsonObject}
        val original=assertNotNull(editorOriginalBuffers)
        assertEquals(original.keys.sorted(),inventory.map {it.getValue("originalPath").jsonPrimitive.content})
        fun sha(text:String)=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
        val buffers=assertNotNull(editorBuffers)
        inventory.forEachIndexed {index,document->
            assertEquals(setOf("originalPath","finalPath","originalSavedContentSha256"),document.keys)
            val from=document.getValue("originalPath").jsonPrimitive.content;val to=document.getValue("finalPath").jsonPrimitive.content
            assertEquals(if(operation=="renameClass"&&from==runner)"src/main/java/pkg/Worker.java" else from,to)
            assertEquals(sha(saved.getValue(from)),document.getValue("originalSavedContentSha256").jsonPrimitive.content)
            if(from==runner)assertNotEquals(sha(original.getValue(from)),document.getValue("originalSavedContentSha256").jsonPrimitive.content)
            val current=if(Files.isRegularFile(root.resolve(from)))from else to
            val savedNow=Files.readString(root.resolve(current));assertEquals(sha(savedNow),document.getValue("originalSavedContentSha256").jsonPrimitive.content)
            buffers[current]=(100L+index) to (editorRestartDrafts[current]?:savedNow)
        }
        assertTrue("src/main/java/pkg/Unrelated.java" in buffers)
        val p=assertNotNull(result);assertEquals(p.getValue("planHash"),discovered.getValue("approvedPlanHash"))
        assertEquals(context.getValue("expectedContextHash"),discovered.getValue("contextHash"))
        fun parameters()=buildJsonObject {
            put("languageId","java");put("transactionId",assertNotNull(editorDiscoveryId));put("approvedPlanHash",discovered.getValue("approvedPlanHash"))
            put("expectedSnapshotHash",org.refactorkit.java.JavaProjectScanner().scan(root).hash);put("javaContext",context);put("sourceAuthority",editorSource(buffers))
        }
        val recovered=rpc("refactor.editor.recover",parameters());editorTransaction=recovered
        if(expected=="undone"){
            assertEquals("undo-prepared",recovered.getValue("status").jsonPrimitive.content)
            applyEditorChanges(recovered.getValue("workspaceEdit").jsonObject,buffers)
            editorTransaction=rpc("refactor.editor.undo.confirm",parameters())
        }
        assertEquals(expected,assertNotNull(editorTransaction).getValue("status").jsonPrimitive.content)
        assertEquals(original,buffers.mapValues {it.value.second})
    }
    private fun sourceAuthority(version:Long,missing:Boolean=false)=buildJsonObject { put("kind","immutable-editor-overlay");put("documents",buildJsonArray { dirty.filterKeys { !missing || it==runner }.forEach { (path,content) -> add(buildJsonObject { put("path",path);put("version",if(path==runner)version else 12);put("content",content) }) } }) }
    private fun relative(uri:String)=root.relativize(Path.of(java.net.URI(uri))).toString()
    private fun rpc(method:String,params:JsonObject)=session!!.dispatch(method,params).jsonObject
    private fun write(relative:String,content:String) { val p=root.resolve(relative);Files.createDirectories(p.parent);Files.writeString(p,content) }
    private fun sources()=Files.walk(root).use { p -> p.filter { Files.isRegularFile(it)&&(it.toString().endsWith(".java")||it.fileName.toString()=="pom.xml") }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }
    @After fun close() { session?.close();if(::owned.isInitialized)Files.walk(owned).use { p->p.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}

from pathlib import Path
import importlib.util, tempfile, shutil, os, subprocess, json
repo=Path('/tmp/refactorkit-k5-review-285da48-pre')
spec=importlib.util.spec_from_file_location('shared', repo/'scripts/smoke-packaged-kotlin.py'); shared=importlib.util.module_from_spec(spec); spec.loader.exec_module(shared)
runtime=repo/'modules/refactorkit-cli/build/package/refactorkit'
cli=runtime/'bin/refactorkit'; jdk=Path('/usr/lib/jvm/java-21-openjdk')
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
compiler=shared.artifact(cache,'org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21')
cp=[shared.artifact(cache,'org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),shared.artifact(cache,'org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),shared.artifact(cache,'org.jetbrains.kotlin','kotlin-reflect','1.6.10'),shared.artifact(cache,'org.jetbrains.kotlin','kotlin-daemon-embeddable','2.0.21'),shared.artifact(cache,'org.jetbrains.intellij.deps','trove4j','1.0.20200330'),shared.artifact(cache,'org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),shared.artifact(cache,'org.jetbrains','annotations','13.0')]

def workspace(td):
    w=Path(td)/'w'; shutil.copytree(repo/'samples/kotlin-maven-simple',w); shutil.rmtree(w/'src'); return w

def cli_run(w,op,extra):
    p=subprocess.run(shared.command_for(cli,['kotlin',op,str(w),'--jdk-home',str(jdk),'--compiler-jar',str(compiler),'--compiler-classpath',os.pathsep.join(map(str,cp)),'--request-id','audit',*extra]),text=True,capture_output=True,timeout=300)
    try: body=json.loads(p.stdout)
    except: body={'stdout':p.stdout,'stderr':p.stderr}
    print('CLI',op,'rc',p.returncode,'status',body.get('status'),'refusal',body.get('refusalCode'),'diagAfter',len(body.get('diagnosticsAfterPreview',[])))
    if p.returncode: print('stderr',p.stderr); print('body',body)
    return p.returncode,body

def run_program(w,main='probe.MainKt'):
    out=w/'audit-out'; shutil.rmtree(out,ignore_errors=True); out.mkdir()
    sources=[str(x) for x in w.rglob('*.kt')]
    cmd=[str(jdk/'bin/java'),'-cp',os.pathsep.join(map(str,[compiler,*cp])),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-reflect','-no-stdlib','-classpath',str(cp[0]),'-jvm-target','21','-d',str(out),*sources]
    p=subprocess.run(cmd,text=True,capture_output=True,timeout=300)
    if p.returncode: raise RuntimeError('compile '+p.stdout+p.stderr)
    p=subprocess.run([str(jdk/'bin/java'),'-cp',os.pathsep.join([str(out),str(cp[0])]),main],text=True,capture_output=True,timeout=60)
    if p.returncode: raise RuntimeError('run '+p.stdout+p.stderr)
    return p.stdout

print('=== organize external static field ===')
with tempfile.TemporaryDirectory(prefix='audit-org-') as td:
    w=workspace(td); f=w/'src/main/kotlin/probe/Main.kt'; f.parent.mkdir(parents=True)
    f.write_text('''package probe\nimport java.lang.Integer.MAX_VALUE\nimport java.lang.Long.*\nfun value(): String = "${MAX_VALUE::class.qualifiedName}:$MAX_VALUE"\nfun main() { print(value()) }\n''')
    before=run_program(w)
    rc,b=cli_run(w,'organize-imports',['--file','src/main/kotlin/probe/Main.kt','--apply'])
    after=run_program(w)
    print('runtime',repr(before),'->',repr(after)); print(f.read_text())

print('=== parameter capture of external static field ===')
with tempfile.TemporaryDirectory(prefix='audit-param-') as td:
    w=workspace(td); f=w/'src/main/kotlin/probe/Main.kt'; f.parent.mkdir(parents=True)
    f.write_text('''package probe
import java.lang.Integer.MAX_VALUE
private fun value(old: Int): Long {
    val first = run {
        val MAX_VALUE = 7
        old.toLong()
    }
    return first + MAX_VALUE
}
fun main() { print(value(5)) }
''')
    _,symbody=cli_run(w,'symbols',['--file','src/main/kotlin/probe/Main.kt'])
    sym=next(x for x in symbody['symbols'] if x['name']=='value' and x['kind']=='function')
    before=run_program(w)
    rc,b=cli_run(w,'change-signature',['--symbol',sym['id'],'--old-name','old','--new-name','MAX_VALUE','--apply'])
    after=run_program(w)
    print('apply diagnostics', b.get('diagnostics'))
    print('runtime',repr(before),'->',repr(after)); print(f.read_text())

print('=== extract collides with imported Java static callable ===')
with tempfile.TemporaryDirectory(prefix='audit-extract-') as td:
    w=workspace(td); f=w/'src/main/kotlin/probe/Main.kt'; f.parent.mkdir(parents=True)
    f.write_text('''package probe\nimport java.util.concurrent.ForkJoinPool.getCommonPoolParallelism\nfun answer(): Int = 40 + 2\nfun imported(): Int = getCommonPoolParallelism()\nfun main() { print("${answer()},${imported()}") }\n''')
    before=run_program(w)
    rc,b=cli_run(w,'extract-method',['--file','src/main/kotlin/probe/Main.kt','--start-line','3','--end-line','3','--method-name','getCommonPoolParallelism','--apply'])
    after=run_program(w)
    print('runtime',repr(before),'->',repr(after)); print(f.read_text())

print('=== generated extract and inline previews ===')
with tempfile.TemporaryDirectory(prefix='audit-generated-') as td:
    w=workspace(td); f=w/'target/generated-sources/kotlin/probe/Generated.kt'; f.parent.mkdir(parents=True)
    f.write_text('''package probe\nfun answer(): Int = 40 + 2\nprivate fun tiny(): Int = 20 + 22\nfun use(): Int = tiny()\n''')
    rc,b=cli_run(w,'extract-method',['--file','target/generated-sources/kotlin/probe/Generated.kt','--start-line','2','--end-line','2','--method-name','fortyTwo','--apply'])
    _,symbody=cli_run(w,'symbols',['--file','target/generated-sources/kotlin/probe/Generated.kt'])
    sym=next(x for x in symbody['symbols'] if x['name']=='tiny' and x['kind']=='function')
    rc,b=cli_run(w,'inline-method',['--symbol',sym['id']])

from pathlib import Path
import importlib.util,tempfile,shutil,os,subprocess,json
repo=Path('/tmp/refactorkit-k5-review-ce65175-pre')
spec=importlib.util.spec_from_file_location('shared',repo/'scripts/smoke-packaged-kotlin.py'); s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
runtime=repo/'modules/refactorkit-cli/build/package/refactorkit';cli=runtime/'bin/refactorkit';jdk=Path('/usr/lib/jvm/java-21-openjdk');cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
comp=s.artifact(cache,'org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21');cp=[s.artifact(cache,'org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-reflect','1.6.10'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-daemon-embeddable','2.0.21'),s.artifact(cache,'org.jetbrains.intellij.deps','trove4j','1.0.20200330'),s.artifact(cache,'org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),s.artifact(cache,'org.jetbrains','annotations','13.0')]
def run_program(w):
 out=w/'probe-out';shutil.rmtree(out,ignore_errors=True);out.mkdir()
 p=subprocess.run([str(jdk/'bin/java'),'-cp',os.pathsep.join(map(str,[comp,*cp])),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-reflect','-no-stdlib','-classpath',str(cp[0]),'-jvm-target','21','-d',str(out),*[str(x) for x in w.rglob('*.kt') if 'probe-out' not in x.parts]],text=True,capture_output=True,timeout=500)
 assert p.returncode==0,(p.stdout,p.stderr)
 p=subprocess.run([str(jdk/'bin/java'),'-cp',os.pathsep.join([str(out),str(cp[0])]),'probe.MainKt'],text=True,capture_output=True,timeout=60)
 assert p.returncode==0,(p.stdout,p.stderr);return p.stdout
with tempfile.TemporaryDirectory(prefix='rpk-enum-') as td:
 w=Path(td)/'w';shutil.copytree(repo/'samples/kotlin-maven-simple',w);shutil.rmtree(w/'src');f=w/'src/main/kotlin/probe/Main.kt';f.parent.mkdir(parents=True)
 f.write_text('''package probe
import java.util.concurrent.TimeUnit.SECONDS
import java.time.temporal.ChronoUnit.*
fun value(): String = SECONDS::class.qualifiedName!!
fun main() { print(value()) }
''')
 before=run_program(w)
 p=subprocess.run(s.command_for(cli,['kotlin','organize-imports',str(w),'--jdk-home',str(jdk),'--compiler-jar',str(comp),'--compiler-classpath',os.pathsep.join(map(str,cp)),'--request-id','enum','--file','src/main/kotlin/probe/Main.kt','--apply']),text=True,capture_output=True,timeout=600)
 body=json.loads(p.stdout)
 after=run_program(w)
 print(json.dumps({'returncode':p.returncode,'status':body.get('status'),'diagnostics':body.get('diagnostics'),'before':before,'after':after,'source':f.read_text()},indent=2))

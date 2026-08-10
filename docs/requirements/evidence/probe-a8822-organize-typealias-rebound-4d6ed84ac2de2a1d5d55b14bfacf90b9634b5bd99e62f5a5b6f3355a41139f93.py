from pathlib import Path
import importlib.util,tempfile,shutil,os,subprocess,json
repo=Path('/tmp/refactorkit-a8822-audit');spec=importlib.util.spec_from_file_location('shared',repo/'scripts/smoke-packaged-kotlin.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
cli=repo/'modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit';jdk=Path('/usr/lib/jvm/java-21-openjdk');cache=Path.home()/'.gradle/caches/modules-2/files-2.1';comp=s.artifact(cache,'org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21');cp=[s.artifact(cache,'org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-reflect','1.6.10'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-daemon-embeddable','2.0.21'),s.artifact(cache,'org.jetbrains.intellij.deps','trove4j','1.0.20200330'),s.artifact(cache,'org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),s.artifact(cache,'org.jetbrains','annotations','13.0')]
def run_program(w):
 out=w/'probe-out';shutil.rmtree(out,ignore_errors=True);out.mkdir(); src=[str(x) for x in w.rglob('*.kt') if 'probe-out' not in x.parts]
 p=subprocess.run([str(jdk/'bin/java'),'-cp',os.pathsep.join(map(str,[comp,*cp])),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-reflect','-no-stdlib','-classpath',str(cp[0]),'-jvm-target','21','-d',str(out),*src],text=True,capture_output=True,timeout=500);assert p.returncode==0,(p.stdout,p.stderr)
 p=subprocess.run([str(jdk/'bin/java'),'-cp',os.pathsep.join([str(out),str(cp[0])]),'consumer.ConsumerKt'],text=True,capture_output=True,timeout=60);assert p.returncode==0,(p.stdout,p.stderr);return p.stdout
with tempfile.TemporaryDirectory(prefix='rpk-typealias-rebound-') as td:
 w=Path(td)/'w';shutil.copytree(repo/'samples/kotlin-maven-simple',w);shutil.rmtree(w/'src')
 a=w/'src/main/kotlin/lib/Alias.kt';b=w/'src/main/kotlin/shadow/Alias.kt';c=w/'src/main/kotlin/consumer/Consumer.kt'
 for x in (a,b,c):x.parent.mkdir(parents=True,exist_ok=True)
 a.write_text('package source.lib\ntypealias Chosen = java.util.concurrent.TimeUnit\n')
 b.write_text('package other.shadow\ntypealias Chosen = java.time.temporal.ChronoUnit\n')
 c.write_text('package consumer\nimport source.lib.Chosen\nimport other.shadow.*\nfun value(): String = Chosen::class.qualifiedName!!\nfun main() { print(value()) }\n')
 before=run_program(w); source_before=c.read_bytes(); txroot=w/'.refactorkit/transactions'; txbefore=list(txroot.rglob('*')) if txroot.exists() else []
 p=subprocess.run(s.command_for(cli,['kotlin','organize-imports',str(w),'--jdk-home',str(jdk),'--compiler-jar',str(comp),'--compiler-classpath',os.pathsep.join(map(str,cp)),'--request-id','typealias-rebound','--file','src/main/kotlin/consumer/Consumer.kt','--apply']),text=True,capture_output=True,timeout=800)
 try: body=json.loads(p.stdout)
 except: body={'stdout':p.stdout}
 after=run_program(w); txafter=[x.relative_to(w).as_posix() for x in txroot.rglob('*') if x.is_file()] if txroot.exists() else []
 print(json.dumps({'returncode':p.returncode,'status':body.get('status'),'refusal':body.get('refusalCode'),'diagnostics':body.get('diagnostics'),'before_identity':before,'after_identity':after,'mutated':c.read_bytes()!=source_before,'source':c.read_text(),'transaction_files':txafter,'stderr':p.stderr},indent=2))

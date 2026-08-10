from pathlib import Path
import importlib.util,tempfile,shutil,os,subprocess,json
repo=Path('/tmp/refactorkit-k5-review-ce65175-pre')
spec=importlib.util.spec_from_file_location('shared',repo/'scripts/smoke-packaged-kotlin.py'); s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
runtime=repo/'modules/refactorkit-cli/build/package/refactorkit';cli=runtime/'bin/refactorkit';jdk=Path('/usr/lib/jvm/java-21-openjdk');cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
comp=s.artifact(cache,'org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21');cp=[s.artifact(cache,'org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-reflect','1.6.10'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-daemon-embeddable','2.0.21'),s.artifact(cache,'org.jetbrains.intellij.deps','trove4j','1.0.20200330'),s.artifact(cache,'org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),s.artifact(cache,'org.jetbrains','annotations','13.0')]
def cli_call(w,op,extra):
 p=subprocess.run(s.command_for(cli,['kotlin',op,str(w),'--jdk-home',str(jdk),'--compiler-jar',str(comp),'--compiler-classpath',os.pathsep.join(map(str,cp)),'--request-id','callref',*extra]),text=True,capture_output=True,timeout=800)
 try:b=json.loads(p.stdout)
 except:b={'raw':p.stdout,'stderr':p.stderr}
 return p,b
def run_program(w):
 out=w/'probe-out';shutil.rmtree(out,ignore_errors=True);out.mkdir()
 src=[str(x) for x in w.rglob('*.kt') if 'probe-out' not in x.parts]
 p=subprocess.run([str(jdk/'bin/java'),'-cp',os.pathsep.join(map(str,[comp,*cp])),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-reflect','-no-stdlib','-classpath',str(cp[0]),'-jvm-target','21','-d',str(out),*src],text=True,capture_output=True,timeout=500);assert p.returncode==0,(p.stdout,p.stderr)
 p=subprocess.run([str(jdk/'bin/java'),'-cp',os.pathsep.join([str(out),str(cp[0])]),'consumer.ConsumerKt'],text=True,capture_output=True,timeout=60);assert p.returncode==0,(p.stdout,p.stderr);return p.stdout
with tempfile.TemporaryDirectory(prefix='rpk-move-callref-') as td:
 w=Path(td)/'w';shutil.copytree(repo/'samples/kotlin-maven-simple',w);shutil.rmtree(w/'src')
 a=w/'src/main/kotlin/javax/swing/SwingUtilities/Function.kt';c=w/'src/main/kotlin/consumer/Consumer.kt';a.parent.mkdir(parents=True);c.parent.mkdir(parents=True)
 a.write_text('package javax.swing.SwingUtilities\nfun isEventDispatchThread(): Boolean = true\n')
 c.write_text('package consumer\nimport javax.swing.SwingUtilities.isEventDispatchThread\nval ref: () -> Boolean = ::isEventDispatchThread\nfun main() { print(ref()) }\n')
 before=run_program(w)
 p,sym=cli_call(w,'symbols',['--file','src/main/kotlin/javax/swing/SwingUtilities/Function.kt']); assert p.returncode==0,(p.stdout,p.stderr)
 target=next(x for x in sym['symbols'] if x['name']=='isEventDispatchThread' and x['kind']=='function')
 p,body=cli_call(w,'move-declaration',['--symbol',target['id'],'--to-package','moved','--accept-external-consumer-risk','--apply'])
 after=run_program(w)
 print(json.dumps({'returncode':p.returncode,'status':body.get('status'),'refusal':body.get('refusalCode'),'diagnostics':body.get('diagnostics'),'before':before,'after':after,'consumer':c.read_text(),'moved_exists':(w/'src/main/kotlin/moved/Function.kt').exists()},indent=2))
 if p.returncode: print(p.stdout,p.stderr)

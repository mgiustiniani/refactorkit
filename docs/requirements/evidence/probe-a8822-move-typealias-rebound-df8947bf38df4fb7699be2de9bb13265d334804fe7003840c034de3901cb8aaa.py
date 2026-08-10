from pathlib import Path
import importlib.util,tempfile,shutil,os,subprocess,json
repo=Path('/tmp/refactorkit-a8822-audit');spec=importlib.util.spec_from_file_location('shared',repo/'scripts/smoke-packaged-kotlin.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
cli=repo/'modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit';jdk=Path('/usr/lib/jvm/java-21-openjdk');cache=Path.home()/'.gradle/caches/modules-2/files-2.1';comp=s.artifact(cache,'org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21');cp=[s.artifact(cache,'org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-reflect','1.6.10'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-daemon-embeddable','2.0.21'),s.artifact(cache,'org.jetbrains.intellij.deps','trove4j','1.0.20200330'),s.artifact(cache,'org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),s.artifact(cache,'org.jetbrains','annotations','13.0')]
def call(w,op,args):
 p=subprocess.run(s.command_for(cli,['kotlin',op,str(w),'--jdk-home',str(jdk),'--compiler-jar',str(comp),'--compiler-classpath',os.pathsep.join(map(str,cp)),'--request-id','move-typealias-rebound',*args]),text=True,capture_output=True,timeout=800)
 try:b=json.loads(p.stdout)
 except:b={'stdout':p.stdout}
 return p,b
def run_program(w):
 out=w/'probe-out';shutil.rmtree(out,ignore_errors=True);out.mkdir();src=[str(x) for x in w.rglob('*.kt') if 'probe-out' not in x.parts]
 p=subprocess.run([str(jdk/'bin/java'),'-cp',os.pathsep.join(map(str,[comp,*cp])),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-reflect','-no-stdlib','-classpath',str(cp[0]),'-jvm-target','21','-d',str(out),*src],text=True,capture_output=True,timeout=500);assert p.returncode==0,(p.stdout,p.stderr)
 p=subprocess.run([str(jdk/'bin/java'),'-cp',os.pathsep.join([str(out),str(cp[0])]),'consumer.ConsumerKt'],text=True,capture_output=True,timeout=60);assert p.returncode==0,(p.stdout,p.stderr);return p.stdout
with tempfile.TemporaryDirectory(prefix='rpk-move-typealias-') as td:
 w=Path(td)/'w';shutil.copytree(repo/'samples/kotlin-maven-simple',w);shutil.rmtree(w/'src')
 files={
  'src/main/kotlin/source/api/Function.kt':'package source.api\nfun selected(): String = Chosen::class.qualifiedName!!\n',
  'src/main/kotlin/source/api/Alias.kt':'package source.api\ntypealias Chosen = java.util.concurrent.TimeUnit\n',
  'src/main/kotlin/target/api/Alias.kt':'package target.api\ntypealias Chosen = java.time.temporal.ChronoUnit\n',
  'src/main/kotlin/consumer/Consumer.kt':'package consumer\nimport source.api.selected\nfun main() { print(selected()) }\n',
 }
 for rel,text in files.items(): f=w/rel;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text)
 before=run_program(w); consumer=w/'src/main/kotlin/consumer/Consumer.kt';source=w/'src/main/kotlin/source/api/Function.kt'; source_before={r:(w/r).read_bytes() for r in files}
 p,sym=call(w,'symbols',['--file','src/main/kotlin/source/api/Function.kt']); assert p.returncode==0,(p.stdout,p.stderr); target=next(x for x in sym['symbols'] if x['name']=='selected' and x['kind']=='function')
 p,body=call(w,'move-declaration',['--symbol',target['id'],'--to-package','target.api','--accept-external-consumer-risk','--apply'])
 after=run_program(w);txroot=w/'.refactorkit/transactions'; tx=[x.relative_to(w).as_posix() for x in txroot.rglob('*') if x.is_file()] if txroot.exists() else []
 print(json.dumps({'returncode':p.returncode,'status':body.get('status'),'refusal':body.get('refusalCode'),'diagnostics':body.get('diagnostics'),'before_identity':before,'after_identity':after,'consumer':consumer.read_text(),'source_exists':source.exists(),'destination':(w/'src/main/kotlin/target/api/Function.kt').read_text() if (w/'src/main/kotlin/target/api/Function.kt').exists() else None,'transaction_files':tx,'stderr':p.stderr},indent=2))

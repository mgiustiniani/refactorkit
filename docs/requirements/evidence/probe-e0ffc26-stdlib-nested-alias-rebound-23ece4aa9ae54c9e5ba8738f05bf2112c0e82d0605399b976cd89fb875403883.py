from pathlib import Path
import importlib.util,tempfile,shutil,os,subprocess,json
repo=Path('/tmp/refactorkit-e0ffc26-audit');spec=importlib.util.spec_from_file_location('shared',repo/'scripts/smoke-packaged-kotlin.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
cli=repo/'modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit';jdk=Path('/usr/lib/jvm/java-21-openjdk');cache=Path.home()/'.gradle/caches/modules-2/files-2.1';comp=s.artifact(cache,'org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21');cp=[s.artifact(cache,'org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-reflect','1.6.10'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-daemon-embeddable','2.0.21'),s.artifact(cache,'org.jetbrains.intellij.deps','trove4j','1.0.20200330'),s.artifact(cache,'org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),s.artifact(cache,'org.jetbrains','annotations','13.0')]
def call(w,op,args):
 p=subprocess.run(s.command_for(cli,['kotlin',op,str(w),'--jdk-home',str(jdk),'--compiler-jar',str(comp),'--compiler-classpath',os.pathsep.join(map(str,cp)),'--request-id','nested-alias-class-rebound',*args]),text=True,capture_output=True,timeout=500)
 try:b=json.loads(p.stdout)
 except:b={'stdout':p.stdout}
 return p,b
def compile_javap(w,owner):
 out=w/'probe-out';shutil.rmtree(out,ignore_errors=True);out.mkdir();src=[str(x) for x in w.rglob('*.kt') if 'probe-out' not in x.parts]
 p=subprocess.run([str(jdk/'bin/java'),'-cp',os.pathsep.join(map(str,[comp,*cp])),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-reflect','-no-stdlib','-classpath',str(cp[0]),'-jvm-target','21','-d',str(out),*src],text=True,capture_output=True,timeout=500);assert p.returncode==0,(p.stdout,p.stderr)
 p=subprocess.run([str(jdk/'bin/javap'),'-classpath',str(out),'-p','-v',owner],text=True,capture_output=True,timeout=60);assert p.returncode==0,p.stderr
 return '\n'.join(x.strip() for x in p.stdout.splitlines() if 'java/util/List<' in x or 'Signature:' in x)
def txfiles(w):
 tx=w/'.refactorkit/transactions';return [x.relative_to(w).as_posix() for x in tx.rglob('*') if x.is_file()] if tx.exists() else []
results={}
with tempfile.TemporaryDirectory(prefix='rpk-nested-alias-class-') as td:
 root=Path(td)
 w=root/'move';shutil.copytree(repo/'samples/kotlin-maven-simple',w);shutil.rmtree(w/'src')
 files={
 'src/main/kotlin/source/api/Function.kt':'package source.api\nprivate fun hidden(value: List<ArrayList<String>>): Int = value.size\nfun selected(): Int = 42\n',
 'src/main/kotlin/target/api/ArrayList.kt':'package target.api\nclass ArrayList<T>\n',
 'src/main/kotlin/consumer/Consumer.kt':'package consumer\nimport source.api.selected\nfun consume(): Int = selected()\n'}
 for rel,text in files.items():f=w/rel;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text)
 before=compile_javap(w,'source.api.FunctionKt');p,sym=call(w,'symbols',['--file','src/main/kotlin/source/api/Function.kt']);target=next((x for x in sym.get('symbols',[]) if x.get('name')=='selected'),None)
 if p.returncode==0 and target:
  source=w/'src/main/kotlin/source/api/Function.kt';dest=w/'src/main/kotlin/target/api/Function.kt';consumer=w/'src/main/kotlin/consumer/Consumer.kt';p2,b=call(w,'move-declaration',['--symbol',target['id'],'--to-package','target.api','--accept-external-consumer-risk','--apply']);after=compile_javap(w,'target.api.FunctionKt') if dest.exists() else None
  results['move']={'symbols_rc':p.returncode,'rc':p2.returncode,'status':b.get('status'),'refusal':b.get('refusalCode'),'before_signature':before,'after_signature':after,'source_exists':source.exists(),'destination':dest.read_text() if dest.exists() else None,'consumer':consumer.read_text(),'transactions':txfiles(w),'stderr':p2.stderr}
 else: results['move']={'symbols_rc':p.returncode,'symbols':sym,'stderr':p.stderr,'before_signature':before}
 w=root/'organize';shutil.copytree(repo/'samples/kotlin-maven-simple',w);shutil.rmtree(w/'src')
 files={
 'src/main/kotlin/other/shadow/ArrayList.kt':'package other.shadow\nclass ArrayList<T>\n',
 'src/main/kotlin/consumer/Consumer.kt':'package consumer\nimport kotlin.collections.ArrayList\nimport other.shadow.*\nfun value(items: List<ArrayList<String>>): Int = items.size\n'}
 for rel,text in files.items():f=w/rel;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text)
 source=w/'src/main/kotlin/consumer/Consumer.kt';before=compile_javap(w,'consumer.ConsumerKt');source_before=source.read_bytes();p,b=call(w,'organize-imports',['--file','src/main/kotlin/consumer/Consumer.kt','--apply']);after=compile_javap(w,'consumer.ConsumerKt')
 results['organize']={'rc':p.returncode,'status':b.get('status'),'refusal':b.get('refusalCode'),'before_signature':before,'after_signature':after,'mutated':source.read_bytes()!=source_before,'source':source.read_text(),'transactions':txfiles(w),'stderr':p.stderr,'stdout':p.stdout if not b else None}
print(json.dumps(results,indent=2))

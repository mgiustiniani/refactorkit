from pathlib import Path
import importlib.util,tempfile,shutil,os,subprocess,json
repo=Path('/tmp/refactorkit-a8822-audit');spec=importlib.util.spec_from_file_location('shared',repo/'scripts/smoke-packaged-kotlin.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
cli=repo/'modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit';jdk=Path('/usr/lib/jvm/java-21-openjdk');cache=Path.home()/'.gradle/caches/modules-2/files-2.1';comp=s.artifact(cache,'org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21');cp=[s.artifact(cache,'org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-reflect','1.6.10'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-daemon-embeddable','2.0.21'),s.artifact(cache,'org.jetbrains.intellij.deps','trove4j','1.0.20200330'),s.artifact(cache,'org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),s.artifact(cache,'org.jetbrains','annotations','13.0')]
def call(w,op,args=[]):
 p=subprocess.run(s.command_for(cli,['kotlin',op,str(w),'--jdk-home',str(jdk),'--compiler-jar',str(comp),'--compiler-classpath',os.pathsep.join(map(str,cp)),'--request-id','maven-xplugin',*args]),text=True,capture_output=True,timeout=800)
 try:b=json.loads(p.stdout)
 except:b={'stdout':p.stdout}
 return p,b
with tempfile.TemporaryDirectory(prefix='rpk-maven-xplugin-') as td:
 w=Path(td)/'w';shutil.copytree(repo/'samples/kotlin-maven-simple',w);shutil.rmtree(w/'src')
 plugin=s.artifact(cache,'org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.0.21'); shutil.copy2(plugin,w/'custom-compiler-plugin.jar'); pom=w/'pom.xml'; pom.write_text(pom.read_text().replace('<jvmTarget>21</jvmTarget>','<jvmTarget>21</jvmTarget><args><arg>-Xplugin=${project.basedir}/custom-compiler-plugin.jar</arg></args>'))
 f=w/'src/main/kotlin/probe/Subject.kt';f.parent.mkdir(parents=True);f.write_text('package probe\nfun answer(): Int = 40 + 2\n')
 p,d=call(w,'diagnostics'); p2,e=call(w,'extract-method',['--file','src/main/kotlin/probe/Subject.kt','--start-line','2','--end-line','2','--method-name','fortyTwo','--apply'])
 txroot=w/'.refactorkit/transactions'; tx=[x.relative_to(w).as_posix() for x in txroot.rglob('*') if x.is_file()] if txroot.exists() else []
 print(json.dumps({'diagnostics_rc':p.returncode,'diagnostics_status':d.get('status'),'diagnostics':d.get('diagnostics'),'failure':d.get('failure'),'extract_rc':p2.returncode,'extract_status':e.get('status'),'extract_refusal':e.get('refusalCode'),'extract_summary':e.get('summary'),'source':f.read_text(),'transactions':tx,'stderr':p2.stderr},indent=2))

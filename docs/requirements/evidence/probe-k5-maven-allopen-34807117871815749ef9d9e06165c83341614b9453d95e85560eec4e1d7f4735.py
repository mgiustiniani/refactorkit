from pathlib import Path
import importlib.util,tempfile,shutil,os,subprocess,json
repo=Path('/tmp/refactorkit-k5-review-ce65175-pre');spec=importlib.util.spec_from_file_location('shared',repo/'scripts/smoke-packaged-kotlin.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
cli=repo/'modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit';jdk=Path('/usr/lib/jvm/java-21-openjdk');cache=Path.home()/'.gradle/caches/modules-2/files-2.1';comp=s.artifact(cache,'org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21');cp=[s.artifact(cache,'org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-reflect','1.6.10'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-daemon-embeddable','2.0.21'),s.artifact(cache,'org.jetbrains.intellij.deps','trove4j','1.0.20200330'),s.artifact(cache,'org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),s.artifact(cache,'org.jetbrains','annotations','13.0')]
def call(w,op,args=[]):
 p=subprocess.run(s.command_for(cli,['kotlin',op,str(w),'--jdk-home',str(jdk),'--compiler-jar',str(comp),'--compiler-classpath',os.pathsep.join(map(str,cp)),'--request-id','maven-plugin',*args]),text=True,capture_output=True,timeout=700)
 try:b=json.loads(p.stdout)
 except:b={'stdout':p.stdout,'stderr':p.stderr}
 return p,b
with tempfile.TemporaryDirectory(prefix='rpk-maven-plugin-') as td:
 w=Path(td)/'w';shutil.copytree(repo/'samples/kotlin-maven-simple',w);shutil.rmtree(w/'src');pom=w/'pom.xml';text=pom.read_text().replace('<jvmTarget>21</jvmTarget>','<jvmTarget>21</jvmTarget><compilerPlugins><plugin>all-open</plugin></compilerPlugins><pluginOptions><option>all-open:annotation=probe.OpenMe</option></pluginOptions>');pom.write_text(text)
 f=w/'src/main/kotlin/probe/PluginGeneratedSemantics.kt';f.parent.mkdir(parents=True);f.write_text('package probe\nannotation class OpenMe\n@OpenMe class PluginSubject\nfun answer(): Int = 40 + 2\n')
 p,d=call(w,'diagnostics');p2,e=call(w,'extract-method',['--file','src/main/kotlin/probe/PluginGeneratedSemantics.kt','--start-line','4','--end-line','4','--method-name','fortyTwo'])
 print(json.dumps({'diagnostics_rc':p.returncode,'diagnostics_status':d.get('status'),'diagnostics':d.get('diagnostics'),'failure':d.get('failure'),'extract_rc':p2.returncode,'extract_status':e.get('status'),'extract_refusal':e.get('refusalCode'),'extract_summary':e.get('summary'),'extract_stderr':p2.stderr},indent=2))

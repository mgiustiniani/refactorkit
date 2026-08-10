from pathlib import Path
import importlib.util,tempfile,shutil,os,subprocess,json
repo=Path('/tmp/refactorkit-a8822-audit');spec=importlib.util.spec_from_file_location('shared',repo/'scripts/smoke-packaged-kotlin.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
cli=repo/'modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit';jdk=Path('/usr/lib/jvm/java-21-openjdk');cache=Path.home()/'.gradle/caches/modules-2/files-2.1';comp=s.artifact(cache,'org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21');cp=[s.artifact(cache,'org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-reflect','1.6.10'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-daemon-embeddable','2.0.21'),s.artifact(cache,'org.jetbrains.intellij.deps','trove4j','1.0.20200330'),s.artifact(cache,'org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),s.artifact(cache,'org.jetbrains','annotations','13.0')]
def call(w,op,args):
 p=subprocess.run(s.command_for(cli,['kotlin',op,str(w),'--jdk-home',str(jdk),'--compiler-jar',str(comp),'--compiler-classpath',os.pathsep.join(map(str,cp)),'--request-id','xplugin-move',*args]),text=True,capture_output=True,timeout=800)
 try:b=json.loads(p.stdout)
 except:b={'stdout':p.stdout}
 return p,b
with tempfile.TemporaryDirectory(prefix='rpk-xplugin-move-') as td:
 w=Path(td)/'w';shutil.copytree(repo/'samples/kotlin-maven-simple',w);shutil.rmtree(w/'src')
 plugin=s.artifact(cache,'org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.0.21');shutil.copy2(plugin,w/'custom-compiler-plugin.jar')
 pom=w/'pom.xml';pom.write_text(pom.read_text().replace('<jvmTarget>21</jvmTarget>','<jvmTarget>21</jvmTarget><args><arg>-Xplugin=${project.basedir}/custom-compiler-plugin.jar</arg></args>'))
 a=w/'src/main/kotlin/api/Function.kt';c=w/'src/main/kotlin/consumer/Consumer.kt';a.parent.mkdir(parents=True);c.parent.mkdir(parents=True);a.write_text('package api\nfun selected(): Int = 42\n');c.write_text('package consumer\nimport api.selected\nfun use(): Int = selected()\n')
 p,sym=call(w,'symbols',['--file','src/main/kotlin/api/Function.kt']);assert p.returncode==0,(p.stdout,p.stderr);target=next(x for x in sym['symbols'] if x['name']=='selected')
 p,b=call(w,'move-declaration',['--symbol',target['id'],'--to-package','moved','--accept-external-consumer-risk','--apply'])
 txroot=w/'.refactorkit/transactions';tx=[x.relative_to(w).as_posix() for x in txroot.rglob('*') if x.is_file()] if txroot.exists() else []
 print(json.dumps({'returncode':p.returncode,'status':b.get('status'),'refusal':b.get('refusalCode'),'diagnostics':b.get('diagnostics'),'source_exists':a.exists(),'destination_exists':(w/'src/main/kotlin/moved/Function.kt').exists(),'consumer':c.read_text(),'transactions':tx,'stderr':p.stderr},indent=2))

from pathlib import Path
import importlib.util,tempfile,shutil,os,subprocess,json
repo=Path('/tmp/refactorkit-k5-review-ce65175-pre');spec=importlib.util.spec_from_file_location('shared',repo/'scripts/smoke-packaged-kotlin.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
cli=repo/'modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit';jdk=Path('/usr/lib/jvm/java-21-openjdk');cache=Path.home()/'.gradle/caches/modules-2/files-2.1';comp=s.artifact(cache,'org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21');cp=[s.artifact(cache,'org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-reflect','1.6.10'),s.artifact(cache,'org.jetbrains.kotlin','kotlin-daemon-embeddable','2.0.21'),s.artifact(cache,'org.jetbrains.intellij.deps','trove4j','1.0.20200330'),s.artifact(cache,'org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),s.artifact(cache,'org.jetbrains','annotations','13.0')]
def call(w,op,args):
 p=subprocess.run(s.command_for(cli,['kotlin',op,str(w),'--jdk-home',str(jdk),'--compiler-jar',str(comp),'--compiler-classpath',os.pathsep.join(map(str,cp)),'--request-id','custom-generated-param',*args]),text=True,capture_output=True,timeout=700)
 try:b=json.loads(p.stdout)
 except:b={'stdout':p.stdout,'stderr':p.stderr}
 return p,b
with tempfile.TemporaryDirectory(prefix='rpk-custom-gen-param-') as td:
 w=Path(td)/'w';shutil.copytree(repo/'samples/kotlin-maven-simple',w);shutil.rmtree(w/'src');pom=w/'pom.xml';text=pom.read_text().replace('<plugins>','''<plugins><plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><executions><execution><goals><goal>add-source</goal></goals><configuration><sources><source>target/custom-generated</source></sources></configuration></execution></executions></plugin>''');pom.write_text(text)
 f=w/'target/custom-generated/probe/Generated.kt';f.parent.mkdir(parents=True);f.write_text('package probe\nprivate fun value(old: Int): Int = old + 1\nfun use(): Int = value(1)\n')
 p,sym=call(w,'symbols',['--file','target/custom-generated/probe/Generated.kt']);target=next(x for x in sym['symbols'] if x['name']=='value' and x['kind']=='function')
 p,b=call(w,'change-signature',['--symbol',target['id'],'--old-name','old','--new-name','renamed','--apply'])
 print(json.dumps({'returncode':p.returncode,'status':b.get('status'),'refusal':b.get('refusalCode'),'diagnostics':b.get('diagnostics'),'source':f.read_text(),'stderr':p.stderr},indent=2))

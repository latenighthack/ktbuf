from pathlib import Path
import subprocess, json, time, sys, os
worktree=Path(sys.argv[1]).resolve()
base=worktree/'build/adversarial-generator-review'; base.mkdir(parents=True,exist_ok=True)
cache=Path(os.environ.get('GRADLE_USER_HOME', str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1'
def jar(group,artifact,version):
    return next((cache/group/artifact/version).glob('*/'+artifact+'-'+version+'.jar'))
compiler=jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.3.10')
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.3.10')
script=jar('org.jetbrains.kotlin','kotlin-script-runtime','2.3.10')
coroutines=jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.9.0')
annotations=next((cache/'org.jetbrains'/'annotations').glob('*/*/annotations-*.jar'))
reflect=jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10')
compiler_cp=':'.join(map(str,[compiler,stdlib,script,reflect,coroutines,annotations]))
consumer_cp=':'.join(map(str,[stdlib,coroutines,annotations,worktree/'library/build/classes/kotlin/jvm/main']))
(base/'classpaths.json').write_text(json.dumps({'compiler':compiler_cp,'consumer':consumer_cp}))
header='syntax="proto3"; package probe; option java_package="probe";\n'
cases={
 'basic':{'case.proto':header+'message M { int32 id=1; }'},
 'scalar_map':{'case.proto':header+'message M { map<string,int32> values=1; }'},
 'message_map':{'case.proto':header+'message V {int32 id=1;} message M {map<string,V> values=1;}'},
 'optional':{'case.proto':header+'message M {optional int32 id=1;}'},
 'proto2':{'case.proto':'syntax="proto2"; package probe; option java_package="probe"; message M {required int32 id=1; optional int32 count=2 [default=7];}'},
 'enum_only':{'case.proto':header+'enum E { ZERO=0; ONE=1; }'},
 'service_only':{'base.proto':header+'message M {int32 id=1;}','case.proto':header+'import "base.proto"; service S {rpc F(M) returns (M);}'},
 'service_imports':{'base.proto':'syntax="proto3"; package external; option java_package="external"; message M {int32 id=1;}','case.proto':header+'import "base.proto"; message Dummy {} service S {rpc F(external.M) returns (external.M);}'},
 'client_streaming':{'case.proto':header+'message M {} service S {rpc F(stream M) returns (M);}'},
 'missing_java_package':{'case.proto':'syntax="proto3"; package probe; message M {int32 id=1;}'},
 'lowercase_nested':{'case.proto':header+'message M { message inner {int32 id=1;} inner value=1;}'},
 'shadow_writer':{'case.proto':header+'message M {int32 writer=1;}'},
 'shadow_reader':{'case.proto':header+'message M {int32 reader=1;}'},
 'unknown_fields_name':{'case.proto':header+'message M {int32 unknown_fields=1;}'},
 'enum_lowercase':{'case.proto':header+'message M {enum E { zero=0; one=1;} E e=1;}'},
 'prefix_packages':{'base.proto':'syntax="proto3"; package probe.sub; option java_package="probe.sub"; message V {int32 id=1;}','case.proto':header+'import "base.proto"; message M {probe.sub.V value=1;}'},
 'cross_package_nested_repeated':{'base.proto':'syntax="proto3"; package external; option java_package="external"; message Outer {message Inner {int32 id=1;}}','case.proto':header+'import "base.proto"; message M {repeated external.Outer.Inner values=1;}'},
 'keyword_oneof':{'case.proto':header+'message M {oneof when {string is=1; int32 class=2;}}'},
 'bytes_oneof':{'case.proto':header+'message M {oneof choice {bytes payload=1;}}'},
}
results=[]
for name,files in cases.items():
    folder=base/name; folder.mkdir(exist_ok=True); out=folder/'out'; out.mkdir(exist_ok=True)
    for f,s in files.items(): (folder/f).write_text(s)
    cmd=['protoc','-I'+str(folder),'--kt_out='+str(out),*files.keys()]
    proc=subprocess.run(cmd,cwd=folder,capture_output=True,text=True,timeout=20)
    (folder/'generator.log').write_text(proc.stdout+proc.stderr)
    kt=list(out.rglob('*.kt')); result={'case':name,'protoc':proc.returncode,'files':len(kt)}
    if proc.returncode==0 and kt:
        cmd=['java','-Xmx512m','-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',consumer_cp,'-d',str(folder/'classes'),*map(str,kt)]
        proc=subprocess.run(cmd,capture_output=True,text=True,timeout=45)
        (folder/'compile.log').write_text(proc.stdout+proc.stderr)
        result['compile']=proc.returncode
        result['first_errors']=[line for line in proc.stderr.splitlines() if 'error:' in line][:2]
    print(json.dumps(result),flush=True); results.append(result)
(base/'results.json').write_text(json.dumps(results,indent=2))

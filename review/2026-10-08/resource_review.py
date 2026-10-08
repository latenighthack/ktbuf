from pathlib import Path
import subprocess,json,sys
worktree=Path(sys.argv[1]).resolve(); b=worktree/'build/adversarial-generator-review'; config=json.loads((b/'classpaths.json').read_text())
compiler=['java','-Xmx512m','-cp',config['compiler'],'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17']

def run_case(case,src):
 p=b/case; f=p/'Consumer.kt';f.write_text(src)
 cp=config['consumer']+':'+str(p/'classes')
 c=subprocess.run(compiler+['-classpath',cp,'-d',str(p/'consumer-classes'),str(f)],capture_output=True,text=True,timeout=30)
 (p/'consumer-compile.log').write_text(c.stdout+c.stderr)
 if c.returncode:
  print(case,'consumer_compile',c.returncode,'\n',c.stderr[:1400],flush=True); return
 proc=subprocess.run(['java','-cp',cp+':'+str(p/'consumer-classes'),'ConsumerKt'],capture_output=True,text=True,timeout=15)
 (p/'consumer-run.log').write_text(proc.stdout+proc.stderr); print(case,proc.stdout,proc.stderr,flush=True)
run_case('message_map','''import probe.*
fun main() {
 val m=M(values=mapOf("a" to V(id=7)))
 println("populated_map_wire_bytes="+m.toByteArray().size)
 val decoded=M.fromByteArray(byteArrayOf(10,7,10,1,97,18,2,8,7))
 println("decoded_map="+decoded.values+" unknown_size="+decoded.unknownFields?.size)
}
''')
run_case('proto2','''import probe.*
fun main(){val m=M.fromByteArray(byteArrayOf()); println("proto2_missing_required_accepted="+m+" expected_count_default=7"); println("encoded="+m.toByteArray().size)}
''')
run_case('enum_only','''import probe.*
fun main(){println(E.ZERO)}
''')
run_case('service_only','''import probe.*
fun main(){val client:SService?=null; println(client)}
''')
p=worktree/'build/adversarial-resource-review';p.mkdir(parents=True,exist_ok=True)
f=p/'ResourceProbe.kt';f.write_text('''import com.latenighthack.ktbuf.conformance.v1.*
import com.latenighthack.ktbuf.patch.*
import com.latenighthack.ktbuf.*
import review.v1.Recursive
import review.v1.fromByteArray

fun main(args:Array<String>) {
 when(args.first()) {
  "recursion" -> {
   var data=byteArrayOf()
   repeat(1000) { val inner=data; data=ProtobufOutputStream.encode { encode(1) {encodeRaw(inner)} } }
   println("deep_message_wire_bytes="+data.size)
   try { Recursive.fromByteArray(data); println("unbounded recursion accepted") } catch(t:Throwable) { println("parse_result="+t::class.simpleName) }
  }
  "quadratic" -> {
   for(n in listOf(2000,10000,40000,80000)) {
    val bytes=AllTypes(rInt32=List(n){1}).toByteArray()
    val start=System.nanoTime(); val m=AllTypes.fromByteArray(bytes)
    println("repeated_elements=$n wire_bytes=${bytes.size} parse_ms="+(System.nanoTime()-start)/1_000_000+" decoded="+m.rInt32.size)
   }
  }
  "patch-hang" -> { byteArrayOf(8,1).applyChange(Change.Remove(Path(listOf(Path.Component(1,2))))); println("completed") }
 }
}
''')
cp=config['consumer']+':'+str(worktree/'conformance/build/classes/kotlin/jvm/main')+':'+str(worktree/'patch/build/classes/kotlin/jvm/main')
c=subprocess.run(compiler+['-classpath',cp,'-d',str(p/'classes'),str(f)],capture_output=True,text=True,timeout=30)
(p/'compile.log').write_text(c.stdout+c.stderr)
if c.returncode: print(c.stderr);raise SystemExit(c.returncode)
for case in ['recursion','quadratic','patch-hang']:
 try:
  proc=subprocess.run(['java','-Xss256k','-Xmx128m','-cp',cp+':'+str(p/'classes'),'ResourceProbeKt',case],capture_output=True,text=True,timeout=15 if case!='patch-hang' else 2)
  print(case,proc.returncode,proc.stdout,proc.stderr[:500],flush=True)
 except subprocess.TimeoutExpired as e: print(case,'TIMEOUT',e.stdout,flush=True)

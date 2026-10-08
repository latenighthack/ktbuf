#!/usr/bin/env python3
"""Install defensive regression probes in an isolated ktbuf worktree and run them."""
import argparse, pathlib, shutil, subprocess, sys
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("worktree",type=pathlib.Path)
parser.add_argument("--platforms",action="store_true",help="also run JS, Android, and macOS probes")
args=parser.parse_args(); work=args.worktree.resolve()
if not (work/"gradlew").exists(): parser.error("worktree must be a ktbuf checkout")
source=pathlib.Path(__file__).resolve().parent/"probes"
for f in source.rglob("*"):
    if not f.is_file(): continue
    target=work/f.relative_to(source)
    if target.exists() and target.read_bytes()!=f.read_bytes():
        raise SystemExit("Refusing to overwrite a different probe: "+str(target))
for f in source.rglob("*"):
    if f.is_file():
        target=work/f.relative_to(source); target.parent.mkdir(parents=True,exist_ok=True); shutil.copyfile(f,target)
tasks=[":conformance:jvmTest",":patch:jvmTest",":server:test","--tests","*Adversarial*","--continue"]
print("These probes assert correct behavior; failures reproduce defects in the audited version.",flush=True)
code=subprocess.run(["./gradlew",*tasks],cwd=work).returncode
if args.platforms:
    code2=subprocess.run(["./gradlew",":conformance:jsNodeTest",":conformance:testDebugUnitTest",":conformance:macosArm64Test",":patch:macosArm64Test","--continue"],cwd=work).returncode
    code=max(code,code2)
sys.exit(code)

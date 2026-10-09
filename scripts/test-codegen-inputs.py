#!/usr/bin/env python3
"""Functional checks for generation freshness, executable inputs and imported schemas."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--plugin", required=True, type=Path)
parser.add_argument("--protoc", type=Path, default=Path(shutil.which("protoc")))
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="ktbuf-codegen-") as temporary:
    directory = Path(temporary)
    calls = directory / "calls"
    protoc = directory / "protoc"
    plugin = directory / "protoc-gen-kt"
    protoc.write_text("#!/usr/bin/env python3\nimport os,sys\nfrom pathlib import Path\n" +
        f"if any(value.startswith('--kt_out=') for value in sys.argv):\n Path({str(calls)!r}).open('a').write('generate\\n')\n" +
        f"os.execv({str(args.protoc.resolve())!r}, [{str(args.protoc.resolve())!r}]+sys.argv[1:])\n")
    plugin.write_text("#!/usr/bin/env python3\nimport os,sys\n" + f"os.execv({str(args.plugin.resolve())!r}, [{str(args.plugin.resolve())!r}]+sys.argv[1:])\n")
    protoc.chmod(0o755); plugin.chmod(0o755)
    environment = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ["PATH"])
    base = [str(root / "gradlew"), ":conformance:generateProtoKotlin", "--console=plain", "--no-daemon", "--no-build-cache", f"-PprotocPath={protoc}", f"-PprotocGenKtPath={plugin}"]
    def run(extra=()):
        result = subprocess.run(base + list(extra), cwd=root, env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if result.returncode: raise AssertionError(result.stdout)
        return result.stdout
    def count(): return len(calls.read_text().splitlines()) if calls.exists() else 0
    run(["--rerun-tasks"]); initial = count(); assert initial > 0
    run(); assert count() == initial, "unchanged generation did not remain up to date"
    protoc.write_text(protoc.read_text() + "# new protoc identity\n")
    run(); assert count() == initial + 1, "changed protoc executable reused stale generated sources"
    plugin.write_text(plugin.read_text() + "# new generator identity\n")
    run(); assert count() == initial + 2, "changed generator executable reused stale generated sources"
    source = directory / "source"; imports = directory / "imports"; output = directory / "output"
    source.mkdir(); imports.mkdir()
    (source / "root.proto").write_text('syntax="proto3";package freshness;option java_package="freshness";import "child.proto";message Root { Child child=1; }')
    child = imports / "child.proto"
    child.write_text('syntax="proto3";package freshness;option java_package="freshness";message Child { int32 value=1; }')
    scoped = [f"-PprotoSourceDir={source}", f"-PprotoIncludeDirs={imports}", f"-PprotoOutputDir={output}"]
    run(scoped); before = count(); assert (output / "freshness/root.data.kt").exists()
    run(scoped); assert count() == before
    child.write_text(child.read_text().replace("int32 value", "string value"))
    run(scoped); assert count() == before + 1, "changed imported schema did not invalidate generation"
    run(scoped + ["--build-cache", "--rerun-tasks"])
    cached = count()
    shutil.rmtree(output)
    result = run(scoped + ["--build-cache"])
    assert "FROM-CACHE" in result and count() == cached, "valid generated outputs were not restored from cache"
    saved = {str(file.relative_to(output)): file.read_bytes() for file in output.rglob("*") if file.is_file()}
    (source / "root.proto").write_text("syntax = ;")
    try:
        run(scoped)
        raise AssertionError("invalid schema succeeded")
    except AssertionError as failure:
        assert "protoc generation failed" in str(failure), str(failure)
    assert saved == {str(file.relative_to(output)): file.read_bytes() for file in output.rglob("*") if file.is_file()}, "failed generation destroyed previously valid output"
    print("generation freshness: unchanged inputs, both executable identities and imported schema changes passed")

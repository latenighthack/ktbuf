#!/usr/bin/env bash
set -euo pipefail
runtime_root="$(cd "$(dirname "$0")/.." && pwd)"
generator_root="$(cd "${1:?Usage: validate-generator.sh GENERATOR_CHECKOUT}" && pwd)"
# An old generator must not turn a filtered run or skipped consumers into success.
for regression in TestMapFieldsRoundTripDefaultsDuplicatesAndBuilders TestInvalidJavaPackageIsRejectedBeforeAnyOutput TestWireServiceNamesRetainTheProtoIdentifier TestClientStreamingUnaryServiceCompilesAndCollectsFiniteRequests; do
  if ! rg -q "func ${regression}" "$generator_root/main_test.go"; then
    echo "Required generator regression is missing: $regression" >&2
    exit 1
  fi
done
"$runtime_root/gradlew" -p "$runtime_root" writeGeneratorClasspaths --console=plain
export KTBUF_TEST_CLASSPATHS="$runtime_root/build/generator-classpaths.json"
export KTBUF_TEST_JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
cd "$generator_root"
go test ./... -count=1 -v

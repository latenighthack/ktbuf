#!/usr/bin/env python3
"""Check that all affected runtime targets and paired generated consumers gate release."""
from pathlib import Path
import re
import yaml

root = Path(__file__).resolve().parents[1]
ci = yaml.safe_load((root / '.github/workflows/gradle.yml').read_text())
release = yaml.safe_load((root / '.github/workflows/release.yml').read_text())
required = {
    ':android-consumer:assembleDebug', ':android-consumer:assembleRelease',
    ':rpc:jvmTest', ':rpc:testDebugUnitTest', ':rpc:testReleaseUnitTest',
    ':rpc:jsNodeTest', ':rpc:jsBrowserTest', ':rpc:macosArm64Test', ':rpc:linuxX64Test',
    ':patch:jvmTest', ':patch:testDebugUnitTest', ':patch:jsNodeTest', ':patch:macosArm64Test',
    ':conformance:jvmTest', ':conformance:jsNodeTest', ':conformance:macosArm64Test', ':server:test'
}
targets = ' '.join(item['target'] for item in ci['jobs']['build']['strategy']['matrix']['include'])
missing = sorted(task for task in required if task not in targets.split())
assert not missing, f'CI omits regression targets: {missing}'
assert 'generator' in ci['jobs'], 'paired generator/consumer tests are absent'
steps = str(ci['jobs']['generator'].get('steps', []))
assert 'validate-generator.sh' in steps, 'generator validation helper is not executed'
assert 'contracts' in ci['jobs'], 'CI contract regression is not run by CI'
assert 'workflow_call' in ci['on'], 'runtime validation must be callable by release'
assert release['on']['push']['tags'] == ['v*'], 'release must retain the version tag trigger'
assert ci['jobs']['contracts']['steps'][-1]['run'].find('test-validation-gates.py') >= 0
publish = release['jobs']['publish']
needs = publish.get('needs', [])
if isinstance(needs, str): needs = [needs]
assert not publish.get('if'), 'publish must use the default successful-needs condition'
assert 'validation' in needs, 'release can publish without validation'
assert release['jobs']['validation'].get('uses') == './.github/workflows/gradle.yml'
for workflow in (root / '.github/workflows').glob('*.yml'):
    text = workflow.read_text()
    assert 'ToSonatypeRepository' not in text and 'closeSonatypeStagingRepository' not in text, f'obsolete release path in {workflow}'
assert 'test-codegen-inputs.py' in str(ci['jobs']['generator']['steps'])
pins = dict(line.split('=',1) for line in (root / 'gradle/protobuf-tools.properties').read_text().splitlines() if line and not line.startswith('#'))
assert re.fullmatch('[0-9a-f]{40}', pins['protocGenKt']), 'generator must identify its reviewed source revision'
print('validation gates: runtime/platform coverage, paired consumers and release dependency passed')

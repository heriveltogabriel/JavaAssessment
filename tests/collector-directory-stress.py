#!/usr/bin/env python3
"""Reproduce the pre-budget OOM with the current classes; create only disposable empty files."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument('--java', required=True)
parser.add_argument('--classpath', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='assessment-wide-directory-') as temporary:
    root = Path(temporary).resolve()
    for number in range(250000):
        (root / (str(number).zfill(7) + '-' + 'x' * 200)).touch()
    start = time.monotonic()
    result = subprocess.run([args.java, '-Xms16m', '-Xmx64m', '-cp', args.classpath,
                             'assessment.DirectoryStressFixture', str(root), '100'],
                            capture_output=True, text=True, timeout=45)
    (args.output / 'directory-stress.log').write_text(result.stdout + result.stderr)
    metrics = json.loads(result.stdout.splitlines()[-1]) if result.returncode == 0 else {}
    report = {'directoryEntries': 250000, 'entryNameLength': 208, 'configuredFileBudget': 100,
              'heapMiB': 64, 'exitCode': result.returncode, 'outOfMemory': 'OutOfMemoryError' in result.stderr,
              'wallSeconds': round(time.monotonic() - start, 3), 'scan': metrics}
    (args.output / 'directory-stress.json').write_text(json.dumps(report, indent=2) + '\n')
    assert result.returncode == 0 and metrics['filesVisited'] == 100 and metrics['partial'] is True, report
    assert metrics['peakHeapBytes'] < 64 * 1024 * 1024, report
    print(json.dumps(report), flush=True)

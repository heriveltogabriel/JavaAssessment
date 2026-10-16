"""Bounded release-tool validation only; no signatures, network or host access."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'ops'))
from agent_update_signing import validate_helper_hashes, sha, MAX_HELPER_BASELINES

hashes = [sha(('test-only-helper-' + str(i)).encode()) for i in range(33)]
checks = 0
for count in (1, 16, 17, 32):
    validate_helper_hashes(hashes[:count], hashes[0])
    checks += 1
for invalid in ([], hashes, [hashes[0], hashes[0]], [hashes[1]], ['*'], ['A' * 64],
                ['g' * 64], [None], [1], [{'hash': hashes[0]}], hashes[0], None):
    try:
        validate_helper_hashes(invalid, hashes[0])
    except RuntimeError:
        checks += 1
    else:
        raise AssertionError('Invalid helper allowlist accepted')
assert MAX_HELPER_BASELINES == 32
print('HELPER_BASELINE_LIMIT=PASS checks=' + str(checks))

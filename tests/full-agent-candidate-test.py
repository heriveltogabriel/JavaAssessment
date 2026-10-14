#!/usr/bin/env python3
"""Public candidate verification and rejection checks; no signing or host access."""
import argparse
import importlib.util
import io
from pathlib import Path
import stat
import sys
import tempfile
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ops'))
spec = importlib.util.spec_from_file_location('candidate_verifier', ROOT / 'ops/verify-full-agent-candidate.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
checks = 0


def check(value):
    global checks
    assert value
    checks += 1


def reject(call):
    try:
        call()
    except (RuntimeError, ValueError, OSError, KeyError):
        check(True)
        return
    raise AssertionError('Invalid candidate input accepted')


def main(candidate):
    pins = ROOT / 'docs/validation/agent-migration-baselines.json'
    public = ROOT / 'collector/public/agent-update-public.der'
    result = module.verify(candidate, candidate.parent, pins, public)
    check(result['result'] == 'PUBLIC_CANDIDATE_VERIFIED' and result['candidateOnly'] is True
          and result['productionCompatibilityValidated'] is False and result['privateSigningMaterialRead'] is False)
    original_parse = module.parse
    for corrupt in ('candidateOnly', 'hash'):
        def changed(raw):
            parsed = original_parse(raw)
            if isinstance(parsed, dict) and parsed.get('status') == 'prepared-local-build':
                if corrupt == 'candidateOnly':
                    parsed['candidateOnly'] = False
                else:
                    parsed['artifacts'][0]['sha256'] = '0' * 64
            return parsed
        with patch.object(module, 'parse', side_effect=changed):
            reject(lambda: module.verify(candidate, candidate.parent, pins, public))
    reject(lambda: module.parse('{"schema":1,"schema":2}'))
    reject(lambda: module.version('2.0.9-beta'))
    reject(lambda: module.version('02.0.9'))
    for name, mode in (('../outside', stat.S_IFREG), ('/absolute', stat.S_IFREG), ('linked', stat.S_IFLNK)):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            item = zipfile.ZipInfo(name)
            item.create_system = 3
            item.external_attr = (mode | 0o600) << 16
            archive.writestr(item, b'public test bytes')
        reject(lambda: module.archive(stream.getvalue()))
    with tempfile.TemporaryDirectory(prefix='candidate-negative-') as temp:
        scratch = Path(temp)
        (scratch / 'public.der').write_bytes(public.read_bytes())
        pem = scratch / 'public.pem'
        pem.write_bytes(module.openssl('pkey', '-pubin', '-inform', 'DER', '-in', scratch / 'public.der', '-outform', 'PEM'))
        reject(lambda: module.check_signature(pem, b'{}', bytes(384), scratch))
    print('Public full-agent candidate PASS ' + str(checks) + ' checks; no private signing material or host access.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    main(parser.parse_args().candidate)

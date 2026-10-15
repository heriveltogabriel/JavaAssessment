#!/usr/bin/env python3
"""Public candidate verification and rejection checks; no signing or host access."""
import argparse
import copy
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
          and result['productionCompatibilityValidated'] is False and result['privateSigningMaterialRead'] is False
          and result['bootstrapOriginVersion'] == '2.0.9' and result['immutableBootstrapVerified'] is True)
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
    # An outer ZIP checksum can be correct while its internal manifest is stale.
    # This was the failed 2.0.13 web amendment: reject it before publication.
    def web_archive(body, manifest):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr('web/app.js', body)
            archive.writestr('SHA256SUMS', manifest)
        return stream.getvalue()
    original_web = b'original web application'
    checksum = module.sha(original_web) + '  web/app.js\n'
    check(module.archive(web_archive(original_web, checksum))['web/app.js'] == original_web)
    reject(lambda: module.archive(web_archive(b'updated web application', checksum)))
    reject(lambda: module.archive(web_archive(original_web, checksum + checksum)))
    reject(lambda: module.archive(web_archive(original_web, module.sha(original_web) + '  missing.js\n')))
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
        reviewed = module.parse(pins.read_bytes())
        origin = next(row for row in reviewed['reviewedPackagePins'] if row['version'] == '2.0.9')
        original = candidate.parent / '2.0.9/packages/assessment-collector-2.0.9.zip'
        entries, profile = module.legacy_bridge_profile(original, origin['sha256'])
        known = {'2.0.9': (entries, profile, origin)}
        inherited_version, inherited = module.inherited_bootstrap(reviewed, known, candidate.parent, public.read_bytes(), pem, scratch, '2.0.11')
        check(inherited_version == '2.0.9' and module.sha(inherited['bootstrap/anchor.jar']) ==
              reviewed['immutableBootstrapPin']['files']['bootstrap/anchor.jar']['sha256'])
        check(set(inherited) == set(reviewed['immutableBootstrapPin']['files']))
        for corrupt in ('origin', 'package', 'receipt', 'inventory', 'member'):
            bad = copy.deepcopy(reviewed)
            pin = bad['immutableBootstrapPin']
            if corrupt == 'origin':
                pin['version'] = '2.0.8'
            elif corrupt == 'package':
                pin['packageSha256'] = '0' * 64
            elif corrupt == 'receipt':
                pin['packageManifestSha256'] = '0' * 64
            elif corrupt == 'inventory':
                pin['files'].pop('bootstrap/root-trust.sh')
            else:
                pin['files']['bootstrap/anchor.jar']['sha256'] = '0' * 64
            reject(lambda: module.inherited_bootstrap(bad, known, candidate.parent, public.read_bytes(), pem, scratch, '2.0.11'))
        # Even an updated checksum pin cannot make altered, unsigned source
        # metadata an authenticated inherited bootstrap.
        bad = copy.deepcopy(reviewed)
        changed_entries = dict(entries)
        raw = changed_entries['full-update/manifest.json']
        altered = raw.replace(b'"minJava":6', b'"minJava":7')
        check(altered != raw)
        changed_entries['full-update/manifest.json'] = altered
        bad['immutableBootstrapPin']['manifestSha256'] = module.sha(altered)
        changed_known = {'2.0.9': (changed_entries, profile, origin)}
        reject(lambda: module.inherited_bootstrap(bad, changed_known, candidate.parent, public.read_bytes(), pem, scratch, '2.0.11'))
        # A replacement payload with a matching outer pin still has to match
        # the signed original bundle checksum and every signed member.
        bad = copy.deepcopy(reviewed)
        changed_entries = dict(entries)
        changed_entries['full-update/bundle.zip'] += b'altered-public-bundle'
        bad['immutableBootstrapPin']['bundleSha256'] = module.sha(changed_entries['full-update/bundle.zip'])
        changed_known = {'2.0.9': (changed_entries, profile, origin)}
        reject(lambda: module.inherited_bootstrap(bad, changed_known, candidate.parent, public.read_bytes(), pem, scratch, '2.0.11'))
    print('Public full-agent candidate PASS ' + str(checks) + ' checks; no private signing material or host access.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    with patch('agent_update_signing.ensure_keypair', side_effect=AssertionError('Private signing material must never be accessed by public verification')):
        main(parser.parse_args().candidate)

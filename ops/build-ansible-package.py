#!/usr/bin/env python3
"""Package only public Ansible sources; never inventory.yml, Vault or private/."""
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'install/ansible'
FILES = ('README.md', 'ansible.cfg', 'install-agents.yml', 'inventory.example.yml',
         'vault.example.yml', 'templates/download-agent.sh.j2', 'files/manage-agent.sh')

def main():
    target = SOURCE / 'java-assessment-ansible.zip'
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name in FILES:
            entry = zipfile.ZipInfo('java-assessment-ansible/' + name, (2026, 10, 26, 0, 0, 0))
            entry.external_attr = 0o100644 << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, (SOURCE / name).read_bytes())
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix('.zip.sha256').write_text(digest + '  ' + target.name + '\n')
    with zipfile.ZipFile(target) as archive:
        assert archive.testzip() is None
        assert archive.namelist() == ['java-assessment-ansible/' + name for name in FILES]
        for name in FILES:
            assert archive.read('java-assessment-ansible/' + name) == (SOURCE / name).read_bytes()
    print('Ansible ZIP verified: ' + digest)

if __name__ == '__main__':
    main()

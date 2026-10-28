# Ansible Java Policy Implementation Plan

> **For agentic workers:** Execute inline using executing-plans. No subagent dispatch is needed for this scoped change.

**Goal:** Select an existing JVM dynamically under an optional minimum-major and non-IBM policy while preserving healthy agents.

**Architecture:** Keep the policy in the controller-side Ansible helper. Validate candidates with the existing protected package trust helper, inspect bounded JVM properties, then run the existing compatibility probe. Never rewrite the agent JAR or the system Java selection.

**Tech Stack:** POSIX shell, GNU coreutils/find/awk, Ansible raw/script, Python unittest fixtures.

---

- [x] Inspect current selection, residue and V3 recovery behavior.
- [x] Add optional inventory policy variables and argument validation in `install/ansible/install-agents.yml`.
- [x] Implement bounded root-validated discovery in `install/ansible/files/manage-agent.sh`; default policy remains unchanged and explicit paths become strict.
- [x] Add behavioral coverage to `tests/ansible-recovery-test.py` and OS boundary fixtures in `tests/ansible_recovery_fixture.py`.
- [x] Add and run Ansible policy integration cases in `tests/ansible-install-test.py`; include one unchanged default install.
- [x] Update the public README, installation index, Ansible README and example inventory.
- [x] Finish the full recovery test suite (39 PASS), inspect the diff and verify the public ZIP contents/checksum; Ansible integration 4 PASS.
- [ ] Validate on the client host separately; no SSH or deployment is performed by these local checks.

Commands: `python3 tests/ansible-recovery-test.py`; run the three `test_java_policy_*` cases and `test_new_production` in `tests/ansible-install-test.py` using the Ansible test environment; `/bin/sh -n install/ansible/files/manage-agent.sh`; `ansible-playbook --syntax-check -i install/ansible/inventory.example.yml install/ansible/install-agents.yml`.

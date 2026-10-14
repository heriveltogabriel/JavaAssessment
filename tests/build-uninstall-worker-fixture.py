#!/usr/bin/env python3
# Test-only copy: production helper has NO configurable destructive paths.
from pathlib import Path
r=Path(__file__).resolve().parents[1]
out=r/'output/remote-uninstall-review/build/worker-fixture-src/UninstallWorker.java'
out.parent.mkdir(parents=True,exist_ok=True)
s=(r/'collector/UninstallWorker.java').read_text().replace('new File("/opt/java-assessment-uninstall")','new File(System.getProperty("fixture.home"))').replace('new File("/opt/java-assessment-collector")','new File(System.getProperty("fixture.target"))')
out.write_text(s)
print(out)

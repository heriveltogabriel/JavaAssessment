"""Check Java exports with Python's independent JSON/CSV/ZIP readers."""
import csv, io, json, sys, zipfile
from pathlib import Path
root=Path(sys.argv[1])
def read(kind):
    with zipfile.ZipFile(root/(kind+'.zip')) as z:
        assert z.namelist()==['inventario.'+kind]
        assert z.testzip() is None
        return z.read(z.namelist()[0]).decode('utf-8-sig')
doc=json.loads(read('json'))
rows=list(csv.DictReader(io.StringIO(read('csv'),newline='')))
assert rows[0]['recordType']=='metadata'
meta=json.loads(rows[0]['exportMetadata']);meta.pop('exportedAt')
expected_meta=dict(doc['metadata']);expected_meta.pop('exportedAt')
assert meta==expected_meta
expected={(kind,str(v['id'])):v for kind,values in doc['data'].items() for v in values}
assert len(rows)==len(expected)+1
for row in rows[1:]:
    record=expected.pop((row['recordType'],row['id']))
    for key,value in record.items():
        cell=row[key]
        if isinstance(value,(list,dict)):assert json.loads(cell)==value,(key,cell)
        elif value is None:assert cell==''
        elif isinstance(value,bool):assert cell==str(value).lower()
        elif isinstance(value,(int,float)):assert float(cell)==value
        else:
            assert cell==value or cell=="'"+value,(key,cell,value)
            if value.startswith('='):assert cell=="'"+value
    if row['recordType'] not in ('metadata','environments'):
        assert row['machineName']=='ol07' and row['environmentName']=='Produção'
        assert json.loads(row['hostIpAddresses'])==['10.0.0.2']
assert not expected
assert 'SECRET' not in read('csv') and 'PASSWORD' not in read('csv')
print('Single-file CSV/JSON equivalence passed: every exported field, nested evidence, Unicode, quotes/newlines, formula protection, relations and counts.')

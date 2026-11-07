"""Expand the unchanged user plan into independent little-endian test payloads.

Adds a synthetic QGC home marker; this is a derived fixture, not a captured wire log.
Runs on development machines only. Output is shared by JVM and Android tests.
"""
import hashlib
import json
from pathlib import Path
import struct

here = Path(__file__).resolve().parent
source = (here / 'mission-1-as-received.plan').read_bytes()
assert hashlib.sha256(source).hexdigest() == '65c71141dd9395c9f5eb4fbe9adddb204215ba46681df4442b02dadf5f0db231'
mission = json.loads(source)['mission']
items = [{'command': 16, 'frame': 0, 'params': [0, 0, 0, 0, *mission['plannedHomePosition']]}]
for item in mission['items']:
    items.extend(item['TransectStyleComplexItem']['Items'] if item['type'] == 'ComplexItem' else [item])
output = bytearray(struct.pack('<H', len(items)))
for sequence, item in enumerate(items):
    values = [float('nan') if value is None else value for value in item['params']]
    spatial = item['command'] in (16, 17, 18, 19, 21, 22, 82)
    xy = [int(values[k] * 1e7) if spatial else 2147483647 if values[k] != values[k] else int(values[k]) for k in (4, 5)]
    output.extend(struct.pack('<4fiifHHBBBBB', *values[:4], *xy, values[6], sequence, item['command'], 1, 1,
                              {0: 5, 3: 6}.get(item['frame'], item['frame']), int(sequence == 0), int(item.get('autoContinue', True))))
assert len(items) == 831
assert hashlib.sha256(output).hexdigest() == 'ca8a56d5b4f631b71259983bfe6308421eb258bcda59438a83df7cd738847283'
target = here.parents[3] / 'mavlab-android/app/src/test/resources/mission-1-items.bin'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(output)
print(target, len(output), hashlib.sha256(output).hexdigest())

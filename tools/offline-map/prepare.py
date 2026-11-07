#!/usr/bin/env python3
"""Prepare the bundled local-grid-v1 training map. Host tool; no network access."""
import argparse
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import sqlite3
import time
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
LAT, LON = -1.2425, 36.9225
EXTENT, RESOLUTION, SIZE, TILE = 1024, 0.5, 2048, 256
ATTRIBUTION = '© OpenStreetMap contributors'


def prepare(output, diagnostic_dir=None):
    source = ROOT / 'source/dandora.osm.gz'
    provenance = json.loads((ROOT / 'source/provenance.json').read_text())
    assert hashlib.sha256(source.read_bytes()).hexdigest() == provenance['gzip_sha256']
    xml = gzip.decompress(source.read_bytes())
    assert hashlib.sha256(xml).hexdigest() == provenance['sanitized_xml_sha256']
    tree = ET.fromstring(xml)
    lon_scale = 111_320 * math.cos(math.radians(LAT))
    def xy(e):
        return ((float(e.get('lon')) - LON) * lon_scale / RESOLUTION + SIZE / 2,
                SIZE / 2 - (float(e.get('lat')) - LAT) * 111_320 / RESOLUTION)
    nodes = {e.get('id'): xy(e) for e in tree.findall('node')}
    areas, roads, counts = [], [], {}
    for way in tree.findall('way'):
        tags = {e.get('k'): e.get('v') for e in way.findall('tag')}
        refs = [e.get('ref') for e in way.findall('nd')]
        if len(refs) < 2 or any(r not in nodes for r in refs):
            continue
        points = [nodes[r] for r in refs]
        if max(x for x, _ in points) < 0 or min(x for x, _ in points) > SIZE or max(y for _, y in points) < 0 or min(y for _, y in points) > SIZE:
            continue
        if refs[0] == refs[-1] and len(refs) >= 4:
            kind = None
            if tags.get('natural') == 'water' or tags.get('waterway') == 'riverbank': kind = 'water'
            elif tags.get('landuse') in ('grass', 'forest', 'recreation_ground', 'farmland') or tags.get('leisure') in ('park', 'pitch'): kind = 'green'
            elif tags.get('building') not in (None, 'no'): kind = 'building'
            elif tags.get('landuse'): kind = 'landuse'
            if kind:
                areas.append((kind, points)); counts[kind] = counts.get(kind, 0) + 1
        if tags.get('highway') and tags.get('area') != 'yes':
            roads.append((tags['highway'], points)); counts['road'] = counts.get('road', 0) + 1
        if tags.get('waterway') in ('river', 'stream', 'canal', 'drain'):
            roads.append(('water', points)); counts['water_line'] = counts.get('water_line', 0) + 1
    image = Image.new('RGB', (SIZE, SIZE), '#eeeade')
    draw = ImageDraw.Draw(image)
    colors = {'landuse': '#e4dfd3', 'green': '#c5d9ba', 'water': '#a9d5e8', 'building': '#c4b6aa'}
    for kind in colors:
        for feature, points in areas:
            if feature == kind: draw.polygon(points, fill=colors[kind], outline='#aa9f93' if kind == 'building' else colors[kind])
    for kind, points in roads:
        if kind == 'water': draw.line(points, fill='#85bdd5', width=5)
        else:
            width = 12 if kind in ('primary', 'secondary', 'tertiary') else 7 if kind in ('residential', 'unclassified', 'service') else 3
            draw.line(points, fill='#c5b89b', width=width + 2, joint='curve')
            draw.line(points, fill='#fff9e6', width=width, joint='curve')
    # Attribution is applied by the capture renderer so every exported crop retains it.
    levels = [image]
    for _ in range(3): levels.append(levels[-1].resize((levels[-1].width // 2, levels[-1].height // 2), Image.Resampling.LANCZOS))
    encoded = {'png': [], 'webp': []}
    timings = {}
    for codec in encoded:
        start = time.monotonic()
        for level, img in enumerate(levels):
            for row in range(img.height // TILE):
                for col in range(img.width // TILE):
                    tile = img.crop((col*TILE, row*TILE, (col+1)*TILE, (row+1)*TILE))
                    b = io.BytesIO()
                    tile.save(b, format=codec.upper(), **({'lossless': True, 'method': 4} if codec == 'webp' else {'optimize': True}))
                    data = b.getvalue()
                    with Image.open(io.BytesIO(data)) as decoded:
                        assert decoded.convert('RGB').tobytes() == tile.tobytes()
                    encoded[codec].append((level, col, row, data))
        timings[codec] = round(time.monotonic() - start, 3)
    sizes = {codec: sum(len(t[3]) for t in tiles) for codec, tiles in encoded.items()}
    codec = min(sizes, key=sizes.get)
    metadata = dict(schema=1, id='dandora-osm-v1', name='Dandora · OSM training map', frame='mavlab-local-grid-v1',
                    origin_lat=LAT, origin_lon=LON, tile_size=TILE, width=SIZE, height=SIZE, meters_per_pixel=RESOLUTION,
                    west=-512.0, north=512.0, valid_west=-448.0, valid_east=448.0, valid_south=-448.0, valid_north=448.0,
                    levels=4, codec=codec, attribution=ATTRIBUTION, license='ODbL-1.0',
                    copyright_url='https://www.openstreetmap.org/copyright', source='OpenStreetMap geometry',
                    source_sha256=provenance['sanitized_xml_sha256'], source_date=provenance['retrieved_utc'],
                    style='MAVLab simple-way style v1; closed ways and linear roads/waterways; no labels or relation fill',
                    ground='flat simulated ground; no surveyed elevation', simulation_altitude_msl=1805.0)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    stage = output.with_suffix('.tmp')
    stage.unlink(missing_ok=True)
    with sqlite3.connect(stage) as db:
        db.executescript('PRAGMA page_size=4096; PRAGMA user_version=1; CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL); CREATE TABLE tiles(level INTEGER NOT NULL, col INTEGER NOT NULL, row INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(level,col,row)) WITHOUT ROWID;')
        db.executemany('INSERT INTO metadata VALUES(?,?)', [(k, json.dumps(v, ensure_ascii=False)) for k,v in sorted(metadata.items())])
        db.executemany('INSERT INTO tiles VALUES(?,?,?,?)', encoded[codec])
        assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
    stage.replace(output)
    report = dict(codec_bytes=sizes, encoding_seconds=timings, tile_count=len(encoded[codec]), selected_codec=codec,
                  byte_identical_decoded=True, pack_bytes=output.stat().st_size, pack_sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
                  features=counts, source=provenance, metadata=metadata)
    output.with_suffix('.json').write_text(json.dumps(report, indent=2, ensure_ascii=False)+'\n')
    if diagnostic_dir:
        diagnostic_dir = Path(diagnostic_dir); diagnostic_dir.mkdir(parents=True, exist_ok=True)
        image.resize((1024,1024)).save(diagnostic_dir/'overview.png')
    return report

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT.parent.parent/'mavlab-android/app/src/main/assets/maps/dandora.mavmap')
    parser.add_argument('--diagnostic-dir', type=Path)
    args = parser.parse_args()
    report = prepare(args.output, args.diagnostic_dir)
    print(json.dumps({k:report[k] for k in ('codec_bytes','encoding_seconds','tile_count','selected_codec','pack_bytes','pack_sha256','features')},indent=2))

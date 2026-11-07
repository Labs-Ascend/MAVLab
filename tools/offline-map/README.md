# Offline map preparation

This host-only tool creates MAVLab's small bundled OSM **training map**, not aerial
imagery. The Android app needs neither Python nor a network connection.

```sh
python3 -m pip install Pillow==10.2.0
python3 tools/offline-map/prepare.py --diagnostic-dir /tmp/mavlab-map-preview
```

Use the Pillow version recorded in the generated receipt when comparing exact
encoded bytes. The pinned, compressed XML is 318 kB and is the reproducible input;
regeneration never downloads anything. `source/provenance.json` records the bounded
one-time source URL, retrieval time, hashes and metadata removal. New regions
should use permitted read-only extracts/Overpass, respecting that provider's
limits. Do not download standard OSM tiles for offline packs or routinely use the
editing API as a map download service.

The renderer uses complete closed ways for buildings, water, simple land use and
linear roads/waterways. It deliberately omits labels, relation fills and other
OSM semantics. This map teaches capture position and overlap; it is not a general
OSM renderer, survey basemap, current navigation map or photogrammetry dataset.
Coverage is a rectangular 896 × 896 m area around -1.2425, 36.9225, with a 64 m
raster margin. Ground elevation is simulated, not sourced from OSM.

The output is a custom, read-only SQLite `.mavmap` file, **not MBTiles**. Metadata
values are JSON scalars in `metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)`;
tiles are `tiles(level,col,row,data BLOB, PRIMARY KEY(level,col,row)) WITHOUT ROWID`.
`PRAGMA user_version=1`. Tiles are 256 pixels, north-up; level 0 has 0.5 m/pixel,
then resolution doubles. The base grid is 2048 × 2048; levels 0–3 total 85 tiles.
Tile edges are `(west + col*256*resolution, north - row*256*resolution)` in
`mavlab-local-grid-v1`, fixed at the metadata origin. There is no geodesic/UTM
conversion in this pack. A phone capture requires matching simulation origin.

Preparation compares PNG and lossless WebP on every tile and verifies byte-for-byte
equality of decoded RGB pixels against its rendered tile. The generated JSON
receipt records size, codec, features, source, transform and pack hash. Runtime
uses a 4 MiB decoded cache and draws tiles directly into one 640 × 480 bitmap.
PNG/WebP compression ratios here do not predict JPEG capture size.

Map data © OpenStreetMap contributors, available under [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
[Copyright and attribution](https://www.openstreetmap.org/copyright).
The sanitized source snapshot is supplied here for reuse under that licence;
MAVLab's simple rendering style is project code. Each exported image contains
visible attribution, and its session manifest retains provenance. No standard
OSM tile-server images, commercial aerial imagery or proprietary map styles are
included.

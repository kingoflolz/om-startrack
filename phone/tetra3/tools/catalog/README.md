# tetra3 catalog pipeline

These dependency-free Python tools build the stellar input for the host-side
tetra3 index builder. They deliberately do not run on Android.

The default catalog is Gaia DR3 through `G <= 12`, supplemented with bright
Hipparcos-2 stars that Gaia does not contain. Gaia positions are propagated
from J2016.0 and Hipparcos positions from J1991.25 to a common configurable
index epoch. Every network artifact and generated artifact gets a SHA-256
manifest; gzip headers and numeric serialization are deterministic.

## Production build

Run from the tetra3 workspace (`phone/tetra3/` from the repository root).
The example keeps downloaded and derived data outside the source tree:

```sh
python3 tools/catalog/fetch_gaia.py \
  --output-dir /path/to/tetra3-catalog/gaia \
  --magnitude-limit 12 \
  --chunk-order 1

python3 tools/catalog/fetch_hipparcos.py \
  --output-dir /path/to/tetra3-catalog/hipparcos

python3 tools/catalog/merge_catalogs.py \
  --gaia-dir /path/to/tetra3-catalog/gaia \
  --hipparcos-dir /path/to/tetra3-catalog/hipparcos \
  --target-epoch 2026 \
  --hipparcos-hp-limit 8 \
  --output /path/to/tetra3-catalog/gaia-dr3-g12-hip2.csv.gz

python3 tools/catalog/validate_coverage.py \
  --catalog /path/to/tetra3-catalog/gaia-dr3-g12-hip2.csv.gz \
  --horizontal-fov 3 \
  --aspect 1.3333333333333333 \
  --magnitude-limits 8,9,10,11,12 \
  --centers 49152 \
  --roll-step 30 \
  --minimum-stars 8 \
  --pattern-anchors-only \
  --output /path/to/tetra3-catalog/coverage-3deg.json

cargo run --release -p tetra3-cli -- catalog convert \
  --input /path/to/tetra3-catalog/gaia-dr3-g12-hip2.csv.gz \
  --output /path/to/tetra3-catalog/gaia-dr3-g12-hip2-2026.t3cat \
  --epoch 2026 \
  --max-magnitude 12

cargo run --release -p tetra3-cli -- build \
  --catalog /path/to/tetra3-catalog/gaia-dr3-g12-hip2-2026.t3cat \
  --output /path/to/tetra3-catalog/om1-300f4-to-45.t3ix \
  --min-fov-deg 3.2 \
  --max-fov-deg 45 \
  --levels 8 \
  --aspect-ratio 1.3333333333333333 \
  --pattern-rolls-per-field 8 \
  --pattern-stars-per-field 12 \
  --patterns-per-field 64 \
  --target-verification-stars-per-field 128 \
  --resume

cargo run --release -p tetra3-cli -- verify \
  /path/to/tetra3-catalog/om1-300f4-to-45.t3ix
```

The converter recognizes gzip by magic bytes, preserves the Gaia/Hipparcos ID
namespaces, honors each row's epoch, and carries `pattern_anchor=0` into the
compact catalog. The index builder excludes those rows from patterns while
retaining them as verification stars. `build --resume` accepts an existing
index only when the index, catalog hash, manifest, and output-affecting build
recipe all match. The execution-only thread setting may differ.

Rerunning either fetch command is safe. Completed Gaia chunks are accepted
only when their configuration and compressed-file hash match their sidecar.
A partial fixed-file download resumes with HTTP Range. Writes use a temporary
file followed by an atomic rename.

`fetch_gaia.py --plan` prints all queries without network access. A one-row
live plumbing probe is:

```sh
python3 tools/catalog/fetch_gaia.py \
  --output-dir /tmp/tetra3-gaia-probe \
  --chunk-order 12 \
  --chunks 222
```

Do not use that tiny shard as an index catalog. `merge_catalogs.py` rejects an
incomplete Gaia manifest unless `--allow-partial` is explicitly supplied.

## Why these sources and partitions

The default TAP service is ARI, one of Gaia's official partner data centers.
It provides anonymous CSV results, advertises a ten-million-row hard result
limit, and recommends `gaiadr3.gaia_source_lite` for DR3. No credentials are
required. `--endpoint` may select another TAP `sync` endpoint that honors
`FORMAT=csv`; some services, notably the tested Gaia@AIP sync endpoint, return
VOTable regardless of that request and are not drop-in replacements.

Gaia embeds a level-12 NESTED HEALPix in the high bits of `source_id`.
`--chunk-order 1` therefore makes 48 indexed integer ranges, averaging about
64,000 of the roughly 3.09 million `G <= 12` sources each. This is a compromise
between restart granularity and being considerate of the public service. Order
2 makes 192 smaller chunks if the network is unreliable. The downloader sends
only one query at a time, retries transient failures with bounded backoff, and
does not require a server-side user workspace.

Hipparcos-2 is the CDS/VizieR I/311 corrected catalog. De-duplication uses
Gaia's published `hipparcos2_best_neighbour` product, not a locally chosen
angular radius. An Hipparcos entry is suppressed only if that mapped Gaia
source is present in the downloaded magnitude slice. Accepted supplement
positions must have finite astrometry and an ordinary five-parameter solution;
`Hp` remains tagged as `Hp` and is never mislabeled as Gaia `G`.

## Canonical CSV

The merged header is:

```text
catalog,source_id,ra_deg,dec_deg,ref_epoch_jyear,pmra_masyr,pmdec_masyr,parallax_mas,magnitude,magnitude_band,color,flags
```

`source_id` remains the original unsigned Gaia source ID or positive HIP
number. The `catalog` column provides the namespace. Rows with both proper
motions are propagated to the requested epoch in Cartesian unit-vector space.
A Gaia row lacking proper motion retains its J2016.0 position and per-row epoch
instead of pretending that it was propagated. Parallax is retained for
provenance but is not applied to the fixed celestial direction.

The `flags` field is a stable semicolon-separated list of source diagnostics.
The Rust importer may ignore unknown keys. Hipparcos `pattern_anchor` is zero
for a source that is safe to retain for verification but should not seed an
index pattern. Gaia sources without a propagatable five- or six-parameter
astrometric solution are likewise verification-only.

## Coverage report

`validate_coverage.py` tests exact gnomonic rectangles at deterministic
Fibonacci-distributed centers and multiple roll angles. It reports the minimum,
low percentiles, mean, number of fields below the requested star count, and the
worst sampled pointings at each magnitude limit. It is a reproducible stress
sample rather than a proof over the continuous pointing sphere. Increase
`--centers` or reduce `--roll-step` before accepting a production index.
`--pattern-anchors-only` excludes sources explicitly retained for verification
but forbidden from pattern construction. Magnitude thresholds use native Gaia
G or Hipparcos Hp as a ranking proxy; they are not a cross-band calibration.

The validator uses only the Python standard library. Its sky grid stores star
vectors in packed arrays, so the full `G <= 12` run remains practical without
Astropy or HEALPix packages.

## Expected resources

The observed sample shard used about 151 bytes per Gaia row as CSV and 68 bytes
per row compressed. For the all-sky slice, budget approximately:

- 190–240 MiB for compressed Gaia query shards (about 470 MiB inflated),
- 11 MiB for Hipparcos-2 plus the best-neighbour table,
- 220–350 MiB for the canonical compressed CSV, depending on flags,
- at least 1 GiB of free workspace, plus the Rust builder's documented memory
  budget.

Public-service load dominates runtime. A typical host should expect roughly
10–40 minutes for the Gaia download, 1–5 minutes for canonicalization, and
several to tens of minutes for the 49,152-center coverage sweep. Busy mirrors
can take longer; the correct response is to rerun and resume, not increase
parallelism.

## Tests

```sh
python3 -m unittest discover -s tools/catalog -p 'test_*.py' -v
```

The test suite is offline and covers source-ID partitioning, deterministic
gzip, spherical proper-motion propagation, Hipparcos fixed-width conversion,
Gaia null-motion semantics, and rectangular FOV counting.

See [NOTICE.md](NOTICE.md) before redistributing any downloaded or generated
catalog artifact.

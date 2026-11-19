# Lens profile recipes

These files are exact, reviewable selections for the host-side Lensfun XML
compiler. They contain camera geometry and canonical names only; generated
`.t3lf` files remain build artifacts.

`om1-all-mft.json` compiles every Lensfun entry with the exact native mount
`Micro 4/3 System` that is compatible with the canonical
`OM Digital Solutions / OM-1` camera. It reads the complete selected database,
not only the OM System source file. The recipe intentionally leaves the
calibration crop factor and aspect ratio unset: those describe the source
calibration and may legitimately differ from the OM-1 raster. If duplicate
maker/model records cannot be resolved by the exact OM-1 crop/aspect geometry,
they are reported as ambiguous and omitted rather than fuzzy-picked.

Bulk profile IDs have the deterministic form
`PREFIX|lens=MAKER_BYTE_LENGTH:MAKERMODEL_BYTE_LENGTH:MODEL`. Maker and model
are the canonical, non-localized Lensfun strings and remain case-sensitive.
The host can call `compatible_profile_id` with those exact strings and then use
the runtime database's exact lookup; there is no normalization or fuzzy match.

`om1-12-45.json` maps the OM-1 active 5184×3888 raster to the 1296×972
centroid plane used by the camera star detector. It selects the canonical
Lensfun v2 entries `OM Digital Solutions / OM-1` and
`OM Digital Solutions / OM 12-45mm F4.0` without fuzzy matching.

The database source and license are recorded in `lensfun-source.lock`. From the tetra3
workspace (`phone/tetra3/` from the repository root), with that Lensfun
checkout in place, compile the complete MFT set:

```sh
cargo run --release -p tetra3-cli -- lensfun compile \
  --xml build/lensfun-source/data/db \
  --profiles profiles/om1-all-mft.json \
  --output build/profiles/om1-all-mft.t3lf \
  --report build/profiles/om1-all-mft.manifest.json \
  --json
```

The `--xml` argument accepts a single XML file or a directory. Directory
members are restricted to immediate `*.xml` files and sorted lexically, so the
compiled bytes do not depend on filesystem enumeration order. The manifest
lists exact included IDs, ambiguous/unmatched skips, and entries with no
distortion calibration. It also records the output, recipe, complete source-set,
and per-document SHA-256 digests plus the pinned source declaration.

In the bundled profile data, Lensfun does not contain the Olympus M.Zuiko Digital
ED 300mm f/4.0 IS PRO. That exact identity is audited by the recipe and reported
as `absent_from_selected_lens_set`. Runtime code must not substitute the
75–300mm zoom, a Panasonic 100–300mm profile, or any other nominally similar
lens. After exact lookup reports that ID absent, the runtime explicitly builds
`PreparedLens::rectilinear_no_distortion` from the captured STAR
focus-breathed `FLact` value and native detector pixel pitch, never from the
IBIS `StartCorr opt_param`. An explicitly measured calibration may replace
that assumption later. Ambiguous matching and profile parse/model failures
remain errors and do not take this fallback.

The single-lens test recipe remains useful as a focused fixture:

```sh
cargo run --release -p tetra3-cli -- lensfun compile \
  --xml build/lensfun-source/data/db/om-system.xml \
  --profiles profiles/om1-12-45.json \
  --output build/profiles/om1-12-45.t3lf
```

The Lensfun database is CC BY-SA 3.0. Keep the tracked
[`LENSFUN-DATABASE-NOTICE.md`](LENSFUN-DATABASE-NOTICE.md), source lock, and
license reference alongside any redistributed compiled profile database.

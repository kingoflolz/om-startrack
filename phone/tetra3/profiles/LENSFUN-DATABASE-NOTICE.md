# Lensfun database notice

Compiled `.t3lf` files produced from Lensfun XML contain selected and
reformatted Lensfun calibration data. They are data artifacts with terms
separate from the Apache-2.0-licensed tetra3 code.

## Source and attribution

- Original work: Lensfun camera and lens database, by the Lensfun project and
  its database contributors.
- Source repository: <https://github.com/lensfun/lensfun>
- Source and license declaration: [`lensfun-source.lock`](lensfun-source.lock).
- Source set: the selected checkout's XML documents under `data/db`.
- Camera record: `OM Digital Solutions / OM-1`.
- Lens selection: canonical records declaring the exact native mount
  `Micro 4/3 System`, with the included/skipped/no-distortion identities listed
  in the generated `om1-all-mft.manifest.json`.
- Focused source/recipe: `data/db/om-system.xml`, selecting
  `OM Digital Solutions / OM 12-45mm F4.0`.

The selected database does not contain a calibration record for the Olympus
M.Zuiko Digital ED 300mm f/4.0 IS PRO. The bulk recipe audits that exact lens as
absent. A redistributed application must not relabel another 300mm lens profile
as the 300mm f/4 PRO.

The tetra3 host compiler changes the source data's format and selection. It
extracts exact canonical identities, selected camera geometry, projection,
optical center, and distortion knots, resolves the documented real-focal
rules, and writes them into the compact binary `.t3lf` format. This notice does
not imply endorsement by Lensfun or its contributors.

## License

The Lensfun database is licensed under the Creative Commons
Attribution-ShareAlike 3.0 Unported license (CC BY-SA 3.0):

- Canonical license deed: <https://creativecommons.org/licenses/by-sa/3.0/>
- Legal code: <https://creativecommons.org/licenses/by-sa/3.0/legalcode>
- License text in the source tree:
  `data/COPYING.CC_BY-SA_3.0`

When redistributing a compiled `.t3lf`, keep this notice (or equivalent source,
attribution, modification, and license information) with the artifact. Make
the adapted calibration data available under CC BY-SA 3.0 and do not apply
additional legal or technical restrictions that conflict with that license.

The Lensfun software library is not linked, vendored, or required by the
tetra3 runtime. This notice covers the calibration database data compiled into
`.t3lf`, not tetra3's independently implemented distortion code.

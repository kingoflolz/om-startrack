# Catalog data notices and provenance

The scripts in this directory are project code. Downloaded catalogs and files
derived from them have separate terms and attribution requirements.

## Gaia DR3

- Dataset: Gaia Data Release 3, `gaiadr3.gaia_source_lite`
- DOI: <https://doi.org/10.17876/gaia/dr.3/1>
- Access service: ARI Gaia TAP, <https://gaia.ari.uni-heidelberg.de/tap.html>
- Official data-access page identifying ARI as a Gaia partner data center:
  <https://www.cosmos.esa.int/web/gaia/data-access>
- Metadata and license statement:
  <https://gaia.aip.de/metadata/gaiadr3/gaia_source_lite/>
- License shown by the provider: Creative Commons Attribution-NonCommercial
  3.0 IGO (CC BY-NC 3.0 IGO)

Credit ESA's Gaia mission and the Gaia Data Processing and Analysis Consortium
(DPAC), including its participating national institutions and funders. Use the
current official wording from the linked Gaia metadata/credits page when
publishing or redistributing a generated catalog.

## Hipparcos-2

- Dataset: Hipparcos, the New Reduction, CDS/VizieR I/311
- Catalog: <https://cdsarc.cds.unistra.fr/viz-bin/cat/I/311>
- Machine-readable schema: <https://cdsarc.cds.unistra.fr/ftp/I/311/ReadMe>
- Data file: <https://cdsarc.cds.unistra.fr/ftp/I/311/hip2.dat.gz>
- Hipparcos terms/catalog page:
  <https://www.cosmos.esa.int/web/hipparcos/catalogues>
- License shown by ESA: CC BY-NC 3.0 IGO
- Required catalog credit: Credit: ESA

The pipeline uses the corrected I/311 file. Generated manifests record the
actual input content digests.

## Gaia DR3 Hipparcos-2 best-neighbour cross-match

- Product:
  <https://cdn.gea.esac.esa.int/Gaia/gedr3/cross_match/hipparcos2_best_neighbour/Hipparcos2BestNeighbour.csv.gz>
- DOI: <https://doi.org/10.17876/gaia/dr.3/14>
- Metadata:
  <https://gaia.aip.de/metadata/gaiadr3/hipparcos2_best_neighbour/>
- License shown by the provider: CC BY-NC 3.0 IGO

The `gedr3` bulk-download path is intentional: DR3 uses EDR3 astrometry and
publishes this table as `gaiadr3.hipparcos2_best_neighbour`.

This notice is informational and is not legal advice. Keep the generated JSON
manifests with every catalog/index build; they contain the exact source URLs,
queries, row counts, and content hashes.

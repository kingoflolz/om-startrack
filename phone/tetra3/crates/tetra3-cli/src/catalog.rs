use std::cmp::Ordering;
use std::collections::{BTreeMap, HashMap};
use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom, Write};
use std::path::Path;

use csv::{ReaderBuilder, StringRecord};
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::geometry::{Vec3, decode_octahedral, encode_octahedral, propagate_epoch};
use crate::io_utils::write_file_atomic;
use crate::{Error, Result};

pub const CATALOG_MAGIC: [u8; 8] = *b"T3CAT001";
pub const CATALOG_VERSION: u16 = 1;
pub const CATALOG_HEADER_LEN: usize = 128;
pub const CATALOG_RECORD_LEN: usize = 17;

pub const STAR_FLAG_PATTERN_ELIGIBLE: u8 = 1 << 0;
pub const STAR_FLAG_HIPPARCOS: u8 = 1 << 1;
const KNOWN_STAR_FLAGS: u8 = STAR_FLAG_PATTERN_ELIGIBLE | STAR_FLAG_HIPPARCOS;

#[derive(Clone, Copy, Debug)]
pub struct CatalogStar {
    pub source_id: u64,
    pub vector: Vec3,
    pub magnitude: f32,
    pub flags: u8,
}

#[derive(Clone, Debug)]
pub struct ConvertOptions {
    pub input_epoch: f64,
    pub output_epoch: f64,
    pub min_magnitude: f32,
    pub max_magnitude: f32,
    pub duplicate_radius_arcsec: f64,
    pub close_pair_radius_arcsec: f64,
    pub strict: bool,
}

impl Default for ConvertOptions {
    fn default() -> Self {
        Self {
            input_epoch: 2016.0,
            output_epoch: 2026.0,
            min_magnitude: -5.0,
            max_magnitude: 12.0,
            duplicate_radius_arcsec: 0.5,
            close_pair_radius_arcsec: 15.0,
            strict: true,
        }
    }
}

#[derive(Clone, Debug, Default, Serialize)]
pub struct ConvertStats {
    pub rows_read: u64,
    pub rows_invalid: u64,
    pub rows_outside_magnitude_limit: u64,
    pub duplicate_source_ids: u64,
    pub spatial_duplicates: u64,
    pub close_pair_stars: u64,
    pub stars_written: u64,
}

#[derive(Clone, Debug, Serialize)]
pub struct CatalogInfo {
    pub version: u16,
    pub records: u64,
    pub epoch: f64,
    pub min_magnitude: f32,
    pub max_magnitude: f32,
    pub duplicate_radius_arcsec: f32,
    pub close_pair_radius_arcsec: f32,
    pub rows_invalid: u64,
    pub rows_removed: u64,
    #[serde(skip)]
    pub records_sha256: [u8; 32],
}

#[derive(Debug)]
pub struct CompactCatalog {
    pub info: CatalogInfo,
    pub stars: Vec<CatalogStar>,
}

impl CompactCatalog {
    pub fn read(path: impl AsRef<Path>, verify_checksum: bool) -> Result<Self> {
        let path = path.as_ref();
        let file = File::open(path).map_err(|error| Error::io(path, error))?;
        let length = file
            .metadata()
            .map_err(|error| Error::io(path, error))?
            .len();
        let mut reader = BufReader::new(file);
        let mut header = [0_u8; CATALOG_HEADER_LEN];
        reader
            .read_exact(&mut header)
            .map_err(|error| Error::io(path, error))?;
        let info = decode_header(&header)?;
        let expected_length = CATALOG_HEADER_LEN as u64
            + info
                .records
                .checked_mul(CATALOG_RECORD_LEN as u64)
                .ok_or_else(|| {
                    Error::InvalidCatalog("record count overflows the file length".into())
                })?;
        if length != expected_length {
            return Err(Error::InvalidCatalog(format!(
                "file is {length} bytes; header requires exactly {expected_length} bytes"
            )));
        }
        let capacity = usize::try_from(info.records)
            .map_err(|_| Error::InvalidCatalog("catalog does not fit this host".into()))?;
        let mut stars = Vec::with_capacity(capacity);
        let mut digest = Sha256::new();
        let mut record = [0_u8; CATALOG_RECORD_LEN];
        for _ in 0..info.records {
            reader
                .read_exact(&mut record)
                .map_err(|error| Error::io(path, error))?;
            if verify_checksum {
                digest.update(record);
            }
            let star = decode_record(record);
            if star.flags & !KNOWN_STAR_FLAGS != 0 {
                return Err(Error::InvalidCatalog(
                    "record contains unknown star flags".into(),
                ));
            }
            let namespaced_hip = star.source_id & (1_u64 << 63) != 0;
            if namespaced_hip != (star.flags & STAR_FLAG_HIPPARCOS != 0) {
                return Err(Error::InvalidCatalog(
                    "record source namespace and Hipparcos flag disagree".into(),
                ));
            }
            stars.push(star);
        }
        if verify_checksum {
            let actual: [u8; 32] = digest.finalize().into();
            if actual != info.records_sha256 {
                return Err(Error::InvalidCatalog(format!(
                    "record SHA-256 mismatch: expected {}, got {}",
                    hex(&info.records_sha256),
                    hex(&actual)
                )));
            }
        }
        Ok(Self { info, stars })
    }
}

pub fn convert_gaia_csv(
    input: impl AsRef<Path>,
    output: impl AsRef<Path>,
    options: &ConvertOptions,
) -> Result<ConvertStats> {
    validate_options(options)?;
    let input = input.as_ref();
    let output = output.as_ref();
    let mut file = File::open(input).map_err(|error| Error::io(input, error))?;
    let mut magic = [0_u8; 2];
    let magic_length = file
        .read(&mut magic)
        .map_err(|error| Error::io(input, error))?;
    file.seek(SeekFrom::Start(0))
        .map_err(|error| Error::io(input, error))?;
    let source: Box<dyn Read> = if magic_length == 2 && magic == [0x1f, 0x8b] {
        Box::new(flate2::read::MultiGzDecoder::new(file))
    } else {
        Box::new(file)
    };
    let mut reader = ReaderBuilder::new().flexible(true).from_reader(source);
    let headers = reader
        .headers()
        .map_err(|error| map_csv_error(input, 1, error))?
        .clone();
    if headers.is_empty() {
        return Err(Error::InvalidCatalog("CSV is empty".into()));
    }
    let columns = GaiaColumns::new(&headers)?;
    let mut stats = ConvertStats::default();
    let mut stars = Vec::new();
    let mut record = StringRecord::new();
    loop {
        match reader.read_record(&mut record) {
            Ok(false) => break,
            Ok(true) => {}
            Err(error) => {
                let row = error
                    .position()
                    .map_or_else(|| reader.position().line(), csv::Position::line);
                if error.is_io_error() {
                    return Err(map_csv_error(input, row, error));
                }
                stats.rows_read += 1;
                if options.strict {
                    return Err(map_csv_error(input, row, error));
                }
                stats.rows_invalid += 1;
                continue;
            }
        }
        if record.is_empty()
            || (record.len() == 1 && record.get(0).is_some_and(|field| field.trim().is_empty()))
        {
            continue;
        }
        let row = record.position().map_or(1, csv::Position::line);
        stats.rows_read += 1;
        let parsed = parse_gaia_row(&record, row, &columns, options);
        match parsed {
            Ok(Some(star)) => stars.push(star),
            Ok(None) => stats.rows_outside_magnitude_limit += 1,
            Err(_error) if !options.strict => stats.rows_invalid += 1,
            Err(error) => return Err(error),
        }
    }

    stars = deduplicate_source_ids(stars, &mut stats);
    stars = spatially_deduplicate(stars, options.duplicate_radius_arcsec, &mut stats);
    stars = reject_close_pairs(stars, options.close_pair_radius_arcsec, &mut stats);
    stars.sort_by(star_brightness_order);
    stats.stars_written = stars.len() as u64;
    write_compact_catalog(output, options, &stats, &stars)?;
    Ok(stats)
}

fn validate_options(options: &ConvertOptions) -> Result<()> {
    if !options.input_epoch.is_finite() || !options.output_epoch.is_finite() {
        return Err(Error::InvalidArgument(
            "catalog epochs must be finite".into(),
        ));
    }
    if !options.min_magnitude.is_finite()
        || !options.max_magnitude.is_finite()
        || options.min_magnitude > options.max_magnitude
        || options.min_magnitude < f32::from(i16::MIN) / 1_000.0
        || options.max_magnitude > f32::from(i16::MAX) / 1_000.0
    {
        return Err(Error::InvalidArgument(
            "magnitude range must be finite, non-empty, and fit signed millimagnitudes".into(),
        ));
    }
    if !(0.0..=3_600.0).contains(&options.duplicate_radius_arcsec)
        || !(0.0..=3_600.0).contains(&options.close_pair_radius_arcsec)
        || options.duplicate_radius_arcsec > options.close_pair_radius_arcsec
        || (options.duplicate_radius_arcsec > 0.0 && options.duplicate_radius_arcsec < 0.01)
        || (options.close_pair_radius_arcsec > 0.0 && options.close_pair_radius_arcsec < 0.01)
    {
        return Err(Error::InvalidArgument(
            "radii must be zero or at least 0.01 arcsec and satisfy duplicate <= close-pair <= 3600 arcsec".into(),
        ));
    }
    Ok(())
}

fn parse_gaia_row(
    fields: &StringRecord,
    row: u64,
    columns: &GaiaColumns,
    options: &ConvertOptions,
) -> Result<Option<CatalogStar>> {
    let field = |index: usize, name: &str| -> Result<&str> {
        fields.get(index).ok_or_else(|| Error::Csv {
            row,
            message: format!("missing field {name}"),
        })
    };
    let parse = |index: usize, name: &str| -> Result<f64> {
        field(index, name)?
            .trim()
            .parse::<f64>()
            .map_err(|_| Error::Csv {
                row,
                message: format!("invalid {name}"),
            })
    };
    let mut source_id = field(columns.source_id, "source_id")?
        .trim()
        .parse::<u64>()
        .map_err(|_| Error::Csv {
            row,
            message: "invalid source_id".into(),
        })?;
    if source_id == 0 {
        return Err(Error::Csv {
            row,
            message: "source_id must be nonzero".into(),
        });
    }
    let mut flags = STAR_FLAG_PATTERN_ELIGIBLE;
    if let Some(catalog_column) = columns.catalog {
        let catalog = field(catalog_column, "catalog")?
            .trim()
            .to_ascii_lowercase();
        match catalog.as_str() {
            "gaia" | "gaia_dr3" | "gaiadr3" => {
                if source_id & (1_u64 << 63) != 0 {
                    return Err(Error::Csv {
                        row,
                        message: "Gaia source_id uses the reserved namespace bit".into(),
                    });
                }
            }
            "hip" | "hipparcos" => {
                source_id |= 1_u64 << 63;
                flags |= STAR_FLAG_HIPPARCOS;
            }
            _ => {
                return Err(Error::Csv {
                    row,
                    message: format!("unsupported catalog namespace {catalog:?}"),
                });
            }
        }
    } else if source_id & (1_u64 << 63) != 0 {
        return Err(Error::Csv {
            row,
            message: "native Gaia source_id uses the reserved namespace bit".into(),
        });
    }
    if let Some(flags_column) = columns.flags {
        let canonical_flags = field(flags_column, "flags")?.trim();
        for item in canonical_flags.split(';').map(str::trim) {
            if item.eq_ignore_ascii_case("pattern_anchor=0") {
                flags &= !STAR_FLAG_PATTERN_ELIGIBLE;
            } else if item.eq_ignore_ascii_case("pattern_anchor=1") {
                flags |= STAR_FLAG_PATTERN_ELIGIBLE;
            }
        }
    }
    let ra = parse(columns.ra, "ra")?;
    let dec = parse(columns.dec, "dec")?;
    let magnitude = parse(columns.magnitude, "phot_g_mean_mag")?;
    let parse_optional = |index: Option<usize>, name: &str| -> Result<f64> {
        let Some(index) = index else { return Ok(0.0) };
        let value = field(index, name)?.trim();
        if value.is_empty()
            || value.eq_ignore_ascii_case("null")
            || value.eq_ignore_ascii_case("nan")
        {
            return Ok(0.0);
        }
        value.parse::<f64>().map_err(|_| Error::Csv {
            row,
            message: format!("invalid {name}"),
        })
    };
    let pmra = parse_optional(columns.pmra, "pmra")?;
    let pmdec = parse_optional(columns.pmdec, "pmdec")?;
    if !ra.is_finite()
        || !(0.0..360.0).contains(&ra)
        || !dec.is_finite()
        || !(-90.0..=90.0).contains(&dec)
        || !magnitude.is_finite()
        || !pmra.is_finite()
        || !pmdec.is_finite()
    {
        return Err(Error::Csv {
            row,
            message: "non-finite or out-of-range Gaia value".into(),
        });
    }
    if magnitude < f64::from(options.min_magnitude) || magnitude > f64::from(options.max_magnitude)
    {
        return Ok(None);
    }
    let vector = propagate_epoch(
        ra,
        dec,
        pmra,
        pmdec,
        options.output_epoch - parse_row_epoch(fields, columns, row, options.input_epoch)?,
    );
    Ok(Some(CatalogStar {
        source_id,
        vector,
        // Quantize once, before any sorting/deduplication. The compact catalog
        // can then reconstruct exactly the ordering used by the converter.
        magnitude: ((magnitude * 1_000.0).round() / 1_000.0) as f32,
        flags,
    }))
}

#[derive(Debug)]
struct GaiaColumns {
    catalog: Option<usize>,
    source_id: usize,
    ra: usize,
    dec: usize,
    magnitude: usize,
    pmra: Option<usize>,
    pmdec: Option<usize>,
    row_epoch: Option<usize>,
    flags: Option<usize>,
}

impl GaiaColumns {
    fn new(headers: &StringRecord) -> Result<Self> {
        let mut by_name = BTreeMap::new();
        for (index, name) in headers.iter().enumerate() {
            let normalized = name
                .trim_start_matches('\u{feff}')
                .trim()
                .to_ascii_lowercase();
            if by_name.insert(normalized.clone(), index).is_some() {
                return Err(Error::InvalidCatalog(format!(
                    "CSV contains duplicate column {normalized}"
                )));
            }
        }
        let one_of = |names: &[&str]| names.iter().find_map(|name| by_name.get(*name).copied());
        let required = |names: &[&str]| {
            one_of(names).ok_or_else(|| {
                Error::InvalidCatalog(format!(
                    "CSV is missing required column {}",
                    names.join(" or ")
                ))
            })
        };
        Ok(Self {
            catalog: one_of(&["catalog"]),
            source_id: required(&["source_id"])?,
            ra: required(&["ra", "ra_deg"])?,
            dec: required(&["dec", "dec_deg"])?,
            magnitude: required(&["phot_g_mean_mag", "magnitude"])?,
            pmra: one_of(&["pmra", "pmra_masyr"]),
            pmdec: one_of(&["pmdec", "pmdec_masyr"]),
            row_epoch: one_of(&["ref_epoch_jyear"]),
            flags: one_of(&["flags"]),
        })
    }
}

fn parse_row_epoch(
    fields: &StringRecord,
    columns: &GaiaColumns,
    row: u64,
    fallback: f64,
) -> Result<f64> {
    let Some(index) = columns.row_epoch else {
        return Ok(fallback);
    };
    let value = fields.get(index).unwrap_or("").trim();
    if value.is_empty() {
        return Ok(fallback);
    }
    let value = value.parse::<f64>().map_err(|_| Error::Csv {
        row,
        message: "invalid ref_epoch_jyear".into(),
    })?;
    if !value.is_finite() {
        return Err(Error::Csv {
            row,
            message: "non-finite ref_epoch_jyear".into(),
        });
    }
    Ok(value)
}

fn map_csv_error(path: &Path, row: u64, error: csv::Error) -> Error {
    let message = error.to_string();
    match error.into_kind() {
        csv::ErrorKind::Io(source) => Error::io(path, source),
        _ => Error::Csv { row, message },
    }
}

fn deduplicate_source_ids(
    mut stars: Vec<CatalogStar>,
    stats: &mut ConvertStats,
) -> Vec<CatalogStar> {
    stars.sort_by(|a, b| {
        a.source_id
            .cmp(&b.source_id)
            .then_with(|| star_brightness_order(a, b))
    });
    let before = stars.len();
    stars.dedup_by_key(|star| star.source_id);
    stats.duplicate_source_ids += (before - stars.len()) as u64;
    stars
}

fn spatially_deduplicate(
    mut stars: Vec<CatalogStar>,
    radius_arcsec: f64,
    stats: &mut ConvertStats,
) -> Vec<CatalogStar> {
    if radius_arcsec == 0.0 {
        return stars;
    }
    stars.sort_by(star_brightness_order);
    let radius_rad = radius_arcsec.to_radians() / 3_600.0;
    let mut grid = ProximityGrid::new(radius_rad);
    let mut accepted: Vec<CatalogStar> = Vec::with_capacity(stars.len());
    for star in stars {
        if grid.has_neighbor(star.vector, radius_rad, &accepted) {
            stats.spatial_duplicates += 1;
        } else {
            grid.insert(star.vector, accepted.len());
            accepted.push(star);
        }
    }
    accepted
}

fn reject_close_pairs(
    stars: Vec<CatalogStar>,
    radius_arcsec: f64,
    stats: &mut ConvertStats,
) -> Vec<CatalogStar> {
    if radius_arcsec == 0.0 {
        return stars;
    }
    let radius_rad = radius_arcsec.to_radians() / 3_600.0;
    let mut rejected = vec![false; stars.len()];
    let mut grid = ProximityGrid::new(radius_rad);
    for (index, star) in stars.iter().enumerate() {
        for other in grid.neighbors(star.vector) {
            if star.vector.angle_to(stars[other].vector) <= radius_rad {
                rejected[index] = true;
                rejected[other] = true;
            }
        }
        grid.insert(star.vector, index);
    }
    let rejected_count = rejected.iter().filter(|&&value| value).count();
    stats.close_pair_stars += rejected_count as u64;
    stars
        .into_iter()
        .zip(rejected)
        .filter_map(|(star, rejected)| (!rejected).then_some(star))
        .collect()
}

struct ProximityGrid {
    cell_size: f64,
    cells: HashMap<(i32, i32, i32), Vec<usize>>,
}

impl ProximityGrid {
    fn new(radius_rad: f64) -> Self {
        // Chord distance is always <= angular distance. Using its exact value as
        // the cell edge ensures a neighbor can differ by at most one cell.
        let cell_size = (2.0 * (radius_rad * 0.5).sin()).max(1e-12);
        Self {
            cell_size,
            cells: HashMap::new(),
        }
    }

    fn key(&self, vector: Vec3) -> (i32, i32, i32) {
        let component = |value: f64| ((value + 1.0) / self.cell_size).floor() as i32;
        (
            component(vector.x),
            component(vector.y),
            component(vector.z),
        )
    }

    fn insert(&mut self, vector: Vec3, index: usize) {
        self.cells.entry(self.key(vector)).or_default().push(index);
    }

    fn neighbors(&self, vector: Vec3) -> Vec<usize> {
        let (x, y, z) = self.key(vector);
        let mut neighbors = Vec::new();
        for dz in -1..=1 {
            for dy in -1..=1 {
                for dx in -1..=1 {
                    if let Some(indices) = self.cells.get(&(x + dx, y + dy, z + dz)) {
                        neighbors.extend_from_slice(indices);
                    }
                }
            }
        }
        neighbors
    }

    fn has_neighbor(&self, vector: Vec3, radius: f64, stars: &[CatalogStar]) -> bool {
        self.neighbors(vector)
            .into_iter()
            .any(|index| vector.angle_to(stars[index].vector) <= radius)
    }
}

fn star_brightness_order(a: &CatalogStar, b: &CatalogStar) -> Ordering {
    a.magnitude
        .total_cmp(&b.magnitude)
        .then_with(|| a.source_id.cmp(&b.source_id))
        .then_with(|| a.vector.x.total_cmp(&b.vector.x))
        .then_with(|| a.vector.y.total_cmp(&b.vector.y))
        .then_with(|| a.vector.z.total_cmp(&b.vector.z))
}

fn write_compact_catalog(
    path: &Path,
    options: &ConvertOptions,
    stats: &ConvertStats,
    stars: &[CatalogStar],
) -> Result<()> {
    write_file_atomic(path, |file| {
        file.write_all(&[0_u8; CATALOG_HEADER_LEN])
            .map_err(|error| Error::io(path, error))?;
        let mut digest = Sha256::new();
        for star in stars {
            let record = encode_record(*star);
            digest.update(record);
            file.write_all(&record)
                .map_err(|error| Error::io(path, error))?;
        }
        let info = CatalogInfo {
            version: CATALOG_VERSION,
            records: stars.len() as u64,
            epoch: options.output_epoch,
            min_magnitude: options.min_magnitude,
            max_magnitude: options.max_magnitude,
            duplicate_radius_arcsec: options.duplicate_radius_arcsec as f32,
            close_pair_radius_arcsec: options.close_pair_radius_arcsec as f32,
            rows_invalid: stats.rows_invalid,
            rows_removed: stats.duplicate_source_ids
                + stats.spatial_duplicates
                + stats.close_pair_stars
                + stats.rows_outside_magnitude_limit,
            records_sha256: digest.finalize().into(),
        };
        file.seek(SeekFrom::Start(0))
            .map_err(|error| Error::io(path, error))?;
        file.write_all(&encode_header(&info))
            .map_err(|error| Error::io(path, error))
    })
}

fn encode_record(star: CatalogStar) -> [u8; CATALOG_RECORD_LEN] {
    let mut record = [0_u8; CATALOG_RECORD_LEN];
    record[0..8].copy_from_slice(&star.source_id.to_le_bytes());
    record[8..14].copy_from_slice(&encode_octahedral(star.vector));
    let millimagnitude = (star.magnitude * 1_000.0)
        .round()
        .clamp(f32::from(i16::MIN), f32::from(i16::MAX)) as i16;
    record[14..16].copy_from_slice(&millimagnitude.to_le_bytes());
    record[16] = star.flags;
    record
}

fn decode_record(record: [u8; CATALOG_RECORD_LEN]) -> CatalogStar {
    let source_id = u64::from_le_bytes(record[0..8].try_into().expect("fixed slice"));
    let vector = decode_octahedral(record[8..14].try_into().expect("fixed slice"));
    let magnitude = f32::from(i16::from_le_bytes(
        record[14..16].try_into().expect("fixed slice"),
    )) / 1_000.0;
    let flags = record[16];
    CatalogStar {
        source_id,
        vector,
        magnitude,
        flags,
    }
}

fn encode_header(info: &CatalogInfo) -> [u8; CATALOG_HEADER_LEN] {
    let mut header = [0_u8; CATALOG_HEADER_LEN];
    header[0..8].copy_from_slice(&CATALOG_MAGIC);
    header[8..10].copy_from_slice(&CATALOG_VERSION.to_le_bytes());
    header[10..12].copy_from_slice(&(CATALOG_HEADER_LEN as u16).to_le_bytes());
    header[12..14].copy_from_slice(&(CATALOG_RECORD_LEN as u16).to_le_bytes());
    header[16..24].copy_from_slice(&info.records.to_le_bytes());
    header[24..32].copy_from_slice(&info.epoch.to_le_bytes());
    header[32..36].copy_from_slice(&info.min_magnitude.to_le_bytes());
    header[36..40].copy_from_slice(&info.max_magnitude.to_le_bytes());
    header[40..44].copy_from_slice(&info.duplicate_radius_arcsec.to_le_bytes());
    header[44..48].copy_from_slice(&info.close_pair_radius_arcsec.to_le_bytes());
    header[48..56].copy_from_slice(&info.rows_invalid.to_le_bytes());
    header[56..64].copy_from_slice(&info.rows_removed.to_le_bytes());
    header[64..96].copy_from_slice(&info.records_sha256);
    let checksum = tetra3_index::crc32c(&header);
    header[96..100].copy_from_slice(&checksum.to_le_bytes());
    header
}

fn decode_header(header: &[u8; CATALOG_HEADER_LEN]) -> Result<CatalogInfo> {
    if header[0..8] != CATALOG_MAGIC {
        return Err(Error::InvalidCatalog("bad T3CAT magic".into()));
    }
    let version = u16::from_le_bytes(header[8..10].try_into().expect("fixed slice"));
    let header_len = u16::from_le_bytes(header[10..12].try_into().expect("fixed slice"));
    let record_len = u16::from_le_bytes(header[12..14].try_into().expect("fixed slice"));
    if version != CATALOG_VERSION
        || usize::from(header_len) != CATALOG_HEADER_LEN
        || usize::from(record_len) != CATALOG_RECORD_LEN
    {
        return Err(Error::InvalidCatalog(format!(
            "unsupported catalog version/layout {version}/{header_len}/{record_len}"
        )));
    }
    let expected_checksum = u32::from_le_bytes(header[96..100].try_into().expect("fixed slice"));
    let mut checksum_header = *header;
    checksum_header[96..100].fill(0);
    let actual_checksum = tetra3_index::crc32c(&checksum_header);
    if expected_checksum != actual_checksum {
        return Err(Error::InvalidCatalog(format!(
            "header CRC32C mismatch: expected {expected_checksum:08x}, got {actual_checksum:08x}"
        )));
    }
    if header[14..16] != [0, 0] || header[100..].iter().any(|&byte| byte != 0) {
        return Err(Error::InvalidCatalog(
            "reserved catalog header bytes are nonzero".into(),
        ));
    }
    let value = |range: std::ops::Range<usize>| -> [u8; 8] {
        header[range].try_into().expect("fixed slice")
    };
    let value4 = |range: std::ops::Range<usize>| -> [u8; 4] {
        header[range].try_into().expect("fixed slice")
    };
    let mut records_sha256 = [0_u8; 32];
    records_sha256.copy_from_slice(&header[64..96]);
    let info = CatalogInfo {
        version,
        records: u64::from_le_bytes(value(16..24)),
        epoch: f64::from_le_bytes(value(24..32)),
        min_magnitude: f32::from_le_bytes(value4(32..36)),
        max_magnitude: f32::from_le_bytes(value4(36..40)),
        duplicate_radius_arcsec: f32::from_le_bytes(value4(40..44)),
        close_pair_radius_arcsec: f32::from_le_bytes(value4(44..48)),
        rows_invalid: u64::from_le_bytes(value(48..56)),
        rows_removed: u64::from_le_bytes(value(56..64)),
        records_sha256,
    };
    if !info.epoch.is_finite()
        || !info.min_magnitude.is_finite()
        || !info.max_magnitude.is_finite()
        || info.min_magnitude > info.max_magnitude
        || !info.duplicate_radius_arcsec.is_finite()
        || !info.close_pair_radius_arcsec.is_finite()
        || info.duplicate_radius_arcsec < 0.0
        || info.close_pair_radius_arcsec < info.duplicate_radius_arcsec
    {
        return Err(Error::InvalidCatalog(
            "catalog header contains non-finite values".into(),
        ));
    }
    Ok(info)
}

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut result = String::with_capacity(bytes.len() * 2);
    for &byte in bytes {
        result.push(DIGITS[usize::from(byte >> 4)] as char);
        result.push(DIGITS[usize::from(byte & 0xf)] as char);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn conversion_handles_quoted_newlines() {
        let directory = tempfile::tempdir().unwrap();
        let input = directory.path().join("quoted.csv");
        let output = directory.path().join("quoted.t3cat");
        std::fs::write(
            &input,
            "source_id,ra,dec,phot_g_mean_mag,notes\n1,0,0,3,\"first line\nsecond, \"\"quoted\"\" line\"\n",
        )
        .unwrap();
        let options = ConvertOptions {
            duplicate_radius_arcsec: 0.0,
            close_pair_radius_arcsec: 0.0,
            ..ConvertOptions::default()
        };

        let stats = convert_gaia_csv(&input, &output, &options).unwrap();

        assert_eq!(stats.rows_read, 1);
        assert_eq!(stats.stars_written, 1);
        assert_eq!(CompactCatalog::read(output, true).unwrap().stars.len(), 1);
    }

    #[test]
    fn conversion_is_deterministic_and_rejects_both_close_stars() {
        let directory = tempfile::tempdir().unwrap();
        let input = directory.path().join("tiny.csv");
        let output_a = directory.path().join("a.t3cat");
        let output_b = directory.path().join("b.t3cat");
        let mut file = File::create(&input).unwrap();
        writeln!(file, "source_id,ra,dec,phot_g_mean_mag,pmra,pmdec").unwrap();
        writeln!(file, "3,90,10,5.0,,").unwrap();
        writeln!(file, "2,0.001,0,4.0,0,0").unwrap();
        writeln!(file, "1,0,0,3.0,0,0").unwrap();
        writeln!(file, "1,180,0,9.0,0,0").unwrap();
        let options = ConvertOptions {
            input_epoch: 2016.0,
            output_epoch: 2016.0,
            duplicate_radius_arcsec: 0.1,
            close_pair_radius_arcsec: 5.0,
            ..ConvertOptions::default()
        };
        let stats = convert_gaia_csv(&input, &output_a, &options).unwrap();
        convert_gaia_csv(&input, &output_b, &options).unwrap();
        assert_eq!(stats.duplicate_source_ids, 1);
        assert_eq!(stats.close_pair_stars, 2);
        assert_eq!(
            std::fs::read(output_a).unwrap(),
            std::fs::read(&output_b).unwrap()
        );
        let catalog = CompactCatalog::read(output_b, true).unwrap();
        assert_eq!(catalog.stars.len(), 1);
        assert_eq!(catalog.stars[0].source_id, 3);
    }

    #[test]
    fn checksum_detects_corruption() {
        let directory = tempfile::tempdir().unwrap();
        let input = directory.path().join("tiny.csv");
        let output = directory.path().join("tiny.t3cat");
        std::fs::write(
            &input,
            "source_id,ra,dec,phot_g_mean_mag,pmra,pmdec\n1,0,0,3,0,0\n",
        )
        .unwrap();
        let options = ConvertOptions {
            close_pair_radius_arcsec: 0.0,
            duplicate_radius_arcsec: 0.0,
            ..ConvertOptions::default()
        };
        convert_gaia_csv(&input, &output, &options).unwrap();
        let mut bytes = std::fs::read(&output).unwrap();
        bytes[CATALOG_HEADER_LEN + 1] ^= 1;
        std::fs::write(&output, bytes).unwrap();
        assert!(CompactCatalog::read(&output, true).is_err());
    }

    #[test]
    fn canonical_gzip_preserves_namespace_epoch_and_anchor_flag() {
        let directory = tempfile::tempdir().unwrap();
        let input = directory.path().join("merged.csv.gz");
        let output = directory.path().join("merged.t3cat");
        let file = File::create(&input).unwrap();
        let mut gzip = flate2::write::GzEncoder::new(file, flate2::Compression::default());
        writeln!(
            gzip,
            "catalog,source_id,ra_deg,dec_deg,ref_epoch_jyear,pmra_masyr,pmdec_masyr,parallax_mas,magnitude,magnitude_band,color,flags"
        )
        .unwrap();
        writeln!(
            gzip,
            "gaia_dr3,42,359.999,0,2016,1000,,0,5.4324,G,,pattern_anchor=1"
        )
        .unwrap();
        writeln!(
            gzip,
            "hipparcos,42,45,60,1991.25,0,0,0,4.0,V,,pattern_anchor=0"
        )
        .unwrap();
        gzip.finish().unwrap();
        let options = ConvertOptions {
            output_epoch: 2026.0,
            duplicate_radius_arcsec: 0.0,
            close_pair_radius_arcsec: 0.0,
            ..ConvertOptions::default()
        };
        let stats = convert_gaia_csv(&input, &output, &options).unwrap();
        assert_eq!(stats.stars_written, 2);
        let catalog = CompactCatalog::read(&output, true).unwrap();
        let hip = catalog
            .stars
            .iter()
            .find(|star| star.flags & STAR_FLAG_HIPPARCOS != 0)
            .unwrap();
        assert_eq!(hip.source_id, 42 | (1_u64 << 63));
        assert_eq!(hip.flags & STAR_FLAG_PATTERN_ELIGIBLE, 0);
        let gaia = catalog
            .stars
            .iter()
            .find(|star| star.source_id == 42)
            .unwrap();
        assert_ne!(gaia.flags & STAR_FLAG_PATTERN_ELIGIBLE, 0);
        let (ra, _) = gaia.vector.to_radec_deg();
        assert!((ra - 0.001_777_777_8).abs() < 0.000_02, "{ra}");
    }

    #[test]
    fn header_crc_detects_metadata_corruption() {
        let directory = tempfile::tempdir().unwrap();
        let input = directory.path().join("tiny.csv");
        let output = directory.path().join("tiny.t3cat");
        std::fs::write(&input, "source_id,ra,dec,phot_g_mean_mag\n1,0,0,3\n").unwrap();
        let options = ConvertOptions {
            close_pair_radius_arcsec: 0.0,
            duplicate_radius_arcsec: 0.0,
            ..ConvertOptions::default()
        };
        convert_gaia_csv(&input, &output, &options).unwrap();
        let mut bytes = std::fs::read(&output).unwrap();
        bytes[24] ^= 1;
        std::fs::write(&output, bytes).unwrap();
        assert!(CompactCatalog::read(&output, true).is_err());
    }
}

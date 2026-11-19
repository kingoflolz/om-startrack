//! Host-side deterministic `.t3ix` writer.

use core::fmt;
use std::collections::BTreeSet;

use crate::format::{
    ENDIAN_MARKER, GLOBAL_LEVEL, HEADER_CRC_OFFSET, HEADER_SIZE, LEVEL_DESCRIPTOR_SIZE, MAGIC,
    MAX_LEVELS, MAX_PATTERN_STARS, MAX_PATTERNS, MAX_RATIO_TOLERANCE_Q8, MAX_SECONDARY_ENTRIES,
    MAX_SPATIAL_CELLS, MAX_VERIFICATION_STARS, PATTERN_RECORD_SIZE, PATTERNS_SECTION_INDEX,
    PRIMARY_BUCKET_COUNT, PRIMARY_DIRECTORY_COUNT, PRIMARY_SECTION_INDEX, SECONDARY_ENTRY_SIZE,
    SECONDARY_SECTION_INDEX, SECTION_COUNT, SECTION_ENTRY_SIZE, VERSION_MAJOR, VERSION_MINOR,
    align8, crc32c, cumulative_magnitude_histogram, magnitude_bin, pack_pattern_refs, pack_primary,
    pack_ratio_lows, pack_secondary, spatial_cell_for_direction, write_u24_signed,
};
use crate::{IndexView, SectionKind, VerificationStar};

const DEFAULT_Z_BINS: u16 = 180;
const DEFAULT_LONGITUDE_BINS: u16 = 360;
const OCT24_SCALE: f64 = 8_388_607.0;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct VerificationStarInput {
    pub direction: [f64; 3],
    pub magnitude: f32,
    pub flags: u8,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PatternInput {
    pub ratios: [u8; 5],
    /// Input-order identifiers returned by [`IndexBuilder::add_verification_star`].
    pub verification_stars: [u32; 4],
    pub largest_edge_q16: u16,
}

impl PatternInput {
    pub fn from_floats(
        ratios: [f64; 5],
        verification_stars: [u32; 4],
        largest_edge_radians: f64,
    ) -> Result<Self, BuildError> {
        let mut quantized = [0_u8; 5];
        for (output, ratio) in quantized.iter_mut().zip(ratios) {
            *output = quantize_ratio(ratio)?;
        }
        Ok(Self {
            ratios: quantized,
            verification_stars,
            largest_edge_q16: quantize_edge_radians(largest_edge_radians)?,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LevelInput {
    /// Horizontal field-of-view lower bound in millidegrees.
    pub min_fov_mdeg: u32,
    /// Horizontal field-of-view upper bound in millidegrees.
    pub max_fov_mdeg: u32,
    pub default_tolerance_q8: u8,
    pub neighbor_level_radius: u8,
    pub max_verification_magnitude_q4: Option<i8>,
    pub patterns: Vec<PatternInput>,
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
struct EncodedStar {
    cell: u32,
    magnitude_q4: i8,
    oct_x: i32,
    oct_y: i32,
    flags: u8,
    original_id: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct EncodedPattern {
    ratios: [u8; 5],
    pattern_stars: [u32; 4],
    largest_edge_q16: u16,
}

struct StarSections {
    verification: Vec<u8>,
    spatial: Vec<u8>,
    remap: Vec<u32>,
}

struct PatternSections {
    primary: Vec<u8>,
    secondary: Vec<u8>,
    patterns: Vec<u8>,
}

struct PreparedLevel {
    min_fov_mdeg: u32,
    max_fov_mdeg: u32,
    default_tolerance_q8: u8,
    neighbor_level_radius: u8,
    max_verification_magnitude_q4: Option<i8>,
    minimum_edge: u16,
    maximum_edge: u16,
}

pub struct IndexBuilder {
    catalog_epoch_milliyear: u32,
    z_bins: u16,
    longitude_bins: u16,
    verification_stars: Vec<EncodedStar>,
    levels: Vec<LevelInput>,
}

impl Default for IndexBuilder {
    fn default() -> Self {
        Self::new()
    }
}

impl IndexBuilder {
    pub fn new() -> Self {
        Self {
            catalog_epoch_milliyear: 2_016_000,
            z_bins: DEFAULT_Z_BINS,
            longitude_bins: DEFAULT_LONGITUDE_BINS,
            verification_stars: Vec::new(),
            levels: Vec::new(),
        }
    }

    pub fn set_catalog_epoch_milliyear(
        &mut self,
        epoch_milliyear: u32,
    ) -> Result<&mut Self, BuildError> {
        if epoch_milliyear > 10_000_000 {
            return Err(BuildError::Epoch);
        }
        self.catalog_epoch_milliyear = epoch_milliyear;
        Ok(self)
    }

    pub fn set_spatial_grid(
        &mut self,
        z_bins: u16,
        longitude_bins: u16,
    ) -> Result<&mut Self, BuildError> {
        let cells = u32::from(z_bins)
            .checked_mul(u32::from(longitude_bins))
            .ok_or(BuildError::Capacity("spatial grid"))?;
        if z_bins == 0 || longitude_bins == 0 || cells > MAX_SPATIAL_CELLS {
            return Err(BuildError::Capacity("spatial grid"));
        }
        if !self.verification_stars.is_empty() {
            return Err(BuildError::Order(
                "set the spatial grid before adding verification stars",
            ));
        }
        self.z_bins = z_bins;
        self.longitude_bins = longitude_bins;
        Ok(self)
    }

    /// Add a verification star and return its input-order identifier.
    ///
    /// The final file sorts stars into spatial cells. Pattern-star mappings are
    /// rewritten to those final identifiers during [`Self::build`].
    pub fn add_verification_star(
        &mut self,
        star: VerificationStarInput,
    ) -> Result<u32, BuildError> {
        let original_id = u32::try_from(self.verification_stars.len())
            .map_err(|_| BuildError::Capacity("verification stars"))?;
        if original_id >= MAX_VERIFICATION_STARS {
            return Err(BuildError::Capacity("verification stars"));
        }
        let direction = normalize(star.direction)?;
        let [oct_x, oct_y] = encode_normalized_oct24(direction);
        let magnitude_q4 = quantize_magnitude(star.magnitude)?;
        let decoded = VerificationStar {
            oct_x,
            oct_y,
            magnitude_q4,
            flags: star.flags,
        }
        .direction_f64();
        let cell = spatial_cell_for_direction(decoded, self.z_bins, self.longitude_bins);
        self.verification_stars.push(EncodedStar {
            cell,
            magnitude_q4,
            oct_x,
            oct_y,
            flags: star.flags,
            original_id,
        });
        Ok(original_id)
    }

    pub fn add_level(&mut self, level: LevelInput) -> Result<&mut Self, BuildError> {
        if self.levels.len() >= usize::from(MAX_LEVELS) {
            return Err(BuildError::Capacity("levels"));
        }
        validate_level_input(&level, self.verification_stars.len() as u32)?;
        self.levels.push(level);
        Ok(self)
    }

    /// Build a byte-identical index for byte-identical logical inputs.
    pub fn build(self) -> Result<Vec<u8>, BuildError> {
        let Self {
            catalog_epoch_milliyear,
            z_bins,
            longitude_bins,
            verification_stars,
            mut levels,
        } = self;
        levels.sort_by_key(|level| (level.min_fov_mdeg, level.max_fov_mdeg));
        for pair in levels.windows(2) {
            if pair[0].min_fov_mdeg == pair[1].min_fov_mdeg {
                return Err(BuildError::Level("duplicate minimum field of view"));
            }
        }

        let pattern_stars = levels
            .iter()
            .flat_map(|level| &level.patterns)
            .flat_map(|pattern| pattern.verification_stars)
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect::<Vec<_>>();
        if pattern_stars.len() > MAX_PATTERN_STARS as usize {
            return Err(BuildError::Capacity("pattern stars"));
        }

        let verification_counts = verification_counts_by_level(&levels, &verification_stars);
        let mut patterns = Vec::new();
        let mut prepared_levels = Vec::with_capacity(levels.len());
        for level in levels {
            let mut edge_window: Option<(u16, u16)> = None;
            for pattern in level.patterns {
                let mut pattern_star_ids = pattern.verification_stars.map(|verification_star| {
                    pattern_stars
                        .binary_search(&verification_star)
                        .expect("pattern references were collected") as u32
                });
                pattern_star_ids.sort_unstable();
                edge_window = Some(match edge_window {
                    Some((minimum, maximum)) => (
                        minimum.min(pattern.largest_edge_q16),
                        maximum.max(pattern.largest_edge_q16),
                    ),
                    None => (pattern.largest_edge_q16, pattern.largest_edge_q16),
                });
                patterns.push(EncodedPattern {
                    ratios: pattern.ratios,
                    pattern_stars: pattern_star_ids,
                    largest_edge_q16: pattern.largest_edge_q16,
                });
            }
            let (minimum_edge, maximum_edge) = edge_window.unwrap_or((0, 0));
            prepared_levels.push(PreparedLevel {
                min_fov_mdeg: level.min_fov_mdeg,
                max_fov_mdeg: level.max_fov_mdeg,
                default_tolerance_q8: level.default_tolerance_q8,
                neighbor_level_radius: level.neighbor_level_radius,
                max_verification_magnitude_q4: level.max_verification_magnitude_q4,
                minimum_edge,
                maximum_edge,
            });
        }
        if patterns.len() > MAX_PATTERNS as usize {
            return Err(BuildError::Capacity("patterns"));
        }
        patterns.sort_by_key(pattern_sort_key);
        patterns.dedup();

        let star_sections = build_star_sections(verification_stars, z_bins, longitude_bins);
        let mut pattern_star_payload = Vec::with_capacity(pattern_stars.len() * 3);
        for &original_id in &pattern_stars {
            let remapped = star_sections.remap[original_id as usize];
            pattern_star_payload.extend_from_slice(&remapped.to_le_bytes()[..3]);
        }

        let pattern_sections = build_pattern_sections(&patterns)?;
        let level_payload = build_level_section(&prepared_levels, &patterns, &verification_counts);

        let sections = [
            SectionData::new(
                SectionKind::LevelDescriptors,
                prepared_levels.len() as u32,
                level_payload,
            ),
            SectionData::new(
                SectionKind::PatternStars,
                pattern_stars.len() as u32,
                pattern_star_payload,
            ),
            SectionData::new(
                SectionKind::VerificationStars,
                star_sections.remap.len() as u32,
                star_sections.verification,
            ),
            SectionData::new(
                SectionKind::SpatialDirectory,
                u32::from(z_bins) * u32::from(longitude_bins),
                star_sections.spatial,
            ),
            SectionData::new(
                SectionKind::PrimaryDirectory,
                PRIMARY_DIRECTORY_COUNT as u32,
                pattern_sections.primary,
            ),
            SectionData::new(
                SectionKind::SecondaryDirectory,
                (pattern_sections.secondary.len() / SECONDARY_ENTRY_SIZE) as u32,
                pattern_sections.secondary,
            ),
            SectionData::new(
                SectionKind::Patterns,
                patterns.len() as u32,
                pattern_sections.patterns,
            ),
        ];
        let output = assemble(
            &sections,
            prepared_levels.len() as u16,
            catalog_epoch_milliyear,
        )?;
        // Reopen what we wrote. This is intentionally part of every build, not
        // merely a test, so host tools cannot emit a file rejected on Android.
        IndexView::parse(&output).map_err(BuildError::Validation)?;
        Ok(output)
    }
}

fn build_star_sections(
    mut stars: Vec<EncodedStar>,
    z_bins: u16,
    longitude_bins: u16,
) -> StarSections {
    stars.sort();
    let mut remap = vec![0_u32; stars.len()];
    let mut verification = Vec::with_capacity(stars.len() * 8);
    let cell_count = usize::from(z_bins) * usize::from(longitude_bins);
    let mut counts = vec![0_u32; cell_count];
    for (new_id, star) in stars.iter().enumerate() {
        remap[star.original_id as usize] = new_id as u32;
        counts[star.cell as usize] += 1;
        write_u24_signed(&mut verification, star.oct_x);
        write_u24_signed(&mut verification, star.oct_y);
        verification.push(star.magnitude_q4 as u8);
        verification.push(star.flags);
    }
    let directory_capacity = (cell_count + 1) * 4 + 8;
    let mut spatial = Vec::with_capacity(directory_capacity);
    spatial.extend_from_slice(&z_bins.to_le_bytes());
    spatial.extend_from_slice(&longitude_bins.to_le_bytes());
    spatial.extend_from_slice(&(stars.len() as u32).to_le_bytes());
    let mut prefix = 0_u32;
    spatial.extend_from_slice(&prefix.to_le_bytes());
    for count in counts {
        prefix += count;
        spatial.extend_from_slice(&prefix.to_le_bytes());
    }
    StarSections {
        verification,
        spatial,
        remap,
    }
}

#[derive(Debug)]
#[non_exhaustive]
pub enum BuildError {
    Capacity(&'static str),
    Direction,
    Magnitude,
    Ratio,
    Edge,
    Epoch,
    Level(&'static str),
    Reference(&'static str),
    Order(&'static str),
    Validation(crate::Error),
}

impl fmt::Display for BuildError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Capacity(item) => write!(formatter, "{item} exceed format capacity"),
            Self::Direction => formatter.write_str("star direction must be finite and non-zero"),
            Self::Magnitude => formatter.write_str("magnitude is outside signed q4 range"),
            Self::Ratio => formatter.write_str("tetrahedron ratio must be finite and in [0, 1]"),
            Self::Edge => formatter.write_str("largest edge must be finite and in [0, pi]"),
            Self::Epoch => formatter.write_str("catalog epoch is outside the supported range"),
            Self::Level(reason) => write!(formatter, "invalid level: {reason}"),
            Self::Reference(item) => write!(formatter, "invalid {item} reference"),
            Self::Order(reason) => write!(formatter, "invalid builder call order: {reason}"),
            Self::Validation(error) => {
                write!(formatter, "writer produced an invalid index: {error}")
            }
        }
    }
}

impl std::error::Error for BuildError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Validation(error) => Some(error),
            _ => None,
        }
    }
}

/// Quantize a normalized invariant with the shared floor-times-256 contract.
pub fn quantize_ratio(ratio: f64) -> Result<u8, BuildError> {
    if !ratio.is_finite() || !(0.0..=1.0).contains(&ratio) {
        return Err(BuildError::Ratio);
    }
    Ok(((ratio * 256.0).floor() as u32).min(255) as u8)
}

/// Quantize an angular edge over one half-turn with floor-times-65536.
pub fn quantize_edge_radians(edge: f64) -> Result<u16, BuildError> {
    if !edge.is_finite() || !(0.0..=core::f64::consts::PI).contains(&edge) {
        return Err(BuildError::Edge);
    }
    Ok(((edge / core::f64::consts::PI * 65_536.0).floor() as u32).min(65_535) as u16)
}

pub fn encode_oct24(direction: [f64; 3]) -> Result<[i32; 2], BuildError> {
    Ok(encode_normalized_oct24(normalize(direction)?))
}

fn encode_normalized_oct24([x, y, z]: [f64; 3]) -> [i32; 2] {
    let inverse_l1 = (x.abs() + y.abs() + z.abs()).recip();
    let mut oct_x = x * inverse_l1;
    let mut oct_y = y * inverse_l1;
    if z < 0.0 {
        let old_x = oct_x;
        oct_x = (1.0 - oct_y.abs()).copysign(old_x);
        oct_y = (1.0 - old_x.abs()).copysign(oct_y);
    }
    [
        (oct_x * OCT24_SCALE)
            .round()
            .clamp(-OCT24_SCALE, OCT24_SCALE) as i32,
        (oct_y * OCT24_SCALE)
            .round()
            .clamp(-OCT24_SCALE, OCT24_SCALE) as i32,
    ]
}

fn normalize(direction: [f64; 3]) -> Result<[f64; 3], BuildError> {
    if direction.iter().any(|component| !component.is_finite()) {
        return Err(BuildError::Direction);
    }
    let length_squared = direction.iter().map(|value| value * value).sum::<f64>();
    if !length_squared.is_finite() || length_squared <= f64::MIN_POSITIVE {
        return Err(BuildError::Direction);
    }
    let inverse_length = length_squared.sqrt().recip();
    Ok(direction.map(|value| value * inverse_length))
}

fn quantize_magnitude(magnitude: f32) -> Result<i8, BuildError> {
    if !magnitude.is_finite() {
        return Err(BuildError::Magnitude);
    }
    let scaled = (magnitude * 4.0).round();
    if scaled < f32::from(i8::MIN) || scaled > f32::from(i8::MAX) {
        return Err(BuildError::Magnitude);
    }
    Ok(scaled as i8)
}

fn validate_level_input(
    level: &LevelInput,
    verification_star_count: u32,
) -> Result<(), BuildError> {
    if level.min_fov_mdeg > level.max_fov_mdeg {
        return Err(BuildError::Level("minimum FOV exceeds maximum FOV"));
    }
    if level.default_tolerance_q8 > MAX_RATIO_TOLERANCE_Q8 {
        return Err(BuildError::Level("ratio tolerance exceeds runtime cap"));
    }
    if level
        .patterns
        .iter()
        .flat_map(|pattern| pattern.verification_stars)
        .any(|star| star >= verification_star_count)
    {
        return Err(BuildError::Reference("verification star"));
    }
    for pattern in &level.patterns {
        if pattern.largest_edge_q16 == 0 {
            return Err(BuildError::Edge);
        }
        if pattern.ratios.windows(2).any(|pair| pair[0] > pair[1]) {
            return Err(BuildError::Ratio);
        }
        let mut refs = pattern.verification_stars;
        refs.sort_unstable();
        if refs.windows(2).any(|pair| pair[0] == pair[1]) {
            return Err(BuildError::Reference("duplicate pattern star"));
        }
    }
    Ok(())
}

fn pattern_sort_key(pattern: &EncodedPattern) -> (u16, u16, u16, u16, [u32; 4]) {
    (
        pack_primary(pattern.ratios),
        pack_secondary(pattern.ratios),
        pattern.largest_edge_q16,
        pack_ratio_lows(pattern.ratios),
        pattern.pattern_stars,
    )
}

fn build_pattern_sections(patterns: &[EncodedPattern]) -> Result<PatternSections, BuildError> {
    let mut primary = Vec::with_capacity(PRIMARY_DIRECTORY_COUNT * 4);
    let mut secondary = Vec::new();
    let mut records = Vec::with_capacity(patterns.len() * PATTERN_RECORD_SIZE);
    let mut position = 0_usize;
    let mut secondary_count = 0_u32;
    for primary_key in 0..PRIMARY_BUCKET_COUNT as u16 {
        primary.extend_from_slice(&secondary_count.to_le_bytes());
        while position < patterns.len() && pack_primary(patterns[position].ratios) == primary_key {
            let secondary_key = pack_secondary(patterns[position].ratios);
            let start = position as u32;
            while position < patterns.len()
                && pack_primary(patterns[position].ratios) == primary_key
                && pack_secondary(patterns[position].ratios) == secondary_key
            {
                encode_pattern_record(&mut records, patterns[position]);
                position += 1;
            }
            secondary.extend_from_slice(&secondary_key.to_le_bytes());
            secondary.extend_from_slice(&0_u16.to_le_bytes());
            secondary.extend_from_slice(&start.to_le_bytes());
            secondary.extend_from_slice(&(position as u32).to_le_bytes());
            secondary_count = secondary_count
                .checked_add(1)
                .ok_or(BuildError::Capacity("secondary buckets"))?;
            if secondary_count > MAX_SECONDARY_ENTRIES {
                return Err(BuildError::Capacity("secondary buckets"));
            }
        }
    }
    primary.extend_from_slice(&secondary_count.to_le_bytes());
    debug_assert_eq!(position, patterns.len());
    Ok(PatternSections {
        primary,
        secondary,
        patterns: records,
    })
}

fn encode_pattern_record(output: &mut Vec<u8>, pattern: EncodedPattern) {
    output.extend_from_slice(&pack_pattern_refs(pattern.pattern_stars));
    output.extend_from_slice(&pack_ratio_lows(pattern.ratios).to_le_bytes());
    output.extend_from_slice(&pattern.largest_edge_q16.to_le_bytes());
}

fn build_level_section(
    levels: &[PreparedLevel],
    shared_patterns: &[EncodedPattern],
    verification_counts: &[u32],
) -> Vec<u8> {
    debug_assert_eq!(levels.len(), verification_counts.len());
    let mut output = Vec::with_capacity(levels.len() * LEVEL_DESCRIPTOR_SIZE);
    for (level, &verification_count) in levels.iter().zip(verification_counts) {
        let pattern_count = if level.minimum_edge == 0 {
            0
        } else {
            shared_patterns
                .iter()
                .filter(|pattern| {
                    (level.minimum_edge..=level.maximum_edge).contains(&pattern.largest_edge_q16)
                })
                .count() as u32
        };
        output.extend_from_slice(&level.min_fov_mdeg.to_le_bytes());
        output.extend_from_slice(&level.max_fov_mdeg.to_le_bytes());
        output.extend_from_slice(&pattern_count.to_le_bytes());
        output.extend_from_slice(&verification_count.to_le_bytes());
        output.extend_from_slice(&PRIMARY_SECTION_INDEX.to_le_bytes());
        output.extend_from_slice(&SECONDARY_SECTION_INDEX.to_le_bytes());
        output.extend_from_slice(&PATTERNS_SECTION_INDEX.to_le_bytes());
        output.push(level.default_tolerance_q8);
        output.push(level.neighbor_level_radius);
        output.push(level.max_verification_magnitude_q4.unwrap_or(0) as u8);
        output.push(u8::from(level.max_verification_magnitude_q4.is_some()));
        output.extend_from_slice(&level.minimum_edge.to_le_bytes());
        output.extend_from_slice(&level.maximum_edge.to_le_bytes());
        output.extend_from_slice(&0_u32.to_le_bytes());
    }
    output
}

fn verification_counts_by_level(levels: &[LevelInput], stars: &[EncodedStar]) -> Vec<u32> {
    let mut histogram = [0_u32; 256];
    for star in stars {
        histogram[magnitude_bin(star.magnitude_q4)] += 1;
    }
    let cumulative = cumulative_magnitude_histogram(histogram);
    let count = cumulative[255];
    levels
        .iter()
        .map(|level| {
            level
                .max_verification_magnitude_q4
                .map_or(count, |limit| cumulative[magnitude_bin(limit)])
        })
        .collect()
}

struct SectionData {
    kind: SectionKind,
    count: u32,
    payload: Vec<u8>,
}

impl SectionData {
    fn new(kind: SectionKind, count: u32, payload: Vec<u8>) -> Self {
        Self {
            kind,
            count,
            payload,
        }
    }
}

fn assemble(
    sections: &[SectionData; SECTION_COUNT as usize],
    level_count: u16,
    catalog_epoch_milliyear: u32,
) -> Result<Vec<u8>, BuildError> {
    let table_length = sections
        .len()
        .checked_mul(SECTION_ENTRY_SIZE)
        .ok_or(BuildError::Capacity("section table"))?;
    let payload_start = align8(
        HEADER_SIZE
            .checked_add(table_length)
            .ok_or(BuildError::Capacity("section table"))?,
    )
    .ok_or(BuildError::Capacity("section table"))?;
    let payload_length = sections.iter().try_fold(0_usize, |length, section| {
        align8(length)
            .and_then(|length| length.checked_add(section.payload.len()))
            .ok_or(BuildError::Capacity("file"))
    })?;
    let capacity = payload_start
        .checked_add(payload_length)
        .ok_or(BuildError::Capacity("file"))?;
    let mut output = Vec::with_capacity(capacity);
    output.resize(payload_start, 0);
    for (section_index, section) in sections.iter().enumerate() {
        if section_index != 0 {
            while output.len() & 7 != 0 {
                output.push(0);
            }
        }
        let offset = output.len();
        output.extend_from_slice(&section.payload);
        let table_offset = HEADER_SIZE + section_index * SECTION_ENTRY_SIZE;
        put_u16(&mut output, table_offset, section.kind as u16);
        put_u16(&mut output, table_offset + 2, GLOBAL_LEVEL);
        put_u32(&mut output, table_offset + 4, 0);
        put_u64(&mut output, table_offset + 8, offset as u64);
        put_u64(&mut output, table_offset + 16, section.payload.len() as u64);
        put_u32(&mut output, table_offset + 24, section.count);
        put_u32(&mut output, table_offset + 28, crc32c(&section.payload));
    }
    output[..4].copy_from_slice(&MAGIC);
    put_u16(&mut output, 4, VERSION_MAJOR);
    put_u16(&mut output, 6, VERSION_MINOR);
    put_u32(&mut output, 8, ENDIAN_MARKER);
    put_u32(&mut output, 12, HEADER_SIZE as u32);
    let output_length = output.len() as u64;
    put_u64(&mut output, 16, output_length);
    put_u64(&mut output, 24, HEADER_SIZE as u64);
    put_u32(&mut output, 32, SECTION_COUNT);
    put_u16(&mut output, 36, SECTION_ENTRY_SIZE as u16);
    put_u16(&mut output, 38, level_count);
    put_u32(&mut output, 40, 0);
    let table_crc = crc32c(&output[HEADER_SIZE..HEADER_SIZE + table_length]);
    put_u32(&mut output, 44, table_crc);
    put_u32(&mut output, 48, catalog_epoch_milliyear);
    let header_crc = crc32c(&output[..HEADER_CRC_OFFSET]);
    put_u32(&mut output, HEADER_CRC_OFFSET, header_crc);
    Ok(output)
}

fn put_u16(output: &mut [u8], offset: usize, value: u16) {
    output[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_u32(output: &mut [u8], offset: usize, value: u32) {
    output[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn put_u64(output: &mut [u8], offset: usize, value: u64) {
    output[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}

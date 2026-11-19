use core::fmt;
use core::ops::{ControlFlow, Range, RangeInclusive};

use crate::format::{
    ENDIAN_MARKER, GLOBAL_LEVEL, HEADER_CRC_OFFSET, HEADER_SIZE, KNOWN_HEADER_FLAGS,
    KNOWN_SECTION_FLAGS, LEVEL_DESCRIPTOR_SIZE, MAGIC, MAX_LEVELS, MAX_PATTERN_STARS, MAX_PATTERNS,
    MAX_RATIO_TOLERANCE_Q8, MAX_SECONDARY_ENTRIES, MAX_SPATIAL_CELLS, MAX_VERIFICATION_STARS,
    PATTERN_RECORD_SIZE, PATTERN_STAR_SIZE, PATTERNS_SECTION_INDEX, PRIMARY_BUCKET_COUNT,
    PRIMARY_DIRECTORY_COUNT, PRIMARY_SECTION_INDEX, SECONDARY_ENTRY_SIZE, SECONDARY_SECTION_INDEX,
    SECTION_COUNT, SECTION_ENTRY_SIZE, VERIFICATION_STAR_SIZE, VERSION_MAJOR, VERSION_MINOR,
    align8, crc32c, cumulative_magnitude_histogram, magnitude_bin, pack_primary, pack_secondary,
    read_u16, read_u24, read_u24_signed, read_u32, read_u64, spatial_cell_for_direction,
    unpack_pattern_refs, unpack_ratios,
};

#[derive(Clone, Copy)]
struct ParseMode {
    verify_checksums: bool,
    validate_records: bool,
}

impl ParseMode {
    const FULL: Self = Self {
        verify_checksums: true,
        validate_records: true,
    };
    const WITHOUT_CHECKSUMS: Self = Self {
        verify_checksums: false,
        validate_records: true,
    };
    #[cfg(feature = "mmap")]
    const TRUSTED_MAPPING: Self = Self {
        verify_checksums: false,
        validate_records: false,
    };
}

/// A parse or lookup error. Errors never borrow the input buffer.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub enum Error {
    TooShort,
    BadMagic,
    UnsupportedVersion { major: u16, minor: u16 },
    WrongEndian { marker: u32 },
    NonCanonical(&'static str),
    ArithmeticOverflow,
    FileLengthMismatch,
    TooManyLevels,
    HeaderChecksum,
    SectionTableChecksum,
    SectionChecksum { section: u32 },
    SectionOutOfBounds { section: u32 },
    InvalidSection { section: u32 },
    InvalidLevel { level: u16 },
    InvalidPatternStar { star: u32 },
    InvalidVerificationStar { star: u32 },
    InvalidSpatialCell { cell: u32 },
    InvalidCap,
    RatioToleranceTooLarge { tolerance: u8 },
}

impl fmt::Display for Error {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::TooShort => formatter.write_str("index is shorter than its header"),
            Self::BadMagic => formatter.write_str("not a tetra3 index"),
            Self::UnsupportedVersion { major, minor } => {
                write!(
                    formatter,
                    "unsupported tetra3 index version {major}.{minor}"
                )
            }
            Self::WrongEndian { marker } => write!(
                formatter,
                "invalid endian marker 0x{marker:08x}; .t3ix integers are little endian"
            ),
            Self::NonCanonical(reason) => write!(formatter, "non-canonical index: {reason}"),
            Self::ArithmeticOverflow => formatter.write_str("index arithmetic overflow"),
            Self::FileLengthMismatch => {
                formatter.write_str("index file length does not match header")
            }
            Self::TooManyLevels => formatter.write_str("index has too many levels"),
            Self::HeaderChecksum => formatter.write_str("index header checksum mismatch"),
            Self::SectionTableChecksum => {
                formatter.write_str("index section-table checksum mismatch")
            }
            Self::SectionChecksum { section } => {
                write!(formatter, "index section {section} checksum mismatch")
            }
            Self::SectionOutOfBounds { section } => {
                write!(formatter, "index section {section} is out of bounds")
            }
            Self::InvalidSection { section } => {
                write!(formatter, "invalid index section {section}")
            }
            Self::InvalidLevel { level } => write!(formatter, "invalid index level {level}"),
            Self::InvalidPatternStar { star } => write!(formatter, "invalid pattern star {star}"),
            Self::InvalidVerificationStar { star } => {
                write!(formatter, "invalid verification star {star}")
            }
            Self::InvalidSpatialCell { cell } => write!(formatter, "invalid spatial cell {cell}"),
            Self::InvalidCap => formatter.write_str("invalid spherical cap"),
            Self::RatioToleranceTooLarge { tolerance } => write!(
                formatter,
                "ratio tolerance {tolerance} exceeds {MAX_RATIO_TOLERANCE_Q8}"
            ),
        }
    }
}

impl std::error::Error for Error {}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u16)]
pub(crate) enum SectionKind {
    LevelDescriptors = 1,
    PatternStars = 2,
    VerificationStars = 3,
    SpatialDirectory = 4,
    PrimaryDirectory = 0x100,
    SecondaryDirectory = 0x101,
    Patterns = 0x102,
}

impl SectionKind {
    fn from_raw(raw: u16) -> Option<Self> {
        Some(match raw {
            1 => Self::LevelDescriptors,
            2 => Self::PatternStars,
            3 => Self::VerificationStars,
            4 => Self::SpatialDirectory,
            0x100 => Self::PrimaryDirectory,
            0x101 => Self::SecondaryDirectory,
            0x102 => Self::Patterns,
            _ => return None,
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SectionInfo {
    kind: SectionKind,
    offset: u64,
    length: u64,
    count: u32,
    checksum: u32,
}

#[derive(Clone, Copy)]
struct PatternLayout<'a> {
    primary: &'a [u8],
    secondary: &'a [u8],
    patterns: &'a [u8],
    secondary_count: u32,
    pattern_count: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct LevelDescriptor {
    pub min_fov_mdeg: u32,
    pub max_fov_mdeg: u32,
    /// Number of records in the shared payload whose edge falls in this level's window.
    pub pattern_count: u32,
    pub default_tolerance_q8: u8,
    /// Number of adjacent levels the solver may try when the selected level is empty.
    pub neighbor_level_radius: u8,
    /// Brightness limit for verification scans at this FOV level.
    pub max_verification_magnitude_q4: Option<i8>,
    /// Exact number of verification-catalog rows admitted by the brightness limit.
    pub verification_star_count: u32,
    pub min_largest_edge_q16: u16,
    pub max_largest_edge_q16: u16,
}

impl LevelDescriptor {
    pub fn min_fov_degrees(self) -> f32 {
        self.min_fov_mdeg as f32 / 1_000.0
    }

    pub fn max_fov_degrees(self) -> f32 {
        self.max_fov_mdeg as f32 / 1_000.0
    }
}

/// One eight-byte verification-catalog record.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct VerificationStar {
    pub oct_x: i32,
    pub oct_y: i32,
    /// Signed quarter-magnitude units.
    pub magnitude_q4: i8,
    pub flags: u8,
}

impl VerificationStar {
    pub fn magnitude(self) -> f32 {
        f32::from(self.magnitude_q4) * 0.25
    }

    /// Decode to the precision used by solver geometry and cap membership.
    pub fn direction_f64(self) -> [f64; 3] {
        const SCALE: f64 = 8_388_607.0;
        let mut x = f64::from(self.oct_x) / SCALE;
        let mut y = f64::from(self.oct_y) / SCALE;
        let mut z = 1.0 - x.abs() - y.abs();
        if z < 0.0 {
            let adjustment = (-z).min(1.0);
            x += if x >= 0.0 { -adjustment } else { adjustment };
            y += if y >= 0.0 { -adjustment } else { adjustment };
        }
        let length_squared = x * x + y * y + z * z;
        let inverse_length = length_squared.sqrt().recip();
        x *= inverse_length;
        y *= inverse_length;
        z *= inverse_length;
        [x, y, z]
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SpatialGrid {
    /// Equal-area bins over z in [-1, 1].
    pub z_bins: u16,
    pub longitude_bins: u16,
}

impl SpatialGrid {
    pub fn cell_count(self) -> u32 {
        u32::from(self.z_bins) * u32::from(self.longitude_bins)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CandidateRecord {
    /// References into the verification catalog.
    pub verification_stars: [u32; 4],
    pub ratios: [u8; 5],
    /// Largest tetrahedron edge, quantized over [0, pi] radians.
    pub largest_edge_q16: u16,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct QueryStats {
    pub primary_buckets_visited: u32,
    pub records_examined: u32,
    pub candidates_emitted: u32,
    /// Conservative logical bytes touched in radix entries and records.
    pub bytes_read: u64,
    pub budget_exhausted: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct QueryBudget {
    pub max_records: u32,
    pub max_bytes: u64,
}

impl QueryBudget {
    pub const UNLIMITED: Self = Self {
        max_records: u32::MAX,
        max_bytes: u64::MAX,
    };
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CapBudget {
    pub max_cells: u32,
    pub max_records: u32,
    pub max_bytes: u64,
}

impl CapBudget {
    pub const UNLIMITED: Self = Self {
        max_cells: u32::MAX,
        max_records: u32::MAX,
        max_bytes: u64::MAX,
    };
}

#[derive(Clone, Copy)]
struct BucketQuery {
    ratios: [u8; 5],
    tolerance: u8,
    minimum_edge: u16,
    maximum_edge: u16,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CapStats {
    pub cells_visited: u32,
    pub records_examined: u32,
    pub stars_emitted: u32,
    pub bytes_read: u64,
    pub budget_exhausted: bool,
}

/// A validated, zero-copy view of a `.t3ix` byte slice.
#[derive(Clone, Copy)]
pub struct IndexView<'a> {
    data: &'a [u8],
    level_count: u16,
}

impl fmt::Debug for IndexView<'_> {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("IndexView")
            .field("length", &self.data.len())
            .field("section_count", &SECTION_COUNT)
            .field("level_count", &self.level_count)
            .field("version", &format_args!("{VERSION_MAJOR}.{VERSION_MINOR}"))
            .field("pattern_star_count", &self.pattern_star_count())
            .field("verification_star_count", &self.verification_star_count())
            .finish()
    }
}

impl<'a> IndexView<'a> {
    pub fn parse(data: &'a [u8]) -> Result<Self, Error> {
        Self::parse_mode(data, ParseMode::FULL)
    }

    /// Fully validate structure and records while skipping CRC-32C verification.
    pub fn parse_without_checksums(data: &'a [u8]) -> Result<Self, Error> {
        Self::parse_mode(data, ParseMode::WITHOUT_CHECKSUMS)
    }

    /// Parse an immutable mapping that was fully verified before installation.
    #[cfg(feature = "mmap")]
    pub(crate) fn parse_trusted_mapped(data: &'a [u8]) -> Result<Self, Error> {
        Self::parse_mode(data, ParseMode::TRUSTED_MAPPING)
    }

    fn parse_mode(data: &'a [u8], mode: ParseMode) -> Result<Self, Error> {
        if data.len() < HEADER_SIZE {
            return Err(Error::TooShort);
        }
        if data.get(..4) != Some(MAGIC.as_slice()) {
            return Err(Error::BadMagic);
        }
        let major = read_u16(data, 4).ok_or(Error::TooShort)?;
        let minor = read_u16(data, 6).ok_or(Error::TooShort)?;
        if major != VERSION_MAJOR || minor != VERSION_MINOR {
            return Err(Error::UnsupportedVersion { major, minor });
        }
        let endian = read_u32(data, 8).ok_or(Error::TooShort)?;
        if endian != ENDIAN_MARKER {
            return Err(Error::WrongEndian { marker: endian });
        }
        if read_u32(data, 12) != Some(HEADER_SIZE as u32) {
            return Err(Error::NonCanonical("header size"));
        }
        let declared_length = usize::try_from(read_u64(data, 16).ok_or(Error::TooShort)?)
            .map_err(|_| Error::ArithmeticOverflow)?;
        if declared_length != data.len() {
            return Err(Error::FileLengthMismatch);
        }
        if read_u64(data, 24) != Some(HEADER_SIZE as u64) {
            return Err(Error::NonCanonical("section table offset"));
        }
        let section_count = read_u32(data, 32).ok_or(Error::TooShort)?;
        if read_u16(data, 36) != Some(SECTION_ENTRY_SIZE as u16) {
            return Err(Error::NonCanonical("section entry size"));
        }
        let level_count = read_u16(data, 38).ok_or(Error::TooShort)?;
        if level_count > MAX_LEVELS {
            return Err(Error::TooManyLevels);
        }
        if section_count != SECTION_COUNT {
            return Err(Error::NonCanonical("section count"));
        }
        if read_u32(data, 40) != Some(KNOWN_HEADER_FLAGS) {
            return Err(Error::NonCanonical("header flags"));
        }
        let epoch_milliyear = read_u32(data, 48).ok_or(Error::TooShort)?;
        if epoch_milliyear > 10_000_000 || data[52..60].iter().any(|&byte| byte != 0) {
            return Err(Error::NonCanonical("reserved header bytes"));
        }
        if mode.verify_checksums {
            let expected_header_crc = read_u32(data, HEADER_CRC_OFFSET).ok_or(Error::TooShort)?;
            if crc32c(&data[..HEADER_CRC_OFFSET]) != expected_header_crc {
                return Err(Error::HeaderChecksum);
            }
        }

        let table_length = usize::try_from(section_count)
            .ok()
            .and_then(|count| count.checked_mul(SECTION_ENTRY_SIZE))
            .ok_or(Error::ArithmeticOverflow)?;
        let table_end = HEADER_SIZE
            .checked_add(table_length)
            .ok_or(Error::ArithmeticOverflow)?;
        let table = data
            .get(HEADER_SIZE..table_end)
            .ok_or(Error::SectionTableChecksum)?;
        if mode.verify_checksums {
            let expected_table_crc = read_u32(data, 44).ok_or(Error::TooShort)?;
            if crc32c(table) != expected_table_crc {
                return Err(Error::SectionTableChecksum);
            }
        }
        let payload_start = align8(table_end).ok_or(Error::ArithmeticOverflow)?;
        let padding = data
            .get(table_end..payload_start)
            .ok_or(Error::SectionTableChecksum)?;
        if padding.iter().any(|&byte| byte != 0) {
            return Err(Error::NonCanonical("section-table padding"));
        }

        let view = Self { data, level_count };
        view.validate_sections(payload_start, mode.verify_checksums)?;
        view.validate_layout(mode.validate_records)?;
        Ok(view)
    }

    pub fn as_bytes(self) -> &'a [u8] {
        self.data
    }

    pub fn section_count(self) -> u32 {
        SECTION_COUNT
    }

    pub fn level_count(self) -> u16 {
        self.level_count
    }

    #[cfg(feature = "mmap")]
    pub(crate) const fn from_validated_parts(data: &'a [u8], level_count: u16) -> Self {
        Self { data, level_count }
    }

    /// Catalog vector epoch in thousandths of a Julian year (e.g. 2,016,000).
    pub fn catalog_epoch_milliyear(self) -> u32 {
        read_u32(self.data, 48).expect("validated index header")
    }

    pub fn catalog_epoch(self) -> f64 {
        f64::from(self.catalog_epoch_milliyear()) / 1_000.0
    }

    fn section_info(self, section: u32) -> Result<SectionInfo, Error> {
        let offset = self.section_entry_offset(section)?;
        let raw_kind = read_u16(self.data, offset).ok_or(Error::InvalidSection { section })?;
        let kind = SectionKind::from_raw(raw_kind).ok_or(Error::InvalidSection { section })?;
        let raw_level = read_u16(self.data, offset + 2).ok_or(Error::InvalidSection { section })?;
        let flags = read_u32(self.data, offset + 4).ok_or(Error::InvalidSection { section })?;
        if raw_level != GLOBAL_LEVEL || flags != KNOWN_SECTION_FLAGS {
            return Err(Error::InvalidSection { section });
        }
        Ok(SectionInfo {
            kind,
            offset: read_u64(self.data, offset + 8).ok_or(Error::InvalidSection { section })?,
            length: read_u64(self.data, offset + 16).ok_or(Error::InvalidSection { section })?,
            count: read_u32(self.data, offset + 24).ok_or(Error::InvalidSection { section })?,
            checksum: read_u32(self.data, offset + 28).ok_or(Error::InvalidSection { section })?,
        })
    }

    pub fn level(self, level: u16) -> Result<LevelView<'a>, Error> {
        if level >= self.level_count {
            return Err(Error::InvalidLevel { level });
        }
        Ok(LevelView { index: self, level })
    }

    /// Select the first level containing a horizontal field of view.
    pub fn level_for_fov_mdeg(self, fov_mdeg: u32) -> Result<Option<LevelView<'a>>, Error> {
        for level in 0..self.level_count {
            let descriptor = self.level_descriptor(level)?;
            if (descriptor.min_fov_mdeg..=descriptor.max_fov_mdeg).contains(&fov_mdeg) {
                return Ok(Some(LevelView { index: self, level }));
            }
        }
        Ok(None)
    }

    pub fn level_descriptor(self, level: u16) -> Result<LevelDescriptor, Error> {
        if level >= self.level_count {
            return Err(Error::InvalidLevel { level });
        }
        let bytes = self.section_bytes(0)?;
        let offset = usize::from(level)
            .checked_mul(LEVEL_DESCRIPTOR_SIZE)
            .ok_or(Error::ArithmeticOverflow)?;
        self.read_level_descriptor(bytes, offset, level)
    }

    pub fn pattern_star_count(self) -> u32 {
        self.section_info(1).map_or(0, |section| section.count)
    }

    pub fn verification_star_count(self) -> u32 {
        self.section_info(2).map_or(0, |section| section.count)
    }

    pub fn verification_star(self, star: u32) -> Result<VerificationStar, Error> {
        let section = self.section_info(2)?;
        decode_verification_star(self.section_bytes(2)?, section.count, star)
    }

    pub fn spatial_grid(self) -> Result<SpatialGrid, Error> {
        decode_spatial_grid(self.section_bytes(3)?)
    }

    pub fn spatial_cell_range(self, cell: u32) -> Result<Range<u32>, Error> {
        let grid = self.spatial_grid()?;
        if cell >= grid.cell_count() {
            return Err(Error::InvalidSpatialCell { cell });
        }
        let bytes = self.section_bytes(3)?;
        let cell_offset = usize::try_from(cell)
            .ok()
            .and_then(|value| value.checked_mul(4))
            .and_then(|value| value.checked_add(8))
            .ok_or(Error::ArithmeticOverflow)?;
        let start = read_u32(bytes, cell_offset).ok_or(Error::InvalidSpatialCell { cell })?;
        let end = read_u32(bytes, cell_offset + 4).ok_or(Error::InvalidSpatialCell { cell })?;
        Ok(start..end)
    }

    /// Conservatively enumerate a spherical cap and apply an f64 chord gate.
    ///
    /// Records are brightness-sorted in every cell. Supplying a maximum q4
    /// magnitude therefore permits an early stop in each cell. This routine
    /// allocates nothing and handles longitude wrap and caps containing a pole.
    pub fn visit_cap(
        self,
        center: [f64; 3],
        radius_radians: f64,
        max_magnitude_q4: Option<i8>,
        visitor: &mut impl FnMut(u32, VerificationStar) -> ControlFlow<()>,
    ) -> Result<CapStats, Error> {
        self.visit_cap_bounded(
            center,
            radius_radians,
            max_magnitude_q4,
            CapBudget::UNLIMITED,
            visitor,
        )
    }

    /// Bounded cap enumeration that stops before either hard limit is exceeded.
    ///
    /// Logical bytes include the spatial-grid header, two prefix entries for
    /// each visited cell, and each eight-byte verification record. The fixed
    /// file header and section table are treated as resident metadata.
    pub fn visit_cap_bounded(
        self,
        center: [f64; 3],
        radius_radians: f64,
        max_magnitude_q4: Option<i8>,
        budget: CapBudget,
        visitor: &mut impl FnMut(u32, VerificationStar) -> ControlFlow<()>,
    ) -> Result<CapStats, Error> {
        self.visit_cap_bounded_cancelable(
            center,
            radius_radians,
            max_magnitude_q4,
            budget,
            &mut || false,
            visitor,
        )
    }

    /// Cancellation-aware bounded cap enumeration. Cancellation is distinct
    /// from budget exhaustion; callers own the predicate and therefore retain
    /// the reason it requested an early return.
    pub fn visit_cap_bounded_cancelable(
        self,
        center: [f64; 3],
        radius_radians: f64,
        max_magnitude_q4: Option<i8>,
        budget: CapBudget,
        should_stop: &mut dyn FnMut() -> bool,
        visitor: &mut impl FnMut(u32, VerificationStar) -> ControlFlow<()>,
    ) -> Result<CapStats, Error> {
        if center.iter().any(|value| !value.is_finite())
            || !radius_radians.is_finite()
            || !(0.0..=core::f64::consts::PI).contains(&radius_radians)
        {
            return Err(Error::InvalidCap);
        }
        let length_squared = center.iter().map(|value| value * value).sum::<f64>();
        if !length_squared.is_finite() || length_squared <= f64::MIN_POSITIVE {
            return Err(Error::InvalidCap);
        }
        let inverse_length = length_squared.sqrt().recip();
        let center = center.map(|value| value * inverse_length);
        let mut stats = CapStats::default();
        if !charge_bytes(
            &mut stats.bytes_read,
            &mut stats.budget_exhausted,
            budget.max_bytes,
            4,
        ) {
            return Ok(stats);
        }
        let grid = self.spatial_grid()?;
        let verification_section = self.section_info(2)?;
        let verification_bytes = self.section_bytes(2)?;
        let latitude = center[2].clamp(-1.0, 1.0).asin();
        let minimum_latitude = (latitude - radius_radians).max(-core::f64::consts::FRAC_PI_2);
        let maximum_latitude = (latitude + radius_radians).min(core::f64::consts::FRAC_PI_2);
        let z_bin = |z: f64| {
            (((z + 1.0) * 0.5 * f64::from(grid.z_bins)).floor() as u16).min(grid.z_bins - 1)
        };
        // One-bin expansion covers trig/floor rounding exactly on a cell seam.
        let minimum_z_bin = z_bin(minimum_latitude.sin()).saturating_sub(1);
        let maximum_z_bin = z_bin(maximum_latitude.sin())
            .saturating_add(1)
            .min(grid.z_bins - 1);

        let longitude = center[1].atan2(center[0]);
        let reaches_pole = radius_radians >= core::f64::consts::FRAC_PI_2 - latitude.abs();
        let longitude_radius = if reaches_pole {
            core::f64::consts::PI
        } else {
            (radius_radians.sin() / latitude.cos())
                .clamp(-1.0, 1.0)
                .asin()
                .abs()
        };
        let longitude_width = 2.0 * core::f64::consts::PI / f64::from(grid.longitude_bins);
        // Chord distance avoids cancellation in cos(radius) at arcsecond
        // scales. The epsilon term is a conservative floating-point expansion;
        // the solver performs its own final exact angular gate.
        let maximum_chord_squared =
            4.0 * (0.5 * radius_radians).sin().powi(2) + 64.0 * f64::EPSILON;
        let mut work_since_poll = 0_u16;
        for z in minimum_z_bin..=maximum_z_bin {
            for longitude_bin in 0..grid.longitude_bins {
                if work_since_poll == 0 && should_stop() {
                    return Ok(stats);
                }
                work_since_poll = work_since_poll.wrapping_add(1) & 0xff;
                let cell_longitude =
                    -core::f64::consts::PI + (f64::from(longitude_bin) + 0.5) * longitude_width;
                let mut delta = (cell_longitude - longitude).abs();
                if delta > core::f64::consts::PI {
                    delta = 2.0 * core::f64::consts::PI - delta;
                }
                if delta > longitude_radius + 0.5 * longitude_width + 16.0 * f64::EPSILON {
                    continue;
                }
                if stats.cells_visited >= budget.max_cells {
                    stats.budget_exhausted = true;
                    return Ok(stats);
                }
                if !charge_bytes(
                    &mut stats.bytes_read,
                    &mut stats.budget_exhausted,
                    budget.max_bytes,
                    8,
                ) {
                    return Ok(stats);
                }
                stats.cells_visited = stats.cells_visited.saturating_add(1);
                let cell = u32::from(z) * u32::from(grid.longitude_bins) + u32::from(longitude_bin);
                for star_id in self.spatial_cell_range(cell)? {
                    if work_since_poll == 0 && should_stop() {
                        return Ok(stats);
                    }
                    work_since_poll = work_since_poll.wrapping_add(1) & 0xff;
                    if stats.records_examined >= budget.max_records {
                        stats.budget_exhausted = true;
                        return Ok(stats);
                    }
                    if !charge_bytes(
                        &mut stats.bytes_read,
                        &mut stats.budget_exhausted,
                        budget.max_bytes,
                        VERIFICATION_STAR_SIZE as u64,
                    ) {
                        return Ok(stats);
                    }
                    let star = decode_verification_star(
                        verification_bytes,
                        verification_section.count,
                        star_id,
                    )?;
                    stats.records_examined = stats.records_examined.saturating_add(1);
                    if max_magnitude_q4.is_some_and(|limit| star.magnitude_q4 > limit) {
                        break;
                    }
                    let direction = star.direction_f64();
                    let chord_squared = direction
                        .iter()
                        .zip(center)
                        .map(|(&star, center)| (star - center).powi(2))
                        .sum::<f64>();
                    if chord_squared > maximum_chord_squared {
                        continue;
                    }
                    stats.stars_emitted = stats.stars_emitted.saturating_add(1);
                    if visitor(star_id, star).is_break() {
                        return Ok(stats);
                    }
                }
            }
        }
        Ok(stats)
    }

    fn validate_sections(self, payload_start: usize, verify_checksums: bool) -> Result<(), Error> {
        let mut expected_offset = payload_start;
        for section_index in 0..SECTION_COUNT {
            let info = self.section_info(section_index)?;
            let offset = usize::try_from(info.offset).map_err(|_| Error::ArithmeticOverflow)?;
            let length = usize::try_from(info.length).map_err(|_| Error::ArithmeticOverflow)?;
            if offset != expected_offset || offset & 7 != 0 {
                return Err(Error::NonCanonical("section offset or alignment"));
            }
            let end = offset
                .checked_add(length)
                .ok_or(Error::ArithmeticOverflow)?;
            let payload = self
                .data
                .get(offset..end)
                .ok_or(Error::SectionOutOfBounds {
                    section: section_index,
                })?;
            if verify_checksums && crc32c(payload) != info.checksum {
                return Err(Error::SectionChecksum {
                    section: section_index,
                });
            }
            let next_offset = align8(end).ok_or(Error::ArithmeticOverflow)?;
            let padding_end = if section_index + 1 == SECTION_COUNT {
                end
            } else {
                next_offset
            };
            let padding = self
                .data
                .get(end..padding_end)
                .ok_or(Error::SectionOutOfBounds {
                    section: section_index,
                })?;
            if padding.iter().any(|&byte| byte != 0) {
                return Err(Error::NonCanonical("section padding"));
            }
            expected_offset = next_offset;
            if section_index + 1 == SECTION_COUNT && end != self.data.len() {
                return Err(Error::FileLengthMismatch);
            }
        }
        Ok(())
    }

    fn validate_layout(self, validate_records: bool) -> Result<(), Error> {
        let level_section =
            self.fixed_section_info(0, SectionKind::LevelDescriptors, LEVEL_DESCRIPTOR_SIZE)?;
        if level_section.count != u32::from(self.level_count) {
            return Err(Error::InvalidSection { section: 0 });
        }
        let pattern_stars =
            self.fixed_section_info(1, SectionKind::PatternStars, PATTERN_STAR_SIZE)?;
        if pattern_stars.count > MAX_PATTERN_STARS {
            return Err(Error::InvalidSection { section: 1 });
        }
        let verification_stars =
            self.fixed_section_info(2, SectionKind::VerificationStars, VERIFICATION_STAR_SIZE)?;
        if verification_stars.count > MAX_VERIFICATION_STARS {
            return Err(Error::InvalidSection { section: 2 });
        }
        let level_bytes = self.section_bytes_from_info(0, level_section)?;
        let pattern_star_bytes = self.section_bytes_from_info(1, pattern_stars)?;
        let verification_star_bytes = self.section_bytes_from_info(2, verification_stars)?;
        let magnitude_counts = self.validate_spatial_directory(
            validate_records,
            verification_stars.count,
            verification_star_bytes,
        )?;

        if validate_records {
            for star in 0..pattern_stars.count {
                let verification =
                    decode_pattern_star(pattern_star_bytes, pattern_stars.count, star)?;
                if verification >= verification_stars.count {
                    return Err(Error::InvalidPatternStar { star });
                }
            }
        }

        let patterns = self.validate_shared_pattern_layout()?;

        let mut level_windows = [(0_u16, 0_u16, 0_u32); MAX_LEVELS as usize];
        let mut previous_min_fov = None;
        for level in 0..self.level_count {
            let offset = usize::from(level)
                .checked_mul(LEVEL_DESCRIPTOR_SIZE)
                .ok_or(Error::ArithmeticOverflow)?;
            let descriptor = self.read_level_descriptor(level_bytes, offset, level)?;
            if descriptor.min_fov_mdeg > descriptor.max_fov_mdeg
                || descriptor.min_largest_edge_q16 > descriptor.max_largest_edge_q16
                || descriptor.pattern_count > patterns.pattern_count
                || (descriptor.pattern_count == 0
                    && (descriptor.min_largest_edge_q16 != 0
                        || descriptor.max_largest_edge_q16 != 0))
                || descriptor.verification_star_count > verification_stars.count
                || (descriptor.max_verification_magnitude_q4.is_none()
                    && descriptor.verification_star_count != verification_stars.count)
                || previous_min_fov.is_some_and(|previous| descriptor.min_fov_mdeg <= previous)
            {
                return Err(Error::InvalidLevel { level });
            }
            if let Some(cumulative) = magnitude_counts {
                let expected = descriptor
                    .max_verification_magnitude_q4
                    .map_or(verification_stars.count, |limit| {
                        cumulative[magnitude_bin(limit)]
                    });
                if descriptor.verification_star_count != expected {
                    return Err(Error::InvalidLevel { level });
                }
            }
            level_windows[usize::from(level)] = (
                descriptor.min_largest_edge_q16,
                descriptor.max_largest_edge_q16,
                descriptor.pattern_count,
            );
            previous_min_fov = Some(descriptor.min_fov_mdeg);
        }
        if validate_records {
            Self::validate_shared_pattern_records(
                &level_windows[..usize::from(self.level_count)],
                patterns,
                pattern_stars.count,
            )?;
        }
        Ok(())
    }

    fn validate_spatial_directory(
        self,
        validate_records: bool,
        verification_count: u32,
        star_bytes: &[u8],
    ) -> Result<Option<[u32; 256]>, Error> {
        let info = self.section_info_of_kind(3, SectionKind::SpatialDirectory)?;
        let bytes = self.section_bytes_from_info(3, info)?;
        let grid = decode_spatial_grid(bytes)?;
        if grid.z_bins == 0 || grid.longitude_bins == 0 {
            return Err(Error::InvalidSection { section: 3 });
        }
        let cell_count = grid.cell_count();
        if cell_count > MAX_SPATIAL_CELLS || info.count != cell_count {
            return Err(Error::InvalidSection { section: 3 });
        }
        let expected_length = usize::try_from(cell_count)
            .ok()
            .and_then(|count| count.checked_add(1))
            .and_then(|count| count.checked_mul(4))
            .and_then(|length| length.checked_add(8))
            .ok_or(Error::ArithmeticOverflow)?;
        if bytes.len() != expected_length {
            return Err(Error::InvalidSection { section: 3 });
        }
        if read_u32(bytes, 4) != Some(verification_count) || read_u32(bytes, 8) != Some(0) {
            return Err(Error::InvalidSection { section: 3 });
        }
        if !validate_records {
            return Ok(None);
        }
        let mut previous = 0_u32;
        let mut magnitude_histogram = [0_u32; 256];
        for index in 0..=cell_count {
            let offset = 8_usize
                .checked_add(
                    usize::try_from(index)
                        .map_err(|_| Error::ArithmeticOverflow)?
                        .checked_mul(4)
                        .ok_or(Error::ArithmeticOverflow)?,
                )
                .ok_or(Error::ArithmeticOverflow)?;
            let value = read_u32(bytes, offset).ok_or(Error::InvalidSection { section: 3 })?;
            if value < previous || value > verification_count {
                return Err(Error::InvalidSection { section: 3 });
            }
            let mut previous_magnitude = None;
            for star in previous..value {
                let record = decode_verification_star(star_bytes, verification_count, star)?;
                let magnitude = record.magnitude_q4;
                if previous_magnitude.is_some_and(|previous| magnitude < previous) {
                    return Err(Error::InvalidSection { section: 2 });
                }
                magnitude_histogram[magnitude_bin(magnitude)] += 1;
                let decoded = record.direction_f64();
                if spatial_cell_for_direction(decoded, grid.z_bins, grid.longitude_bins)
                    != index.saturating_sub(1)
                {
                    return Err(Error::InvalidSection { section: 3 });
                }
                previous_magnitude = Some(magnitude);
            }
            previous = value;
        }
        if previous != verification_count {
            return Err(Error::InvalidSection { section: 3 });
        }
        Ok(Some(cumulative_magnitude_histogram(magnitude_histogram)))
    }

    fn validate_shared_pattern_layout(self) -> Result<PatternLayout<'a>, Error> {
        let primary_index = PRIMARY_SECTION_INDEX;
        let secondary_index = SECONDARY_SECTION_INDEX;
        let patterns_index = PATTERNS_SECTION_INDEX;
        let primary = self.fixed_section_info(
            primary_index,
            SectionKind::PrimaryDirectory,
            core::mem::size_of::<u32>(),
        )?;
        if primary.count != PRIMARY_DIRECTORY_COUNT as u32 {
            return Err(Error::InvalidSection {
                section: primary_index,
            });
        }
        let secondary = self.fixed_section_info(
            secondary_index,
            SectionKind::SecondaryDirectory,
            SECONDARY_ENTRY_SIZE,
        )?;
        if secondary.count > MAX_SECONDARY_ENTRIES {
            return Err(Error::InvalidSection {
                section: secondary_index,
            });
        }
        let patterns =
            self.fixed_section_info(patterns_index, SectionKind::Patterns, PATTERN_RECORD_SIZE)?;
        if patterns.count > MAX_PATTERNS {
            return Err(Error::InvalidSection {
                section: patterns_index,
            });
        }
        Ok(PatternLayout {
            primary: self.section_bytes_from_info(primary_index, primary)?,
            secondary: self.section_bytes_from_info(secondary_index, secondary)?,
            patterns: self.section_bytes_from_info(patterns_index, patterns)?,
            secondary_count: secondary.count,
            pattern_count: patterns.count,
        })
    }

    fn validate_shared_pattern_records(
        level_windows: &[(u16, u16, u32)],
        layout: PatternLayout<'a>,
        pattern_star_count: u32,
    ) -> Result<(), Error> {
        let primary_index = PRIMARY_SECTION_INDEX;
        let secondary_index = SECONDARY_SECTION_INDEX;
        let patterns_index = PATTERNS_SECTION_INDEX;
        let primary_bytes = layout.primary;
        if read_u32(primary_bytes, 0) != Some(0) {
            return Err(Error::InvalidSection {
                section: primary_index,
            });
        }
        let mut previous = 0_u32;
        for index in 0..PRIMARY_DIRECTORY_COUNT {
            let value = read_u32(primary_bytes, index * 4).ok_or(Error::InvalidSection {
                section: primary_index,
            })?;
            if value < previous || value > layout.secondary_count {
                return Err(Error::InvalidSection {
                    section: primary_index,
                });
            }
            previous = value;
        }
        if previous != layout.secondary_count {
            return Err(Error::InvalidSection {
                section: primary_index,
            });
        }

        let secondary_bytes = layout.secondary;
        let pattern_bytes = layout.patterns;
        let mut expected_pattern_start = 0_u32;
        let mut level_window_counts = [0_u32; MAX_LEVELS as usize];
        for primary in 0..PRIMARY_BUCKET_COUNT {
            let secondary_start =
                read_u32(primary_bytes, primary * 4).ok_or(Error::InvalidSection {
                    section: primary_index,
                })?;
            let secondary_end =
                read_u32(primary_bytes, (primary + 1) * 4).ok_or(Error::InvalidSection {
                    section: primary_index,
                })?;
            let mut previous_key = None;
            for secondary_entry in secondary_start..secondary_end {
                let entry_offset = usize::try_from(secondary_entry)
                    .ok()
                    .and_then(|value| value.checked_mul(SECONDARY_ENTRY_SIZE))
                    .ok_or(Error::ArithmeticOverflow)?;
                let key = read_u16(secondary_bytes, entry_offset).ok_or(Error::InvalidSection {
                    section: secondary_index,
                })?;
                if key >= 1 << 10
                    || previous_key.is_some_and(|previous| key <= previous)
                    || read_u16(secondary_bytes, entry_offset + 2) != Some(0)
                {
                    return Err(Error::InvalidSection {
                        section: secondary_index,
                    });
                }
                previous_key = Some(key);
                let pattern_start =
                    read_u32(secondary_bytes, entry_offset + 4).ok_or(Error::InvalidSection {
                        section: secondary_index,
                    })?;
                let pattern_end =
                    read_u32(secondary_bytes, entry_offset + 8).ok_or(Error::InvalidSection {
                        section: secondary_index,
                    })?;
                if pattern_start != expected_pattern_start
                    || pattern_end < pattern_start
                    || pattern_end > layout.pattern_count
                {
                    return Err(Error::InvalidSection {
                        section: secondary_index,
                    });
                }
                let mut previous_edge = None;
                for pattern in pattern_start..pattern_end {
                    let record_offset = usize::try_from(pattern)
                        .ok()
                        .and_then(|value| value.checked_mul(PATTERN_RECORD_SIZE))
                        .ok_or(Error::ArithmeticOverflow)?;
                    let record = pattern_bytes
                        .get(record_offset..record_offset + PATTERN_RECORD_SIZE)
                        .ok_or(Error::InvalidSection {
                            section: patterns_index,
                        })?;
                    let refs = unpack_pattern_refs(record).ok_or(Error::InvalidSection {
                        section: patterns_index,
                    })?;
                    if refs.iter().any(|&star| star >= pattern_star_count) {
                        return Err(Error::InvalidSection {
                            section: patterns_index,
                        });
                    }
                    let lows = read_u16(record, 10).ok_or(Error::InvalidSection {
                        section: patterns_index,
                    })?;
                    if lows & 0x8000 != 0 {
                        return Err(Error::InvalidSection {
                            section: patterns_index,
                        });
                    }
                    let edge = read_u16(record, 12).ok_or(Error::InvalidSection {
                        section: patterns_index,
                    })?;
                    let ratios = unpack_ratios(primary as u16, key, lows);
                    if edge == 0 || ratios.windows(2).any(|pair| pair[0] > pair[1]) {
                        return Err(Error::InvalidSection {
                            section: patterns_index,
                        });
                    }
                    if refs.windows(2).any(|pair| pair[0] >= pair[1]) {
                        return Err(Error::InvalidSection {
                            section: patterns_index,
                        });
                    }
                    if previous_edge.is_some_and(|previous| edge < previous) {
                        return Err(Error::InvalidSection {
                            section: patterns_index,
                        });
                    }
                    previous_edge = Some(edge);
                    for (count, &(minimum, maximum, _)) in
                        level_window_counts.iter_mut().zip(level_windows)
                    {
                        if (minimum..=maximum).contains(&edge) {
                            *count += 1;
                        }
                    }
                }
                expected_pattern_start = pattern_end;
            }
        }
        if expected_pattern_start != layout.pattern_count {
            return Err(Error::InvalidSection {
                section: patterns_index,
            });
        }
        for (level, (&observed, &(_, _, expected))) in
            level_window_counts.iter().zip(level_windows).enumerate()
        {
            if observed != expected {
                return Err(Error::InvalidLevel {
                    level: level as u16,
                });
            }
        }
        Ok(())
    }

    fn read_level_descriptor(
        self,
        bytes: &[u8],
        offset: usize,
        level: u16,
    ) -> Result<LevelDescriptor, Error> {
        let verification_star_count =
            read_u32(bytes, offset + 12).ok_or(Error::InvalidLevel { level })?;
        if bytes.get(offset..offset + LEVEL_DESCRIPTOR_SIZE).is_none()
            || read_u32(bytes, offset + 36) != Some(0)
            || read_u32(bytes, offset + 16) != Some(PRIMARY_SECTION_INDEX)
            || read_u32(bytes, offset + 20) != Some(SECONDARY_SECTION_INDEX)
            || read_u32(bytes, offset + 24) != Some(PATTERNS_SECTION_INDEX)
        {
            return Err(Error::InvalidLevel { level });
        }
        let tolerance = bytes[offset + 28];
        let descriptor_flags = bytes[offset + 31];
        if tolerance > MAX_RATIO_TOLERANCE_Q8 {
            return Err(Error::InvalidLevel { level });
        }
        if descriptor_flags & !1 != 0 || (descriptor_flags == 0 && bytes[offset + 30] != 0) {
            return Err(Error::InvalidLevel { level });
        }
        Ok(LevelDescriptor {
            min_fov_mdeg: read_u32(bytes, offset).ok_or(Error::InvalidLevel { level })?,
            max_fov_mdeg: read_u32(bytes, offset + 4).ok_or(Error::InvalidLevel { level })?,
            pattern_count: read_u32(bytes, offset + 8).ok_or(Error::InvalidLevel { level })?,
            default_tolerance_q8: tolerance,
            neighbor_level_radius: bytes[offset + 29],
            max_verification_magnitude_q4: (descriptor_flags & 1 != 0)
                .then_some(bytes[offset + 30] as i8),
            verification_star_count,
            min_largest_edge_q16: read_u16(bytes, offset + 32)
                .ok_or(Error::InvalidLevel { level })?,
            max_largest_edge_q16: read_u16(bytes, offset + 34)
                .ok_or(Error::InvalidLevel { level })?,
        })
    }

    fn section_info_of_kind(self, index: u32, kind: SectionKind) -> Result<SectionInfo, Error> {
        let info = self.section_info(index)?;
        if info.kind != kind {
            return Err(Error::InvalidSection { section: index });
        }
        Ok(info)
    }

    fn fixed_section_info(
        self,
        section: u32,
        kind: SectionKind,
        record_size: usize,
    ) -> Result<SectionInfo, Error> {
        let info = self.section_info_of_kind(section, kind)?;
        let expected_length = usize::try_from(info.count)
            .ok()
            .and_then(|count| count.checked_mul(record_size))
            .ok_or(Error::ArithmeticOverflow)?;
        if usize::try_from(info.length) != Ok(expected_length) {
            return Err(Error::InvalidSection { section });
        }
        Ok(info)
    }

    fn section_entry_offset(self, section: u32) -> Result<usize, Error> {
        if section >= SECTION_COUNT {
            return Err(Error::InvalidSection { section });
        }
        usize::try_from(section)
            .ok()
            .and_then(|value| value.checked_mul(SECTION_ENTRY_SIZE))
            .and_then(|value| value.checked_add(HEADER_SIZE))
            .ok_or(Error::ArithmeticOverflow)
    }

    fn section_bytes(self, section: u32) -> Result<&'a [u8], Error> {
        let info = self.section_info(section)?;
        self.section_bytes_from_info(section, info)
    }

    fn section_bytes_from_info(self, section: u32, info: SectionInfo) -> Result<&'a [u8], Error> {
        let start = usize::try_from(info.offset).map_err(|_| Error::ArithmeticOverflow)?;
        let length = usize::try_from(info.length).map_err(|_| Error::ArithmeticOverflow)?;
        let end = start.checked_add(length).ok_or(Error::ArithmeticOverflow)?;
        self.data
            .get(start..end)
            .ok_or(Error::SectionOutOfBounds { section })
    }
}

#[derive(Clone, Copy)]
pub struct LevelView<'a> {
    index: IndexView<'a>,
    level: u16,
}

impl fmt::Debug for LevelView<'_> {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("LevelView")
            .field("level", &self.level)
            .field("descriptor", &self.descriptor())
            .finish()
    }
}

impl<'a> LevelView<'a> {
    pub fn number(self) -> u16 {
        self.level
    }

    pub fn descriptor(self) -> LevelDescriptor {
        // Construction of LevelView proves the descriptor exists and parse validated it.
        self.index
            .level_descriptor(self.level)
            .expect("validated level descriptor")
    }

    /// Visit matching records. Ratio comparison is entirely integer based.
    ///
    /// The directory consumes the upper five bits of every q8 ratio: three in
    /// the primary radix and two in the sparse secondary radix. The remaining
    /// fifteen bits are stored in each 14-byte pattern record. Records within a
    /// bucket are ordered by `largest_edge_q16`, so the edge range is applied by
    /// two binary searches before records are decoded.
    pub fn visit_candidates(
        self,
        query_ratios: [u8; 5],
        tolerance_q8: u8,
        edge_range: RangeInclusive<u16>,
        visitor: &mut impl FnMut(CandidateRecord) -> ControlFlow<()>,
    ) -> Result<QueryStats, Error> {
        self.visit_candidates_bounded(
            query_ratios,
            tolerance_q8,
            edge_range,
            QueryBudget::UNLIMITED,
            visitor,
        )
    }

    /// Bounded variant of [`Self::visit_candidates`].
    ///
    /// `max_records` counts 14-byte pattern records examined. `max_bytes` is a
    /// conservative logical-read count covering radix probes, edge binary
    /// searches, pattern records, and pattern-star mappings. The implementation
    /// stops before either bound would be exceeded.
    pub fn visit_candidates_bounded(
        self,
        query_ratios: [u8; 5],
        tolerance_q8: u8,
        edge_range: RangeInclusive<u16>,
        budget: QueryBudget,
        visitor: &mut impl FnMut(CandidateRecord) -> ControlFlow<()>,
    ) -> Result<QueryStats, Error> {
        self.visit_candidates_bounded_cancelable(
            query_ratios,
            tolerance_q8,
            edge_range,
            budget,
            &mut || false,
            visitor,
        )
    }

    /// Cancellation-aware bounded pattern enumeration. Cancellation does not
    /// set `budget_exhausted`; the caller owns the predicate and its reason.
    pub fn visit_candidates_bounded_cancelable(
        self,
        query_ratios: [u8; 5],
        tolerance_q8: u8,
        edge_range: RangeInclusive<u16>,
        budget: QueryBudget,
        should_stop: &mut dyn FnMut() -> bool,
        visitor: &mut impl FnMut(CandidateRecord) -> ControlFlow<()>,
    ) -> Result<QueryStats, Error> {
        if tolerance_q8 > MAX_RATIO_TOLERANCE_Q8 {
            return Err(Error::RatioToleranceTooLarge {
                tolerance: tolerance_q8,
            });
        }
        if edge_range.is_empty() {
            return Ok(QueryStats::default());
        }
        let mut low_high = [(0_u8, 0_u8); 5];
        for (index, &ratio) in query_ratios.iter().enumerate() {
            low_high[index] = (
                ratio.saturating_sub(tolerance_q8) >> 3,
                ratio.saturating_add(tolerance_q8) >> 3,
            );
        }
        let mut stats = QueryStats::default();
        let bucket_query = BucketQuery {
            ratios: query_ratios,
            tolerance: tolerance_q8,
            minimum_edge: *edge_range.start(),
            maximum_edge: *edge_range.end(),
        };
        let mut work_since_poll = 0_u16;
        for a in low_high[0].0..=low_high[0].1 {
            for b in low_high[1].0..=low_high[1].1 {
                for c in low_high[2].0..=low_high[2].1 {
                    for d in low_high[3].0..=low_high[3].1 {
                        for e in low_high[4].0..=low_high[4].1 {
                            if work_since_poll == 0 && should_stop() {
                                return Ok(stats);
                            }
                            work_since_poll = work_since_poll.wrapping_add(1) & 0xff;
                            let high = [a << 3, b << 3, c << 3, d << 3, e << 3];
                            let should_break = self.visit_one_bucket(
                                pack_primary(high),
                                pack_secondary(high),
                                bucket_query,
                                budget,
                                &mut stats,
                                &mut work_since_poll,
                                should_stop,
                                visitor,
                            )?;
                            if should_break {
                                return Ok(stats);
                            }
                        }
                    }
                }
            }
        }
        Ok(stats)
    }

    // These are the decoded query fields plus caller-owned accounting and
    // callbacks; grouping them would only obscure the zero-allocation scan.
    #[allow(clippy::too_many_arguments)]
    fn visit_one_bucket(
        self,
        primary: u16,
        secondary: u16,
        query: BucketQuery,
        budget: QueryBudget,
        stats: &mut QueryStats,
        work_since_poll: &mut u16,
        should_stop: &mut dyn FnMut() -> bool,
        visitor: &mut impl FnMut(CandidateRecord) -> ControlFlow<()>,
    ) -> Result<bool, Error> {
        if !charge_bytes(
            &mut stats.bytes_read,
            &mut stats.budget_exhausted,
            budget.max_bytes,
            8,
        ) {
            return Ok(true);
        }
        stats.primary_buckets_visited = stats.primary_buckets_visited.saturating_add(1);
        let primary_bytes = self.index.section_bytes(PRIMARY_SECTION_INDEX)?;
        let primary_offset = usize::from(primary) * 4;
        let secondary_start = read_u32(primary_bytes, primary_offset)
            .ok_or(Error::InvalidLevel { level: self.level })?;
        let secondary_end = read_u32(primary_bytes, primary_offset + 4)
            .ok_or(Error::InvalidLevel { level: self.level })?;
        if secondary_start == secondary_end {
            return Ok(false);
        }
        let secondary_bytes = self.index.section_bytes(SECONDARY_SECTION_INDEX)?;

        let secondary_probe_bytes = u64::from(binary_probe_count(secondary_end - secondary_start))
            .saturating_mul(2)
            .saturating_add(10);
        if !charge_bytes(
            &mut stats.bytes_read,
            &mut stats.budget_exhausted,
            budget.max_bytes,
            secondary_probe_bytes,
        ) {
            return Ok(true);
        }

        let mut left = secondary_start;
        let mut right = secondary_end;
        while left < right {
            let middle = left + (right - left) / 2;
            let offset = usize::try_from(middle)
                .ok()
                .and_then(|value| value.checked_mul(SECONDARY_ENTRY_SIZE))
                .ok_or(Error::ArithmeticOverflow)?;
            let key = read_u16(secondary_bytes, offset)
                .ok_or(Error::InvalidLevel { level: self.level })?;
            if key < secondary {
                left = middle + 1;
            } else {
                right = middle;
            }
        }
        if left == secondary_end {
            return Ok(false);
        }
        let entry_offset = usize::try_from(left)
            .ok()
            .and_then(|value| value.checked_mul(SECONDARY_ENTRY_SIZE))
            .ok_or(Error::ArithmeticOverflow)?;
        if read_u16(secondary_bytes, entry_offset) != Some(secondary) {
            return Ok(false);
        }
        let start = read_u32(secondary_bytes, entry_offset + 4)
            .ok_or(Error::InvalidLevel { level: self.level })?;
        let end = read_u32(secondary_bytes, entry_offset + 8)
            .ok_or(Error::InvalidLevel { level: self.level })?;
        let pattern_bytes = self.index.section_bytes(PATTERNS_SECTION_INDEX)?;
        let edge_probe_bytes = u64::from(binary_probe_count(end - start)).saturating_mul(4);
        if !charge_bytes(
            &mut stats.bytes_read,
            &mut stats.budget_exhausted,
            budget.max_bytes,
            edge_probe_bytes,
        ) {
            return Ok(true);
        }
        let first = edge_lower_bound(pattern_bytes, start, end, query.minimum_edge, self.level)?;
        let after_last =
            edge_upper_bound(pattern_bytes, first, end, query.maximum_edge, self.level)?;
        let pattern_star_count = self.index.section_info(1)?.count;
        let pattern_star_bytes = self.index.section_bytes(1)?;
        for pattern_index in first..after_last {
            if *work_since_poll == 0 && should_stop() {
                return Ok(true);
            }
            *work_since_poll = work_since_poll.wrapping_add(1) & 0xff;
            if stats.records_examined >= budget.max_records
                || !charge_bytes(
                    &mut stats.bytes_read,
                    &mut stats.budget_exhausted,
                    budget.max_bytes,
                    PATTERN_RECORD_SIZE as u64,
                )
            {
                stats.budget_exhausted = true;
                return Ok(true);
            }
            let offset = usize::try_from(pattern_index)
                .ok()
                .and_then(|value| value.checked_mul(PATTERN_RECORD_SIZE))
                .ok_or(Error::ArithmeticOverflow)?;
            let record = pattern_bytes
                .get(offset..offset + PATTERN_RECORD_SIZE)
                .ok_or(Error::InvalidLevel { level: self.level })?;
            stats.records_examined = stats.records_examined.saturating_add(1);
            let lows = read_u16(record, 10).ok_or(Error::InvalidLevel { level: self.level })?;
            let ratios = unpack_ratios(primary, secondary, lows);
            if ratios
                .iter()
                .zip(query.ratios)
                .any(|(&stored, query_ratio)| stored.abs_diff(query_ratio) > query.tolerance)
            {
                continue;
            }
            let pattern_stars =
                unpack_pattern_refs(record).ok_or(Error::InvalidLevel { level: self.level })?;
            if !charge_bytes(
                &mut stats.bytes_read,
                &mut stats.budget_exhausted,
                budget.max_bytes,
                u64::from(PATTERN_STAR_SIZE as u32 * 4),
            ) {
                return Ok(true);
            }
            let mut verification_stars = [0_u32; 4];
            for (output, &pattern_star) in verification_stars.iter_mut().zip(&pattern_stars) {
                *output =
                    decode_pattern_star(pattern_star_bytes, pattern_star_count, pattern_star)?;
            }
            // Reserve the four eight-byte verification vectors that a solver
            // adapter must read to reconstruct the candidate's exact invariant.
            if !charge_bytes(
                &mut stats.bytes_read,
                &mut stats.budget_exhausted,
                budget.max_bytes,
                4 * VERIFICATION_STAR_SIZE as u64,
            ) {
                return Ok(true);
            }
            let candidate = CandidateRecord {
                verification_stars,
                ratios,
                largest_edge_q16: read_u16(record, 12)
                    .ok_or(Error::InvalidLevel { level: self.level })?,
            };
            stats.candidates_emitted = stats.candidates_emitted.saturating_add(1);
            if visitor(candidate).is_break() {
                return Ok(true);
            }
        }
        Ok(false)
    }
}

fn decode_pattern_star(bytes: &[u8], count: u32, star: u32) -> Result<u32, Error> {
    if star >= count {
        return Err(Error::InvalidPatternStar { star });
    }
    let offset = usize::try_from(star)
        .ok()
        .and_then(|value| value.checked_mul(PATTERN_STAR_SIZE))
        .ok_or(Error::ArithmeticOverflow)?;
    read_u24(bytes, offset).ok_or(Error::InvalidPatternStar { star })
}

fn decode_spatial_grid(bytes: &[u8]) -> Result<SpatialGrid, Error> {
    Ok(SpatialGrid {
        z_bins: read_u16(bytes, 0).ok_or(Error::InvalidSection { section: 3 })?,
        longitude_bins: read_u16(bytes, 2).ok_or(Error::InvalidSection { section: 3 })?,
    })
}

fn decode_verification_star(
    bytes: &[u8],
    count: u32,
    star: u32,
) -> Result<VerificationStar, Error> {
    if star >= count {
        return Err(Error::InvalidVerificationStar { star });
    }
    let offset = usize::try_from(star)
        .ok()
        .and_then(|value| value.checked_mul(VERIFICATION_STAR_SIZE))
        .ok_or(Error::ArithmeticOverflow)?;
    let record = bytes
        .get(offset..offset + VERIFICATION_STAR_SIZE)
        .ok_or(Error::InvalidVerificationStar { star })?;
    Ok(VerificationStar {
        oct_x: read_u24_signed(record, 0).ok_or(Error::InvalidVerificationStar { star })?,
        oct_y: read_u24_signed(record, 3).ok_or(Error::InvalidVerificationStar { star })?,
        magnitude_q4: record[6] as i8,
        flags: record[7],
    })
}

fn charge_bytes(bytes_read: &mut u64, exhausted: &mut bool, limit: u64, bytes: u64) -> bool {
    let Some(updated) = bytes_read.checked_add(bytes) else {
        *exhausted = true;
        return false;
    };
    if updated > limit {
        *exhausted = true;
        return false;
    }
    *bytes_read = updated;
    true
}

fn binary_probe_count(length: u32) -> u32 {
    if length == 0 {
        0
    } else {
        u32::BITS - length.leading_zeros()
    }
}

fn edge_lower_bound(
    records: &[u8],
    mut left: u32,
    mut right: u32,
    edge: u16,
    level: u16,
) -> Result<u32, Error> {
    while left < right {
        let middle = left + (right - left) / 2;
        if record_edge(records, middle, level)? < edge {
            left = middle + 1;
        } else {
            right = middle;
        }
    }
    Ok(left)
}

fn edge_upper_bound(
    records: &[u8],
    mut left: u32,
    mut right: u32,
    edge: u16,
    level: u16,
) -> Result<u32, Error> {
    while left < right {
        let middle = left + (right - left) / 2;
        if record_edge(records, middle, level)? <= edge {
            left = middle + 1;
        } else {
            right = middle;
        }
    }
    Ok(left)
}

fn record_edge(records: &[u8], index: u32, level: u16) -> Result<u16, Error> {
    let offset = usize::try_from(index)
        .ok()
        .and_then(|value| value.checked_mul(PATTERN_RECORD_SIZE))
        .and_then(|value| value.checked_add(12))
        .ok_or(Error::ArithmeticOverflow)?;
    read_u16(records, offset).ok_or(Error::InvalidLevel { level })
}

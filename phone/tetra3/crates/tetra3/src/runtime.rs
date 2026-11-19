//! Phone-side glue between the storage, lens, and solver crates.
//!
//! [`RuntimeIndex`] is deliberately just a borrowed [`tetra3_index::IndexView`].
//! It never copies an index section, directory, pattern, or catalog row. When
//! the view borrows an mmap, all lookup and verification reads remain backed by
//! that mmap.

use std::{error, fmt, ops::ControlFlow};

use tetra3_core::{
    CatalogQuery, CatalogQueryStats, CatalogStar, Invariant, Observation, PatternCandidate,
    PatternIndex, PatternQuery, PixelCentroid, PixelObservation, QueryStats, SolveConfig,
    SolveError, SolveOutcome, SolveRequest, StarCatalog, StarId, UnitVec3,
};
use tetra3_index::{
    CapBudget, Error as IndexError, IndexView, LevelDescriptor, MAX_RATIO_TOLERANCE_Q8,
    QueryBudget, VerificationStar,
};
use tetra3_lens::{Error as LensError, Pixel, PreparedLens, Projection};

#[cfg(test)]
use tetra3_lens::CorrectedCoord;

const EDGE_SCALE: f64 = 65_536.0 / std::f64::consts::PI;
const MAX_LEVEL_RANGES: usize = tetra3_index::MAX_LEVELS as usize;
const VERIFICATION_DENSITY_SAFETY_FACTOR: f64 = 4.0;

/// A checked, zero-copy bridge from `.t3ix` storage to the solver traits.
#[derive(Clone, Copy, Debug)]
pub struct RuntimeIndex<'a> {
    view: IndexView<'a>,
    selected_level: Option<u8>,
    verification_density_hint_enabled: bool,
}

impl<'a> RuntimeIndex<'a> {
    #[must_use]
    pub fn new(view: IndexView<'a>) -> Self {
        Self {
            view,
            selected_level: None,
            verification_density_hint_enabled: true,
        }
    }

    /// Bind both pattern lookup and verification brightness to one horizontal-
    /// FOV level.
    pub fn for_level(view: IndexView<'a>, level: u8) -> Result<Self, RuntimeIndexError> {
        Self::new(view).select_level(level)
    }

    /// Return a copy bound to one level. The descriptor's magnitude cutoff is
    /// used by bounded verification cap reads.
    fn select_level(mut self, level: u8) -> Result<Self, RuntimeIndexError> {
        self.view.level(u16::from(level))?;
        self.selected_level = Some(level);
        Ok(self)
    }

    /// Suppress the index-provided verification-density estimate and make the
    /// solver use its configured fallback density instead.
    ///
    /// It is useful when a caller must preserve an established, deliberately
    /// stricter acceptance threshold for an otherwise unchanged solve mode.
    #[must_use]
    pub const fn without_verification_density_hint(mut self) -> Self {
        self.verification_density_hint_enabled = false;
        self
    }

    #[must_use]
    pub const fn selected_level(self) -> Option<u8> {
        self.selected_level
    }

    #[must_use]
    pub fn verification_magnitude_limit_q4(self) -> Option<i8> {
        self.selected_level_descriptor()
            .and_then(|descriptor| descriptor.max_verification_magnitude_q4)
    }

    /// Exact all-sky cardinality of the catalog visible through the active
    /// verification cutoff, when carried by the index format.
    #[must_use]
    pub fn verification_star_count_hint(self) -> Option<u32> {
        if !self.verification_density_hint_enabled {
            return None;
        }
        match self.selected_level_descriptor() {
            Some(descriptor) if descriptor.max_verification_magnitude_q4.is_some() => {
                Some(descriptor.verification_star_count)
            }
            Some(_) | None => Some(self.view.verification_star_count()),
        }
    }

    fn selected_level_descriptor(self) -> Option<LevelDescriptor> {
        self.selected_level.map(|level| {
            self.view
                .level(u16::from(level))
                .expect("RuntimeIndex selected level was validated")
                .descriptor()
        })
    }

    /// Largest stored pattern-edge bin reachable by this runtime's normal
    /// level lookup policy. A selected level includes only the direct fallback
    /// neighbors named by that level's descriptor; an unselected runtime can
    /// reach every level.
    fn maximum_pattern_edge_q16(self) -> Result<Option<u16>, RuntimeIndexError> {
        let mut maximum = None;
        let (first, exclusive_end) = if let Some(selected) = self.selected_level {
            let descriptor = self.view.level(u16::from(selected))?.descriptor();
            let radius = u16::from(descriptor.neighbor_level_radius);
            (
                u16::from(selected).saturating_sub(radius),
                u16::from(selected)
                    .saturating_add(radius)
                    .saturating_add(1)
                    .min(self.view.level_count()),
            )
        } else {
            (0, self.view.level_count())
        };
        for level in first..exclusive_end {
            let descriptor = self.view.level(level)?.descriptor();
            if descriptor.pattern_count != 0 {
                maximum = Some(
                    maximum.map_or(descriptor.max_largest_edge_q16, |known: u16| {
                        known.max(descriptor.max_largest_edge_q16)
                    }),
                );
            }
        }
        Ok(maximum)
    }

    /// Completeness-preserving maximum observed seed edge for the patterns
    /// reachable through this runtime's level policy.
    ///
    /// Stored edges are floor-quantized. An observed edge larger than the
    /// stored maximum can still overlap its lookup bin through the solver's
    /// relative edge tolerance, so this returns the upper q16 bin boundary
    /// divided by `1 - largest_edge_relative_tolerance`.
    pub fn maximum_observed_pattern_edge_rad(
        self,
        largest_edge_relative_tolerance: f64,
    ) -> Result<Option<f64>, RuntimeIndexError> {
        if !largest_edge_relative_tolerance.is_finite()
            || !(0.0..1.0).contains(&largest_edge_relative_tolerance)
        {
            return Err(RuntimeIndexError::InvalidQuery);
        }
        let Some(maximum_q16) = self.maximum_pattern_edge_q16()? else {
            return Ok(None);
        };
        let stored_bin_upper_rad = (f64::from(maximum_q16) + 1.0) / EDGE_SCALE;
        Ok(Some(
            (stored_bin_upper_rad / (1.0 - largest_edge_relative_tolerance))
                .min(std::f64::consts::PI),
        ))
    }

    fn decode_catalog_star(
        self,
        id: u32,
        record: VerificationStar,
    ) -> Result<CatalogStar, RuntimeIndexError> {
        let [x, y, z] = record.direction_f64();
        let direction =
            UnitVec3::new(x, y, z).map_err(|_| RuntimeIndexError::InvalidStoredGeometry)?;
        Ok(CatalogStar {
            id: StarId(id),
            direction,
            magnitude: record.magnitude(),
        })
    }

    fn catalog_star(self, id: u32) -> Result<CatalogStar, RuntimeIndexError> {
        self.decode_catalog_star(id, self.view.verification_star(id)?)
    }

    fn candidate_stars(self, star_ids: [u32; 4]) -> Result<[CatalogStar; 4], RuntimeIndexError> {
        Ok([
            self.catalog_star(star_ids[0])?,
            self.catalog_star(star_ids[1])?,
            self.catalog_star(star_ids[2])?,
            self.catalog_star(star_ids[3])?,
        ])
    }

    #[allow(clippy::too_many_arguments)]
    fn visit_level_segment(
        self,
        level_number: u16,
        query: PatternQuery,
        query_ratios: [u8; 5],
        tolerance_q8: u8,
        minimum_edge: u16,
        maximum_edge: u16,
        total: &mut QueryStats,
        should_stop: &mut dyn FnMut() -> bool,
        visitor: &mut dyn FnMut(PatternCandidate) -> ControlFlow<()>,
    ) -> Result<bool, RuntimeIndexError> {
        let remaining_records = query
            .max_records_to_scan
            .saturating_sub(total.records_scanned);
        let remaining_bytes = query.max_bytes_to_read.saturating_sub(total.bytes_read);
        if remaining_records == 0 || remaining_bytes == 0 {
            total.budget_exhausted = true;
            return Ok(false);
        }

        let level = self.view.level(level_number)?;
        let mut callback_error = None;
        let mut visitor_stopped = false;
        let stats = level.visit_candidates_bounded_cancelable(
            query_ratios,
            tolerance_q8,
            minimum_edge..=maximum_edge,
            QueryBudget {
                max_records: remaining_records,
                max_bytes: remaining_bytes,
            },
            should_stop,
            &mut |record| {
                let stars = match self.candidate_stars(record.verification_stars) {
                    Ok(stars) => stars,
                    Err(error) => {
                        callback_error = Some(error);
                        return ControlFlow::Break(());
                    }
                };
                let invariant = match Invariant::from_rays(stars.map(|star| star.direction)) {
                    Ok(invariant) => invariant,
                    Err(_) => {
                        callback_error = Some(RuntimeIndexError::InvalidStoredGeometry);
                        return ControlFlow::Break(());
                    }
                };
                if invariant.max_ratio_delta(query.invariant) > query.ratio_tolerance
                    || !(query.largest_edge_min_rad..=query.largest_edge_max_rad)
                        .contains(&invariant.largest_edge_rad())
                {
                    return ControlFlow::Continue(());
                }

                let flow = visitor(PatternCandidate { stars });
                visitor_stopped = flow.is_break();
                flow
            },
        )?;
        if let Some(error) = callback_error {
            return Err(error);
        }

        total.buckets_touched = total
            .buckets_touched
            .saturating_add(stats.primary_buckets_visited);
        total.records_scanned = total.records_scanned.saturating_add(stats.records_examined);
        total.bytes_read = total.bytes_read.saturating_add(stats.bytes_read);
        total.budget_exhausted |= stats.budget_exhausted;
        Ok(visitor_stopped)
    }
}

/// A malformed query or an invalid row encountered while streaming an index.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub enum RuntimeIndexError {
    Index(IndexError),
    InvalidQuery,
    UnsupportedRatioTolerance,
    InvalidStoredGeometry,
    FovNotCovered { fov_mdeg: u32 },
}

impl fmt::Display for RuntimeIndexError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Index(error) => write!(formatter, "index error: {error}"),
            Self::InvalidQuery => formatter.write_str("invalid runtime index query"),
            Self::UnsupportedRatioTolerance => formatter
                .write_str("pattern ratio tolerance exceeds the compact index lookup limit"),
            Self::InvalidStoredGeometry => {
                formatter.write_str("index contains invalid or degenerate star geometry")
            }
            Self::FovNotCovered { fov_mdeg } => {
                write!(
                    formatter,
                    "index does not cover horizontal FOV {fov_mdeg} mdeg"
                )
            }
        }
    }
}

impl error::Error for RuntimeIndexError {
    fn source(&self) -> Option<&(dyn error::Error + 'static)> {
        match self {
            Self::Index(error) => Some(error),
            _ => None,
        }
    }
}

impl From<IndexError> for RuntimeIndexError {
    fn from(error: IndexError) -> Self {
        Self::Index(error)
    }
}

#[allow(clippy::too_many_arguments)]
fn visit_uncovered_level(
    runtime: RuntimeIndex<'_>,
    level_number: u16,
    descriptor: LevelDescriptor,
    query: PatternQuery,
    query_ratios: [u8; 5],
    tolerance_q8: u8,
    query_minimum_edge: u16,
    query_maximum_edge: u16,
    covered: &mut [(u16, u16); MAX_LEVEL_RANGES],
    covered_len: &mut usize,
    total: &mut QueryStats,
    should_stop: &mut dyn FnMut() -> bool,
    visitor: &mut dyn FnMut(PatternCandidate) -> ControlFlow<()>,
) -> Result<bool, RuntimeIndexError> {
    if descriptor.pattern_count == 0 {
        return Ok(false);
    }
    let minimum_edge = query_minimum_edge.max(descriptor.min_largest_edge_q16);
    let maximum_edge = query_maximum_edge.min(descriptor.max_largest_edge_q16);
    if minimum_edge > maximum_edge {
        return Ok(false);
    }

    let mut segments = [(0_u16, 0_u16); MAX_LEVEL_RANGES + 1];
    let segment_count = uncovered_edge_segments(
        (minimum_edge, maximum_edge),
        &covered[..*covered_len],
        &mut segments,
    );
    let mut visitor_stopped = false;
    for &(segment_minimum, segment_maximum) in &segments[..segment_count] {
        let outcome = runtime.visit_level_segment(
            level_number,
            query,
            query_ratios,
            tolerance_q8,
            segment_minimum,
            segment_maximum,
            total,
            should_stop,
            visitor,
        )?;
        visitor_stopped |= outcome;
        if visitor_stopped || total.budget_exhausted {
            return Ok(visitor_stopped);
        }
    }
    add_covered_edge_range(covered, covered_len, (minimum_edge, maximum_edge));
    Ok(visitor_stopped)
}

fn uncovered_edge_segments(
    requested: (u16, u16),
    covered: &[(u16, u16)],
    output: &mut [(u16, u16); MAX_LEVEL_RANGES + 1],
) -> usize {
    let (minimum, maximum) = (u32::from(requested.0), u32::from(requested.1));
    let mut cursor = minimum;
    let mut output_len = 0_usize;
    for &(covered_minimum, covered_maximum) in covered {
        let (covered_minimum, covered_maximum) =
            (u32::from(covered_minimum), u32::from(covered_maximum));
        if covered_maximum < cursor {
            continue;
        }
        if covered_minimum > maximum {
            break;
        }
        if covered_minimum > cursor {
            output[output_len] = (cursor as u16, (covered_minimum - 1).min(maximum) as u16);
            output_len += 1;
        }
        cursor = cursor.max(covered_maximum.saturating_add(1));
        if cursor > maximum {
            return output_len;
        }
    }
    output[output_len] = (cursor as u16, maximum as u16);
    output_len + 1
}

fn add_covered_edge_range(
    covered: &mut [(u16, u16); MAX_LEVEL_RANGES],
    covered_len: &mut usize,
    range: (u16, u16),
) {
    debug_assert!(*covered_len < covered.len());
    covered[*covered_len] = range;
    *covered_len += 1;
    covered[..*covered_len].sort_unstable_by_key(|value| value.0);

    let mut merged_len = 0_usize;
    for index in 0..*covered_len {
        let current = covered[index];
        if merged_len > 0 && u32::from(current.0) <= u32::from(covered[merged_len - 1].1) + 1 {
            covered[merged_len - 1].1 = covered[merged_len - 1].1.max(current.1);
        } else {
            covered[merged_len] = current;
            merged_len += 1;
        }
    }
    *covered_len = merged_len;
}

impl PatternIndex for RuntimeIndex<'_> {
    type Error = RuntimeIndexError;

    fn visit_candidates(
        &self,
        query: PatternQuery,
        visitor: &mut dyn FnMut(PatternCandidate) -> ControlFlow<()>,
    ) -> Result<QueryStats, Self::Error> {
        self.visit_candidates_cancelable(query, &mut || false, visitor)
    }

    fn visit_candidates_cancelable(
        &self,
        query: PatternQuery,
        should_stop: &mut dyn FnMut() -> bool,
        visitor: &mut dyn FnMut(PatternCandidate) -> ControlFlow<()>,
    ) -> Result<QueryStats, Self::Error> {
        validate_query(query)?;

        // q8 uses floor(r * 256). If two exact ratios differ by at most t,
        // their integer bins can differ by ceil(t * 256). Using ceil here is
        // what makes the approximate lookup conservative at bin boundaries.
        let tolerance_q8 = (query.ratio_tolerance * 256.0).ceil();
        if tolerance_q8 > f64::from(MAX_RATIO_TOLERANCE_Q8) {
            return Err(RuntimeIndexError::UnsupportedRatioTolerance);
        }
        let tolerance_q8 = tolerance_q8 as u8;
        let query_ratios = query.invariant.quantized_q8();
        let edge_min_q16 = quantize_edge(query.largest_edge_min_rad)?;
        let edge_max_q16 = quantize_edge(query.largest_edge_max_rad)?;

        let mut total = QueryStats::default();
        let mut covered = [(0_u16, 0_u16); MAX_LEVEL_RANGES];
        let mut covered_len = 0_usize;

        if let Some(selected) = self.selected_level {
            let selected = u16::from(selected);
            let selected_descriptor = self.view.level(selected)?.descriptor();
            let selected_outcome = visit_uncovered_level(
                *self,
                selected,
                selected_descriptor,
                query,
                query_ratios,
                tolerance_q8,
                edge_min_q16,
                edge_max_q16,
                &mut covered,
                &mut covered_len,
                &mut total,
                should_stop,
                visitor,
            )?;
            if selected_outcome || total.budget_exhausted {
                return Ok(total);
            }

            // Adjacent levels are a fallback, not an unconditional duplicate
            // scan of the shared pattern payload. A candidate is only a reason
            // to stop scanning levels when the solver's visitor returns Break:
            // an exact geometric match can still fail attitude verification.
            // Cover every configured neighbor in distance order while the
            // visitor continues, subtracting q16 ranges already scanned.
            for distance in 1..=u16::from(selected_descriptor.neighbor_level_radius) {
                let neighbors = [
                    selected.checked_sub(distance),
                    selected.checked_add(distance),
                ];
                for neighbor in neighbors.into_iter().flatten() {
                    if neighbor >= self.view.level_count() {
                        continue;
                    }
                    let descriptor = self.view.level(neighbor)?.descriptor();
                    let outcome = visit_uncovered_level(
                        *self,
                        neighbor,
                        descriptor,
                        query,
                        query_ratios,
                        tolerance_q8,
                        edge_min_q16,
                        edge_max_q16,
                        &mut covered,
                        &mut covered_len,
                        &mut total,
                        should_stop,
                        visitor,
                    )?;
                    if outcome || total.budget_exhausted {
                        return Ok(total);
                    }
                }
            }
            return Ok(total);
        }

        // Without an FOV selection, cover the union of every level edge window
        // exactly once. The file uses shared radix directories and payload, so
        // subtracting already-covered intervals avoids duplicate mmap reads.
        for level_number in 0..self.view.level_count() {
            let descriptor = self.view.level(level_number)?.descriptor();
            let outcome = visit_uncovered_level(
                *self,
                level_number,
                descriptor,
                query,
                query_ratios,
                tolerance_q8,
                edge_min_q16,
                edge_max_q16,
                &mut covered,
                &mut covered_len,
                &mut total,
                should_stop,
                visitor,
            )?;
            if outcome || total.budget_exhausted {
                break;
            }
        }
        Ok(total)
    }
}

impl StarCatalog for RuntimeIndex<'_> {
    type Error = RuntimeIndexError;

    fn visit_cap(
        &self,
        query: CatalogQuery,
        visitor: &mut dyn FnMut(CatalogStar) -> ControlFlow<()>,
    ) -> Result<CatalogQueryStats, Self::Error> {
        self.visit_cap_cancelable(query, &mut || false, visitor)
    }

    fn visit_cap_cancelable(
        &self,
        query: CatalogQuery,
        should_stop: &mut dyn FnMut() -> bool,
        visitor: &mut dyn FnMut(CatalogStar) -> ControlFlow<()>,
    ) -> Result<CatalogQueryStats, Self::Error> {
        validate_cap(query.radius_rad)?;
        let center = query.center.as_vec();
        let mut callback_error = None;
        let stats = self.view.visit_cap_bounded_cancelable(
            [center.x, center.y, center.z],
            query.radius_rad,
            self.verification_magnitude_limit_q4(),
            CapBudget {
                max_cells: query.max_cells_to_scan,
                max_records: query.max_records_to_scan,
                max_bytes: query.max_bytes_to_read,
            },
            should_stop,
            &mut |id, record| match self.decode_catalog_star(id, record) {
                Ok(star) => visitor(star),
                Err(error) => {
                    callback_error = Some(error);
                    ControlFlow::Break(())
                }
            },
        )?;
        if let Some(error) = callback_error {
            return Err(error);
        }
        Ok(CatalogQueryStats {
            cells_scanned: stats.cells_visited,
            records_scanned: stats.records_examined,
            bytes_read: stats.bytes_read,
            budget_exhausted: stats.budget_exhausted,
        })
    }

    fn density_hint_per_steradian(
        &self,
        _center: UnitVec3,
        _field_radius_rad: f64,
    ) -> Result<Option<f64>, Self::Error> {
        // N/(4*pi) is the all-sky density for a random-attitude null. Multiply
        // it by four to cover measured large-scale sky clustering and the fact
        // that candidate attitudes are conditioned on catalog pattern stars.
        // The production G<=5.5 catalog's densest sampled 45-degree field is
        // 1.917x its all-sky mean, leaving more than 2x measured headroom.
        // The index stores N after the selected magnitude cutoff, keeping this
        // O(1) and mmap-only.
        Ok(self.verification_star_count_hint().map(|count| {
            VERIFICATION_DENSITY_SAFETY_FACTOR * f64::from(count) / (4.0 * std::f64::consts::PI)
        }))
    }
}

fn validate_query(query: PatternQuery) -> Result<(), RuntimeIndexError> {
    if !query.ratio_tolerance.is_finite()
        || query.ratio_tolerance < 0.0
        || !query.largest_edge_min_rad.is_finite()
        || !query.largest_edge_max_rad.is_finite()
        || query.largest_edge_min_rad < 0.0
        || query.largest_edge_max_rad > std::f64::consts::PI
        || query.largest_edge_min_rad > query.largest_edge_max_rad
    {
        Err(RuntimeIndexError::InvalidQuery)
    } else {
        Ok(())
    }
}

fn validate_cap(radius_rad: f64) -> Result<(), RuntimeIndexError> {
    if radius_rad.is_finite() && (0.0..=std::f64::consts::PI).contains(&radius_rad) {
        Ok(())
    } else {
        Err(RuntimeIndexError::InvalidQuery)
    }
}

fn quantize_edge(edge_rad: f64) -> Result<u16, RuntimeIndexError> {
    if !edge_rad.is_finite() || !(0.0..=std::f64::consts::PI).contains(&edge_rad) {
        return Err(RuntimeIndexError::InvalidQuery);
    }
    Ok(((edge_rad * EDGE_SCALE).floor() as u32).min(u32::from(u16::MAX)) as u16)
}

/// A [`PreparedLens`] exposed through the core projection interface.
#[derive(Clone, Copy, Debug)]
pub struct LensProjector<'a> {
    lens: &'a PreparedLens,
}

impl<'a> LensProjector<'a> {
    #[must_use]
    pub const fn new(lens: &'a PreparedLens) -> Self {
        Self { lens }
    }

    /// Project one delivered-image centroid through the prepared lens.
    pub fn pixel_to_ray(
        self,
        centroid: PixelCentroid,
        fov_scale: f64,
    ) -> Result<UnitVec3, LensProjectionError> {
        validate_fov_scale(fov_scale)?;
        let ray = self
            .lens
            .pixel_to_ray_scaled(Pixel::new(centroid.x, centroid.y), fov_scale)?;
        Ok(UnitVec3::new(ray.x, ray.y, ray.z).expect("a prepared lens returns a finite unit ray"))
    }

    /// Horizontal angular extent between the delivered image's outer pixel
    /// rims at the vertical center of the raster.
    ///
    /// Pixel rims, rather than the first and last pixel centers, preserve the
    /// complete active calibration-raster width through crop and resize maps.
    pub fn horizontal_fov_rad(self, fov_scale: f64) -> Result<f64, LensProjectionError> {
        let camera = self.lens.camera();
        let middle_y = f64::from(camera.encoded_height() - 1) * 0.5;
        let left = self.pixel_to_ray(
            PixelCentroid {
                x: -0.5,
                y: middle_y,
            },
            fov_scale,
        )?;
        let right = self.pixel_to_ray(
            PixelCentroid {
                x: f64::from(camera.encoded_width()) - 0.5,
                y: middle_y,
            },
            fov_scale,
        )?;
        Ok(left.angle_to(right))
    }

    /// Maximum angular displacement from `centroid` to the corners of an
    /// explicit encoded-plane uncertainty box.
    ///
    /// This is the exact primitive for clipped or asymmetric quantizers. The
    /// box and centroid must lie within the delivered image's pixel rims, and
    /// the box must contain the centroid.
    #[cfg(test)]
    pub(crate) fn pixel_box_error_rad(
        self,
        centroid: PixelCentroid,
        minimum: PixelCentroid,
        maximum: PixelCentroid,
        fov_scale: f64,
    ) -> Result<f64, LensProjectionError> {
        let camera = self.lens.camera();
        let minimum_x = -0.5;
        let minimum_y = -0.5;
        let maximum_x = f64::from(camera.encoded_width()) - 0.5;
        let maximum_y = f64::from(camera.encoded_height()) - 0.5;
        if !minimum.x.is_finite()
            || !minimum.y.is_finite()
            || !maximum.x.is_finite()
            || !maximum.y.is_finite()
            || minimum.x < minimum_x
            || minimum.y < minimum_y
            || maximum.x > maximum_x
            || maximum.y > maximum_y
            || minimum.x > centroid.x
            || minimum.y > centroid.y
            || maximum.x < centroid.x
            || maximum.y < centroid.y
        {
            return Err(LensProjectionError::InvalidPixelCellExtent);
        }

        let center = self.pixel_to_ray(centroid, fov_scale)?;
        let mut maximum_error = 0.0_f64;
        for corner in [
            minimum,
            PixelCentroid {
                x: maximum.x,
                y: minimum.y,
            },
            PixelCentroid {
                x: minimum.x,
                y: maximum.y,
            },
            maximum,
        ] {
            maximum_error =
                maximum_error.max(center.angle_to(self.pixel_to_ray(corner, fov_scale)?));
        }
        Ok(maximum_error)
    }

    /// Exact maximum rectilinear pixel-box displacement over a bounded
    /// image-plane scale interval.
    ///
    /// For corrected plane points `p` and `q`, their angular separation is
    /// `atan2(s*sqrt(A + B*s^2), 1 + c*s^2)`. Its derivative can change sign
    /// at most once, at `s^2 = A/(c*A - 2*B)`, so both endpoints and that one
    /// interior point contain the global maximum.
    #[cfg(test)]
    pub(crate) fn pixel_box_error_rad_over_rectilinear_scale_range(
        self,
        centroid: PixelCentroid,
        minimum: PixelCentroid,
        maximum: PixelCentroid,
        minimum_fov_scale: f64,
        maximum_fov_scale: f64,
    ) -> Result<f64, LensProjectionError> {
        validate_fov_scale(minimum_fov_scale)?;
        validate_fov_scale(maximum_fov_scale)?;
        if minimum_fov_scale > maximum_fov_scale
            || self.lens.projection() != Projection::Rectilinear
        {
            return Err(LensProjectionError::InvalidFovScale);
        }

        // Reuse the ordinary primitive to validate the pixel box and include
        // both interval endpoints exactly.
        let mut maximum_error =
            self.pixel_box_error_rad(centroid, minimum, maximum, minimum_fov_scale)?;
        maximum_error = maximum_error.max(self.pixel_box_error_rad(
            centroid,
            minimum,
            maximum,
            maximum_fov_scale,
        )?);
        if minimum_fov_scale == maximum_fov_scale {
            return Ok(maximum_error);
        }

        let center = self
            .lens
            .pixel_to_corrected(Pixel::new(centroid.x, centroid.y))?;
        for corner in [
            minimum,
            PixelCentroid {
                x: maximum.x,
                y: minimum.y,
            },
            PixelCentroid {
                x: minimum.x,
                y: maximum.y,
            },
            maximum,
        ] {
            let corner = self
                .lens
                .pixel_to_corrected(Pixel::new(corner.x, corner.y))?;
            if let Some(stationary_scale) = rectilinear_edge_maximum_scale(center, corner) {
                if (minimum_fov_scale..=maximum_fov_scale).contains(&stationary_scale) {
                    maximum_error = maximum_error.max(rectilinear_edge_angle_rad(
                        center,
                        corner,
                        stationary_scale,
                    ));
                }
            }
        }
        Ok(maximum_error)
    }

    /// Maximum angular displacement from the optical axis to an image corner.
    pub fn field_radius_rad(self, fov_scale: f64) -> Result<f64, LensProjectionError> {
        validate_fov_scale(fov_scale)?;
        let camera = self.lens.camera();
        let minimum_x = -0.5;
        let minimum_y = -0.5;
        let maximum_x = f64::from(camera.encoded_width()) - 0.5;
        let maximum_y = f64::from(camera.encoded_height()) - 0.5;
        let corners = [
            PixelCentroid {
                x: minimum_x,
                y: minimum_y,
            },
            PixelCentroid {
                x: maximum_x,
                y: minimum_y,
            },
            PixelCentroid {
                x: minimum_x,
                y: maximum_y,
            },
            PixelCentroid {
                x: maximum_x,
                y: maximum_y,
            },
        ];
        let mut radius = 0.0_f64;
        for corner in corners {
            radius = radius.max(self.pixel_to_ray(corner, fov_scale)?.angle_to(UnitVec3::Z));
        }
        Ok(radius)
    }
}

#[cfg(test)]
fn rectilinear_edge_angle_rad(left: CorrectedCoord, right: CorrectedCoord, scale: f64) -> f64 {
    let delta_x = left.x - right.x;
    let delta_y = left.y - right.y;
    let cross = left.x * right.y - left.y * right.x;
    let a = delta_x * delta_x + delta_y * delta_y;
    let b = cross * cross;
    let c = left.x * right.x + left.y * right.y;
    let sine_numerator = scale * (a + b * scale * scale).sqrt();
    let cosine_numerator = 1.0 + c * scale * scale;
    sine_numerator.atan2(cosine_numerator)
}

#[cfg(test)]
fn rectilinear_edge_maximum_scale(left: CorrectedCoord, right: CorrectedCoord) -> Option<f64> {
    let delta_x = left.x - right.x;
    let delta_y = left.y - right.y;
    let cross = left.x * right.y - left.y * right.x;
    let a = delta_x * delta_x + delta_y * delta_y;
    let b = cross * cross;
    let c = left.x * right.x + left.y * right.y;
    let denominator = c * a - 2.0 * b;
    (a > 0.0 && denominator > 0.0)
        .then(|| (a / denominator).sqrt())
        .filter(|scale| scale.is_finite() && *scale > 0.0)
}

#[cfg(test)]
mod rectilinear_scale_tests {
    use super::*;

    #[test]
    fn edge_maximum_includes_the_unique_interior_stationary_scale() {
        let left = CorrectedCoord { x: 0.9, y: 0.0 };
        let right = CorrectedCoord { x: 1.1, y: 0.0 };
        let stationary = rectilinear_edge_maximum_scale(left, right).unwrap();
        let expected = (1.0_f64 / 0.99).sqrt();
        assert!((stationary - expected).abs() < 1.0e-14);

        let minimum = rectilinear_edge_angle_rad(left, right, 0.92);
        let maximum = rectilinear_edge_angle_rad(left, right, 1.08);
        let interior = rectilinear_edge_angle_rad(left, right, stationary);
        assert!(interior > minimum);
        assert!(interior > maximum);
    }
}

/// Projection errors retain the original lens failure and distinguish an
/// invalid solver-side FOV scale.
#[derive(Clone, Debug, PartialEq)]
#[non_exhaustive]
pub enum LensProjectionError {
    Lens(LensError),
    InvalidFovScale,
    InvalidPixelCellExtent,
}

impl fmt::Display for LensProjectionError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Lens(error) => write!(formatter, "lens projection failed: {error}"),
            Self::InvalidFovScale => {
                formatter.write_str("FOV scale must be finite and greater than zero")
            }
            Self::InvalidPixelCellExtent => {
                formatter.write_str("pixel-cell uncertainty extent is invalid")
            }
        }
    }
}

impl error::Error for LensProjectionError {
    fn source(&self) -> Option<&(dyn error::Error + 'static)> {
        match self {
            Self::Lens(error) => Some(error),
            _ => None,
        }
    }
}

impl From<LensError> for LensProjectionError {
    fn from(error: LensError) -> Self {
        Self::Lens(error)
    }
}

fn validate_fov_scale(fov_scale: f64) -> Result<(), LensProjectionError> {
    if fov_scale.is_finite() && fov_scale > 0.0 {
        Ok(())
    } else {
        Err(LensProjectionError::InvalidFovScale)
    }
}

/// Solver error type for the mmap-backed runtime adapter.
pub type RuntimeSolveError = SolveError<RuntimeIndexError, RuntimeIndexError>;

impl RuntimeIndex<'_> {
    /// Solve observations that have already been projected into camera rays.
    ///
    /// Level selection is owned by this runtime. Construct it with
    /// [`RuntimeIndex::for_level`] when the solve should not search the complete
    /// index.
    pub fn solve_rays(
        &self,
        observations: &[Observation],
        field_radius_rad: f64,
        config: &SolveConfig,
    ) -> Result<SolveOutcome, RuntimeSolveError> {
        self.solve_rays_with_deadline(observations, field_radius_rad, config, || false)
    }

    /// Solve pre-projected observations while cooperatively checking a caller-
    /// supplied deadline or cancellation predicate.
    ///
    /// `deadline_expired` is deliberately a predicate rather than a duration so
    /// callers can use their platform's monotonic clock and may combine a timeout
    /// with an explicit cancellation signal. The check is cooperative: bounded
    /// index/catalog operations already in progress are allowed to finish.
    pub fn solve_rays_with_deadline(
        &self,
        observations: &[Observation],
        field_radius_rad: f64,
        config: &SolveConfig,
        deadline_expired: impl FnMut() -> bool,
    ) -> Result<SolveOutcome, RuntimeSolveError> {
        let (calibrated_field_radius_rad, rectilinear_radial_reference_rad) =
            rectilinear_calibration_field_radii(field_radius_rad, config);
        self.solve_rays_with_reference_and_deadline(
            observations,
            calibrated_field_radius_rad,
            rectilinear_radial_reference_rad,
            config,
            deadline_expired,
        )
    }

    fn solve_rays_with_reference_and_deadline(
        &self,
        observations: &[Observation],
        field_radius_rad: f64,
        rectilinear_radial_reference_rad: f64,
        config: &SolveConfig,
        deadline_expired: impl FnMut() -> bool,
    ) -> Result<SolveOutcome, RuntimeSolveError> {
        let max_pattern_edge_rad = maximum_pattern_edge_for_request(
            self,
            field_radius_rad,
            config.largest_edge_relative_tolerance,
        )
        .map_err(SolveError::Index)?;
        tetra3_core::solve_with_deadline(
            self,
            self,
            SolveRequest {
                observations,
                field_radius_rad,
                rectilinear_radial_reference_rad,
                max_pattern_edge_rad,
            },
            config,
            deadline_expired,
        )
    }
}

fn maximum_pattern_edge_for_request(
    index: &RuntimeIndex<'_>,
    field_radius_rad: f64,
    largest_edge_relative_tolerance: f64,
) -> Result<Option<f64>, RuntimeIndexError> {
    // Let core retain ownership of invalid-config reporting. Its accepted
    // tolerance range always leaves a positive denominator below.
    if !largest_edge_relative_tolerance.is_finite()
        || !(0.0..1.0).contains(&largest_edge_relative_tolerance)
    {
        return Ok(None);
    }
    let Some(observed_edge_limit) =
        index.maximum_observed_pattern_edge_rad(largest_edge_relative_tolerance)?
    else {
        return Ok(None);
    };
    let maximum_image_edge = (2.0 * field_radius_rad).min(std::f64::consts::PI);
    Ok((maximum_image_edge > observed_edge_limit).then_some(observed_edge_limit))
}

/// Error returned while projecting centroids or running the geometric solver.
#[derive(Debug)]
#[non_exhaustive]
pub enum PixelSolveError {
    Projection(LensProjectionError),
    Solve(RuntimeSolveError),
}

impl fmt::Display for PixelSolveError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Projection(error) => write!(formatter, "pixel projection failed: {error}"),
            Self::Solve(error) => error.fmt(formatter),
        }
    }
}

impl error::Error for PixelSolveError {
    fn source(&self) -> Option<&(dyn error::Error + 'static)> {
        match self {
            Self::Projection(error) => Some(error),
            Self::Solve(error) => Some(error),
        }
    }
}

impl RuntimeIndex<'_> {
    /// Correct centroids with an independently implemented lens profile and
    /// solve them against the same borrowed mmap-backed index.
    ///
    /// Only the deterministic brightest `config.budgets.max_observations`
    /// centroids are projected, so an oversized detector result cannot cause
    /// unbounded lens work or an O(`pixels.len()`) runtime allocation. Fluxes
    /// are still validated across the complete input slice. Match indices in
    /// the result always refer to the original `pixels` slice. A returned
    /// solution's `fov_scale` is the total scale relative to `lens`, including
    /// the caller-supplied base scale.
    pub fn solve_pixels(
        &self,
        lens: &PreparedLens,
        pixels: &[PixelObservation],
        fov_scale: f64,
        config: &SolveConfig,
    ) -> Result<SolveOutcome, PixelSolveError> {
        self.solve_pixels_with_deadline(lens, pixels, fov_scale, config, || false)
    }

    /// Correct centroids and solve with a cooperative caller-supplied deadline
    /// or cancellation predicate.
    ///
    /// Projection is bounded by `config.budgets.max_observations`. The predicate
    /// is checked by the geometric solver at natural search boundaries; it is
    /// not a hard real-time interrupt.
    pub fn solve_pixels_with_deadline(
        &self,
        lens: &PreparedLens,
        pixels: &[PixelObservation],
        fov_scale: f64,
        config: &SolveConfig,
        deadline_expired: impl FnMut() -> bool,
    ) -> Result<SolveOutcome, PixelSolveError> {
        let mut effective_config = *config;
        if lens.projection() != Projection::Rectilinear {
            effective_config.rectilinear_fov_scale_max_delta = 0.0;
            effective_config.rectilinear_radial_max_abs = 0.0;
        }
        let projector = LensProjector::new(lens);
        let (observations, source_indices) = tetra3_core::project_observations_bounded(
            pixels,
            effective_config.budgets.max_observations,
            |centroid| projector.pixel_to_ray(centroid, fov_scale),
        )
        .map_err(|error| match error {
            tetra3_core::BoundedProjectionError::InvalidObservation { index } => {
                PixelSolveError::Solve(SolveError::InvalidObservation { index })
            }
            tetra3_core::BoundedProjectionError::Projection(error) => {
                PixelSolveError::Projection(error)
            }
        })?;
        let baseline_field_radius_rad = projector
            .field_radius_rad(fov_scale)
            .map_err(PixelSolveError::Projection)?;
        let (field_radius_rad, rectilinear_radial_reference_rad) =
            rectilinear_calibration_field_radii(baseline_field_radius_rad, &effective_config);
        let mut outcome = self
            .solve_rays_with_reference_and_deadline(
                &observations,
                field_radius_rad,
                rectilinear_radial_reference_rad,
                &effective_config,
                deadline_expired,
            )
            .map_err(PixelSolveError::Solve)?;
        compose_fov_scale(&mut outcome, fov_scale);
        remap_observation_indices(&mut outcome, &source_indices);
        Ok(outcome)
    }
}

fn compose_fov_scale(outcome: &mut SolveOutcome, base_fov_scale: f64) {
    let compose = |solution: &mut tetra3_core::Solution| {
        if solution.rectilinear_radial_reference_rad > 0.0
            && solution.rectilinear_radial_reference_rad < std::f64::consts::FRAC_PI_2
        {
            solution.rectilinear_radial_reference_rad =
                (solution.rectilinear_radial_reference_rad.tan() / base_fov_scale).atan();
        }
        solution.fov_scale *= base_fov_scale;
    };
    match outcome {
        SolveOutcome::Solved { solution, .. } => compose(solution),
        SolveOutcome::Ambiguous {
            best, runner_up, ..
        } => {
            compose(best);
            compose(runner_up);
        }
        SolveOutcome::NoMatch { .. } => {}
    }
}

fn rectilinear_calibration_field_radii(
    baseline_field_radius_rad: f64,
    config: &SolveConfig,
) -> (f64, f64) {
    if config.rectilinear_fov_scale_max_delta == 0.0 && config.rectilinear_radial_max_abs == 0.0 {
        return (baseline_field_radius_rad, baseline_field_radius_rad);
    }
    let maximum_scale = 1.0 + config.rectilinear_fov_scale_max_delta;
    let baseline_plane_radius = baseline_field_radius_rad.tan();
    let reference_plane_radius = maximum_scale * baseline_plane_radius;
    let reference_rad = reference_plane_radius.atan();
    if config.rectilinear_radial_max_abs == 0.0 {
        return (reference_rad, reference_rad);
    }
    let calibrated_plane_radius = maximum_scale
        * (1.0
            + config.rectilinear_radial_max_abs * baseline_plane_radius.powi(2)
                / reference_plane_radius.powi(2))
        * baseline_plane_radius;
    (calibrated_plane_radius.atan(), reference_rad)
}

#[cfg(test)]
mod residual_calibration_tests {
    use super::*;
    use tetra3_core::{Diagnostics, Match, Quat, Solution};

    #[test]
    fn positive_radial_bound_expands_coverage_beyond_fixed_reference() {
        let baseline_field = 0.4_f64;
        let config = SolveConfig {
            rectilinear_fov_scale_max_delta: 0.08,
            rectilinear_radial_max_abs: 0.25,
            ..SolveConfig::default()
        };
        let (coverage, reference) = rectilinear_calibration_field_radii(baseline_field, &config);
        let baseline_plane = baseline_field.tan();
        let maximum_scale = 1.08;
        let expected_reference = (maximum_scale * baseline_plane).atan();
        let expected_coverage =
            (maximum_scale * (1.0 + 0.25 / maximum_scale.powi(2)) * baseline_plane).atan();
        assert!((reference - expected_reference).abs() < 1.0e-15);
        assert!((coverage - expected_coverage).abs() < 1.0e-15);
        assert!(coverage > reference);

        let corner = UnitVec3::new(baseline_plane, 0.0, 1.0).unwrap();
        let calibrated = tetra3_core::apply_rectilinear_calibration(
            corner,
            maximum_scale,
            config.rectilinear_radial_max_abs,
            reference,
        )
        .unwrap();
        assert!((calibrated.angle_to(UnitVec3::Z) - coverage).abs() < 1.0e-15);
    }

    #[test]
    fn disabled_calibration_preserves_a_field_wider_than_ninety_degrees() {
        let field = 120.0_f64.to_radians();
        let config = SolveConfig::default();
        assert_eq!(
            rectilinear_calibration_field_radii(field, &config),
            (field, field)
        );
    }

    #[test]
    fn composition_preserves_normalized_radial_model_across_base_scale() {
        let seed_matches = core::array::from_fn(|index| Match {
            observation_index: index,
            star_id: StarId(index as u32),
            catalog_direction: UnitVec3::Z,
            residual_rad: 0.0,
        });
        let core_reference = 0.5_f64;
        let mut outcome = SolveOutcome::Solved {
            solution: Solution {
                camera_to_world: Quat::identity(),
                fov_scale: 1.03,
                rectilinear_radial_coefficient: -0.12,
                rectilinear_radial_reference_rad: core_reference,
                seed_matches,
                matches: Vec::new(),
                rms_residual_rad: 0.0,
                max_residual_rad: 0.0,
                raw_chance_probability: 0.0,
                chance_probability: 0.0,
            },
            diagnostics: Diagnostics::default(),
        };
        let base = 1.2;
        compose_fov_scale(&mut outcome, base);
        let SolveOutcome::Solved { solution, .. } = outcome else {
            unreachable!();
        };
        assert!((solution.fov_scale - 1.03 * base).abs() < 1.0e-15);
        assert_eq!(solution.rectilinear_radial_coefficient, -0.12);
        assert!(
            (solution.rectilinear_radial_reference_rad - (core_reference.tan() / base).atan())
                .abs()
                < 1.0e-15
        );
    }
}

fn remap_observation_indices(outcome: &mut SolveOutcome, source_indices: &[usize]) {
    let remap_solution = |solution: &mut tetra3_core::Solution| {
        for matched in &mut solution.seed_matches {
            matched.observation_index = source_indices[matched.observation_index];
        }
        solution
            .seed_matches
            .sort_unstable_by_key(|matched| matched.observation_index);
        for matched in &mut solution.matches {
            matched.observation_index = source_indices[matched.observation_index];
        }
        solution
            .matches
            .sort_unstable_by_key(|matched| matched.observation_index);
    };
    match outcome {
        SolveOutcome::Solved { solution, .. } => remap_solution(solution),
        SolveOutcome::Ambiguous {
            best, runner_up, ..
        } => {
            remap_solution(best);
            remap_solution(runner_up);
        }
        SolveOutcome::NoMatch { .. } => {}
    }
}

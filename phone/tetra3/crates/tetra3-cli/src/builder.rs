use std::collections::{BTreeSet, BinaryHeap, HashSet};
use std::f64::consts::{PI, TAU};
use std::path::Path;

use clap::Args;
use rayon::prelude::*;
use serde::{Deserialize, Serialize};
use tetra3_core::{Invariant, UnitVec3};
use tetra3_index::VerificationStar;

use crate::catalog::{CatalogStar, CompactCatalog, STAR_FLAG_PATTERN_ELIGIBLE};
use crate::geometry::Vec3;
use crate::io_utils::write_bytes_atomic;
use crate::{Error, Result};

#[derive(Clone, Copy, Debug, Args, PartialEq, Serialize, Deserialize)]
#[serde(from = "[f64; 2]", into = "[f64; 2]")]
pub struct HorizontalFovRange {
    /// Minimum supported horizontal rectilinear field of view.
    #[arg(long, default_value_t = HorizontalFovRange::default().min_fov_deg)]
    pub min_fov_deg: f64,
    /// Maximum supported horizontal rectilinear field of view.
    #[arg(long, default_value_t = HorizontalFovRange::default().max_fov_deg)]
    pub max_fov_deg: f64,
}

impl Default for HorizontalFovRange {
    fn default() -> Self {
        Self {
            min_fov_deg: 3.0,
            max_fov_deg: 45.0,
        }
    }
}

impl From<[f64; 2]> for HorizontalFovRange {
    fn from([min_fov_deg, max_fov_deg]: [f64; 2]) -> Self {
        Self {
            min_fov_deg,
            max_fov_deg,
        }
    }
}

impl From<HorizontalFovRange> for [f64; 2] {
    fn from(value: HorizontalFovRange) -> Self {
        [value.min_fov_deg, value.max_fov_deg]
    }
}

#[derive(Clone, Copy, Debug, Args, PartialEq, Eq, Serialize, Deserialize)]
#[serde(from = "[u16; 2]", into = "[u16; 2]")]
pub struct SpatialGrid {
    #[arg(long, default_value_t = SpatialGrid::default().spatial_z_bins)]
    pub spatial_z_bins: u16,
    #[arg(long, default_value_t = SpatialGrid::default().spatial_lon_bins)]
    pub spatial_lon_bins: u16,
}

impl Default for SpatialGrid {
    fn default() -> Self {
        Self {
            spatial_z_bins: 180,
            spatial_lon_bins: 360,
        }
    }
}

impl From<[u16; 2]> for SpatialGrid {
    fn from([spatial_z_bins, spatial_lon_bins]: [u16; 2]) -> Self {
        Self {
            spatial_z_bins,
            spatial_lon_bins,
        }
    }
}

impl From<SpatialGrid> for [u16; 2] {
    fn from(value: SpatialGrid) -> Self {
        [value.spatial_z_bins, value.spatial_lon_bins]
    }
}

/// Options that determine the exact bytes emitted for a catalog and generator version.
#[derive(Clone, Debug, Args, PartialEq, Serialize, Deserialize)]
pub struct BuildRecipe {
    #[command(flatten)]
    pub horizontal_fov_deg: HorizontalFovRange,
    #[arg(
        long = "levels",
        value_name = "LEVELS",
        default_value_t = BuildRecipe::default().level_count
    )]
    pub level_count: usize,
    /// Sensor width divided by sensor height. The default is the phone input's 4:3.
    #[arg(long, default_value_t = BuildRecipe::default().aspect_ratio)]
    pub aspect_ratio: f64,
    /// Approximate separation between Fibonacci field centers, as a fraction of
    /// horizontal FOV. Smaller values increase overlap and build time.
    #[arg(long, default_value_t = BuildRecipe::default().lattice_spacing_fraction)]
    pub lattice_spacing_fraction: f64,
    /// Evenly spaced detector rolls sampled at every lattice center. Rolls are
    /// unique modulo pi because a centered rectangle is invariant under 180°.
    #[arg(long, default_value_t = BuildRecipe::default().pattern_rolls_per_field)]
    pub pattern_rolls_per_field: usize,
    /// Maximum locally bright stars retained in each sampled field.
    #[arg(long, default_value_t = BuildRecipe::default().pattern_stars_per_field)]
    pub pattern_stars_per_field: usize,
    /// Maximum accepted quads emitted by each lattice field. Zero enumerates all.
    #[arg(
        long = "patterns-per-field",
        value_name = "PATTERNS_PER_FIELD",
        default_value_t = BuildRecipe::default().max_patterns_per_field
    )]
    pub max_patterns_per_field: usize,
    /// Initial isolation radius as a fraction of horizontal FOV. It is relaxed
    /// deterministically when a sparse field cannot reach the target count.
    #[arg(long, default_value_t = BuildRecipe::default().isolation_fraction)]
    pub isolation_fraction: f64,
    #[arg(long, default_value_t = BuildRecipe::default().minimum_isolation_arcsec)]
    pub minimum_isolation_arcsec: f64,
    /// Reject quads whose largest edge is smaller than this fraction of the
    /// field's diagonal. They carry little scale information.
    #[arg(long, default_value_t = BuildRecipe::default().minimum_pattern_span_fraction)]
    pub minimum_pattern_span_fraction: f64,
    /// Reject quads with a smallest/largest angular edge ratio below this value.
    #[arg(long, default_value_t = BuildRecipe::default().minimum_edge_ratio)]
    pub minimum_edge_ratio: f64,
    /// Reject near-collinear quads using the minor/major tangent covariance ratio.
    #[arg(long, default_value_t = BuildRecipe::default().minimum_covariance_ratio)]
    pub minimum_covariance_ratio: f64,
    /// All-sky average number of verification stars desired in a rectangular field.
    #[arg(
        long,
        default_value_t = BuildRecipe::default().target_verification_stars_per_field
    )]
    pub target_verification_stars_per_field: usize,
    /// Brightness-prefix multiplier used only while choosing pattern anchors.
    #[arg(
        long,
        default_value_t = BuildRecipe::default().pattern_anchor_safety_multiplier
    )]
    pub pattern_anchor_safety_multiplier: usize,
    #[arg(
        long = "tolerance-q8",
        value_name = "TOLERANCE_Q8",
        default_value_t = BuildRecipe::default().default_tolerance_q8
    )]
    pub default_tolerance_q8: u8,
    #[command(flatten)]
    pub spatial_grid: SpatialGrid,
    /// Permit emitting an index even when a sampled lattice field has no
    /// accepted quad. Production builds fail closed by default.
    #[arg(long)]
    pub allow_uncovered_fields: bool,
}

impl Default for BuildRecipe {
    fn default() -> Self {
        Self {
            horizontal_fov_deg: HorizontalFovRange::default(),
            aspect_ratio: 4.0 / 3.0,
            level_count: 8,
            lattice_spacing_fraction: 0.55,
            pattern_rolls_per_field: 4,
            pattern_stars_per_field: 10,
            max_patterns_per_field: 32,
            isolation_fraction: 0.003,
            minimum_isolation_arcsec: 20.0,
            minimum_pattern_span_fraction: 0.15,
            minimum_edge_ratio: 0.08,
            minimum_covariance_ratio: 0.02,
            target_verification_stars_per_field: 128,
            pattern_anchor_safety_multiplier: 4,
            default_tolerance_q8: 2,
            spatial_grid: SpatialGrid::default(),
            allow_uncovered_fields: false,
        }
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct LevelStats {
    pub horizontal_fov_deg: f64,
    pub diagonal_fov_deg: f64,
    pub lattice_centers: usize,
    pub pattern_rolls_per_field: usize,
    /// Total oriented fields: lattice centers times detector rolls.
    pub lattice_fields: usize,
    pub fields_with_quad: usize,
    pub fields_without_quad: usize,
    pub fields_capped: usize,
    pub quad_combinations_considered: usize,
    pub quads_rejected_degenerate: usize,
    pub quads_rejected_span: usize,
    pub quads_rejected_edge_ratio: usize,
    pub quads_rejected_collinear: usize,
    pub max_verification_magnitude_q4: i8,
    pub max_verification_magnitude: f32,
    pub verification_catalog_prefix: usize,
    pub pattern_anchor_catalog_prefix: usize,
    pub patterns_before_dedup: usize,
    /// Tuple references retained by this level after within-level deduplication.
    pub patterns_after_level_dedup: usize,
    /// Globally unique payload tuples first contributed by this level. Later
    /// levels still retain duplicate membership for descriptor edge windows.
    pub patterns_after_global_dedup: usize,
}

#[derive(Clone, Debug, Serialize)]
pub struct BuildStats {
    pub catalog_stars: usize,
    pub pattern_stars: usize,
    /// Globally unique records in the writer's shared flash pattern payload.
    pub patterns: usize,
    pub levels: Vec<LevelStats>,
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub struct GeneratedPattern {
    /// Indices in the brightness-sorted verification catalog.
    pub catalog_stars: [u32; 4],
    pub ratios_q8: [u8; 5],
    pub largest_edge_q16: u16,
}

#[derive(Debug)]
pub struct GeneratedLevel {
    pub horizontal_fov_deg: f64,
    pub min_horizontal_fov_deg: f64,
    pub max_horizontal_fov_deg: f64,
    pub max_verification_magnitude_q4: i8,
    pub patterns: Vec<GeneratedPattern>,
}

#[derive(Debug)]
pub struct GeneratedIndex {
    pub levels: Vec<GeneratedLevel>,
    pub stats: BuildStats,
}

pub fn logarithmic_fov_levels(minimum_deg: f64, maximum_deg: f64, count: usize) -> Vec<f64> {
    if count == 1 {
        return vec![minimum_deg];
    }
    let ratio = (maximum_deg / minimum_deg).powf(1.0 / (count - 1) as f64);
    (0..count)
        .map(|index| {
            if index + 1 == count {
                maximum_deg
            } else {
                minimum_deg * ratio.powi(index as i32)
            }
        })
        .collect()
}

pub fn diagonal_fov_deg(horizontal_fov_deg: f64, aspect_ratio: f64) -> f64 {
    let horizontal_tangent = (horizontal_fov_deg.to_radians() * 0.5).tan();
    let diagonal_tangent = horizontal_tangent * (1.0 + aspect_ratio.recip().powi(2)).sqrt();
    (2.0 * diagonal_tangent.atan()).to_degrees()
}

pub fn generate_index(
    catalog: &CompactCatalog,
    recipe: &BuildRecipe,
    threads: usize,
) -> Result<GeneratedIndex> {
    validate_build_options(recipe, catalog.stars.len())?;
    let fovs = logarithmic_fov_levels(
        recipe.horizontal_fov_deg.min_fov_deg,
        recipe.horizontal_fov_deg.max_fov_deg,
        recipe.level_count,
    );
    // Patterns must be calculated from the exact vectors that Android will
    // decode from signed oct24 records. Computing from higher precision and
    // quantizing only while writing can cross a q8 bucket boundary.
    let runtime_stars = runtime_quantized_stars(&catalog.stars)?;
    let verification_cutoffs = fovs
        .iter()
        .map(|&fov| verification_magnitude_cutoff(&runtime_stars, fov, recipe))
        .collect::<Result<Vec<_>>>()?;
    let sky = SkyGrid::new(&runtime_stars, 1.0)?;
    let threads = if threads == 0 {
        std::thread::available_parallelism().map_or(1, usize::from)
    } else {
        threads
    };
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(threads)
        .build()
        .map_err(|error| Error::Build(format!("cannot create Rayon pool: {error}")))?;
    let generated: Vec<(GeneratedLevel, LevelStats)> = pool.install(|| {
        fovs.par_iter()
            .zip(verification_cutoffs.par_iter())
            .map(|(&fov, &cutoff)| generate_level(&runtime_stars, &sky, fov, cutoff, recipe))
            .collect::<Result<Vec<_>>>()
    })?;

    // A quartet can be sampled by several overlapping lattice fields and by
    // adjacent scale levels. Keep every level's membership so the writer can
    // derive each descriptor's physical edge window from that level's complete
    // generation result. The writer globally deduplicates the shared payload.
    // We still assign each unique tuple to its first level for truthful payload
    // statistics, without removing it from later LevelInput records.
    let mut global_tuples = HashSet::new();
    let mut levels = Vec::with_capacity(generated.len());
    let mut level_stats = Vec::with_capacity(generated.len());
    for (level, mut stats) in generated {
        stats.patterns_after_global_dedup = level
            .patterns
            .iter()
            .filter(|pattern| global_tuples.insert(pattern.catalog_stars))
            .count();
        levels.push(level);
        level_stats.push(stats);
    }
    assign_level_ranges(
        &mut levels,
        recipe.horizontal_fov_deg.min_fov_deg,
        recipe.horizontal_fov_deg.max_fov_deg,
    );

    let mut pattern_catalog_stars = BTreeSet::new();
    for level in &levels {
        for pattern in &level.patterns {
            pattern_catalog_stars.extend(pattern.catalog_stars);
        }
    }
    let pattern_count = global_tuples.len();
    let stats = BuildStats {
        catalog_stars: catalog.stars.len(),
        pattern_stars: pattern_catalog_stars.len(),
        patterns: pattern_count,
        levels: level_stats,
    };
    Ok(GeneratedIndex { levels, stats })
}

#[derive(Clone, Copy, Debug)]
struct VerificationCutoff {
    magnitude_q4: i8,
    catalog_prefix: usize,
}

fn verification_magnitude_cutoff(
    stars: &[CatalogStar],
    horizontal_fov_deg: f64,
    recipe: &BuildRecipe,
) -> Result<VerificationCutoff> {
    let solid_angle = rectangular_solid_angle(horizontal_fov_deg, recipe.aspect_ratio);
    if !solid_angle.is_finite() || solid_angle <= 0.0 {
        return Err(Error::Build("invalid rectangular field solid angle".into()));
    }
    let needed = ((recipe.target_verification_stars_per_field as f64 * 4.0 * PI / solid_angle)
        .ceil() as usize)
        .clamp(1, stars.len());
    let mut magnitudes = stars
        .iter()
        .map(|star| (star.magnitude * 4.0).round() as i8)
        .collect::<Vec<_>>();
    magnitudes.sort_unstable();
    Ok(VerificationCutoff {
        magnitude_q4: magnitudes[needed - 1],
        catalog_prefix: needed,
    })
}

pub fn rectangular_solid_angle(horizontal_fov_deg: f64, aspect_ratio: f64) -> f64 {
    let x = (horizontal_fov_deg.to_radians() * 0.5).tan();
    let y = x / aspect_ratio;
    4.0 * (x * y / (1.0 + x * x + y * y).sqrt()).atan()
}

fn runtime_quantized_stars(stars: &[CatalogStar]) -> Result<Vec<CatalogStar>> {
    stars
        .iter()
        .map(|star| {
            let [oct_x, oct_y] =
                tetra3_index::writer::encode_oct24([star.vector.x, star.vector.y, star.vector.z])
                    .map_err(|error| Error::Build(error.to_string()))?;
            let magnitude_q4 = (star.magnitude * 4.0).round();
            if !magnitude_q4.is_finite()
                || magnitude_q4 < f32::from(i8::MIN)
                || magnitude_q4 > f32::from(i8::MAX)
            {
                return Err(Error::Build(format!(
                    "catalog magnitude {} is outside index q4 range",
                    star.magnitude
                )));
            }
            let stored = VerificationStar {
                oct_x,
                oct_y,
                magnitude_q4: magnitude_q4 as i8,
                flags: star.flags,
            };
            // Generate patterns from the exact f64 decoder used by the Android
            // solver and spatial-cap lookup.  Decoding through f32 here can move
            // an edge ratio across a q8 boundary before the same stored star is
            // decoded at runtime.
            let [x, y, z] = stored.direction_f64();
            Ok(CatalogStar {
                source_id: star.source_id,
                vector: Vec3::new(x, y, z),
                magnitude: stored.magnitude(),
                flags: star.flags,
            })
        })
        .collect()
}

fn validate_build_options(recipe: &BuildRecipe, catalog_stars: usize) -> Result<()> {
    if catalog_stars < 4 {
        return Err(Error::InvalidArgument(
            "at least four catalog stars are required".into(),
        ));
    }
    if catalog_stars >= (1 << 24) {
        return Err(Error::InvalidArgument(
            "catalog exceeds the index's 24-bit verification-star limit".into(),
        ));
    }
    if !recipe.horizontal_fov_deg.min_fov_deg.is_finite()
        || !recipe.horizontal_fov_deg.max_fov_deg.is_finite()
        || recipe.horizontal_fov_deg.min_fov_deg <= 0.0
        || recipe.horizontal_fov_deg.min_fov_deg > recipe.horizontal_fov_deg.max_fov_deg
        || recipe.horizontal_fov_deg.max_fov_deg >= 180.0
    {
        return Err(Error::InvalidArgument(
            "FOV range must satisfy 0 < min <= max < 180 degrees".into(),
        ));
    }
    if !(1..=64).contains(&recipe.level_count) {
        return Err(Error::InvalidArgument(
            "--levels must be between 1 and 64".into(),
        ));
    }
    if recipe.level_count > 1
        && recipe.horizontal_fov_deg.min_fov_deg == recipe.horizontal_fov_deg.max_fov_deg
    {
        return Err(Error::InvalidArgument(
            "equal minimum/maximum FOV requires --levels=1".into(),
        ));
    }
    if recipe.level_count == 1
        && recipe.horizontal_fov_deg.min_fov_deg != recipe.horizontal_fov_deg.max_fov_deg
    {
        return Err(Error::InvalidArgument(
            "--levels=1 requires equal minimum and maximum FOV".into(),
        ));
    }
    if !recipe.aspect_ratio.is_finite() || recipe.aspect_ratio <= 0.0 {
        return Err(Error::InvalidArgument(
            "--aspect-ratio must be finite and positive".into(),
        ));
    }
    if !recipe.lattice_spacing_fraction.is_finite()
        || !(0.1..=1.0).contains(&recipe.lattice_spacing_fraction)
    {
        return Err(Error::InvalidArgument(
            "--lattice-spacing-fraction must be in [0.1, 1]".into(),
        ));
    }
    if !(1..=32).contains(&recipe.pattern_rolls_per_field) {
        return Err(Error::InvalidArgument(
            "--pattern-rolls-per-field must be in [1, 32]".into(),
        ));
    }
    if !(4..=24).contains(&recipe.pattern_stars_per_field) {
        return Err(Error::InvalidArgument(
            "--pattern-stars-per-field must be in [4, 24]".into(),
        ));
    }
    if recipe.max_patterns_per_field > 100_000 {
        return Err(Error::InvalidArgument(
            "--patterns-per-field must be at most 100000 (or zero for all)".into(),
        ));
    }
    if !recipe.isolation_fraction.is_finite()
        || !(0.0..=0.1).contains(&recipe.isolation_fraction)
        || !recipe.minimum_isolation_arcsec.is_finite()
        || !(0.0..=3_600.0).contains(&recipe.minimum_isolation_arcsec)
        || !recipe.minimum_pattern_span_fraction.is_finite()
        || !(0.0..1.0).contains(&recipe.minimum_pattern_span_fraction)
        || !recipe.minimum_edge_ratio.is_finite()
        || !(0.0..1.0).contains(&recipe.minimum_edge_ratio)
        || !recipe.minimum_covariance_ratio.is_finite()
        || !(0.0..1.0).contains(&recipe.minimum_covariance_ratio)
    {
        return Err(Error::InvalidArgument(
            "invalid isolation or minimum pattern-span option".into(),
        ));
    }
    if recipe.target_verification_stars_per_field == 0 {
        return Err(Error::InvalidArgument(
            "--target-verification-stars-per-field must be positive".into(),
        ));
    }
    if !(1..=32).contains(&recipe.pattern_anchor_safety_multiplier) {
        return Err(Error::InvalidArgument(
            "--pattern-anchor-safety-multiplier must be in [1, 32]".into(),
        ));
    }
    if recipe.default_tolerance_q8 > tetra3_index::MAX_RATIO_TOLERANCE_Q8 {
        return Err(Error::InvalidArgument(format!(
            "--tolerance-q8 exceeds {}",
            tetra3_index::MAX_RATIO_TOLERANCE_Q8
        )));
    }
    let cells = u32::from(recipe.spatial_grid.spatial_z_bins)
        * u32::from(recipe.spatial_grid.spatial_lon_bins);
    if recipe.spatial_grid.spatial_z_bins == 0
        || recipe.spatial_grid.spatial_lon_bins == 0
        || cells > (1 << 20)
    {
        return Err(Error::InvalidArgument(
            "spatial grid must be non-empty and contain at most 2^20 cells".into(),
        ));
    }
    Ok(())
}

fn generate_level(
    stars: &[CatalogStar],
    sky: &SkyGrid,
    horizontal_fov_deg: f64,
    verification_cutoff: VerificationCutoff,
    recipe: &BuildRecipe,
) -> Result<(GeneratedLevel, LevelStats)> {
    let diagonal_fov_deg = diagonal_fov_deg(horizontal_fov_deg, recipe.aspect_ratio);
    let cap_radius = diagonal_fov_deg.to_radians() * 0.5;
    let spacing = horizontal_fov_deg.to_radians() * recipe.lattice_spacing_fraction;
    let centers = fibonacci_lattice(spacing);
    let rolls = pattern_roll_angles(recipe.pattern_rolls_per_field);
    let oriented_fields = centers
        .len()
        .checked_mul(rolls.len())
        .ok_or_else(|| Error::Build("oriented lattice field count overflow".into()))?;
    let mut patterns = Vec::new();
    let mut fields_with_quad = 0;
    let mut quad_stats = FieldQuadStats::default();
    let pattern_anchor_catalog_prefix = verification_cutoff
        .catalog_prefix
        .saturating_mul(recipe.pattern_anchor_safety_multiplier)
        .min(stars.len());
    for center in &centers {
        // The diagonal cap is queried once; roll-specific exact rectangles are
        // then cheap deterministic filters over the same candidate IDs.
        let cap_candidates = sky.query_cap(
            *center,
            cap_radius,
            stars,
            pattern_anchor_catalog_prefix as u32,
        );
        for &roll in &rolls {
            let candidates = filter_gnomonic_rectangle(
                &cap_candidates,
                stars,
                *center,
                horizontal_fov_deg,
                recipe.aspect_ratio,
                roll,
            );
            let selected =
                select_local_pattern_stars(candidates, stars, horizontal_fov_deg, recipe);
            if selected.len() >= 4 {
                let field_stats = append_field_patterns(
                    &selected,
                    stars,
                    diagonal_fov_deg.to_radians(),
                    recipe,
                    &mut patterns,
                )?;
                if field_stats.accepted > 0 {
                    fields_with_quad += 1;
                }
                quad_stats.accumulate(field_stats);
            }
        }
    }
    let fields_without_quad = oriented_fields - fields_with_quad;
    if fields_without_quad != 0 && !recipe.allow_uncovered_fields {
        return Err(Error::Build(format!(
            "FOV {horizontal_fov_deg:.6} degrees has {fields_without_quad}/{oriented_fields} oriented sampled fields without an accepted quad ({} centers x {} rolls); improve catalog/pattern coverage or explicitly pass --allow-uncovered-fields for a non-production build",
            centers.len(),
            rolls.len()
        )));
    }
    let before_dedup = patterns.len();
    patterns.sort_unstable();
    // The same tuple has one invariant, but retaining the full key in the sort
    // also makes malformed/non-deterministic geometry immediately visible.
    patterns.dedup_by_key(|pattern| pattern.catalog_stars);
    let after_level_dedup = patterns.len();
    let stats = LevelStats {
        horizontal_fov_deg,
        diagonal_fov_deg,
        lattice_centers: centers.len(),
        pattern_rolls_per_field: rolls.len(),
        lattice_fields: oriented_fields,
        fields_with_quad,
        fields_without_quad,
        fields_capped: quad_stats.fields_capped,
        quad_combinations_considered: quad_stats.combinations_considered,
        quads_rejected_degenerate: quad_stats.rejected_degenerate,
        quads_rejected_span: quad_stats.rejected_span,
        quads_rejected_edge_ratio: quad_stats.rejected_edge_ratio,
        quads_rejected_collinear: quad_stats.rejected_collinear,
        max_verification_magnitude_q4: verification_cutoff.magnitude_q4,
        max_verification_magnitude: f32::from(verification_cutoff.magnitude_q4) * 0.25,
        verification_catalog_prefix: verification_cutoff.catalog_prefix,
        pattern_anchor_catalog_prefix,
        patterns_before_dedup: before_dedup,
        patterns_after_level_dedup: after_level_dedup,
        patterns_after_global_dedup: 0,
    };
    Ok((
        GeneratedLevel {
            horizontal_fov_deg,
            min_horizontal_fov_deg: horizontal_fov_deg,
            max_horizontal_fov_deg: horizontal_fov_deg,
            max_verification_magnitude_q4: verification_cutoff.magnitude_q4,
            patterns,
        },
        stats,
    ))
}

fn fibonacci_lattice(spacing_rad: f64) -> Vec<Vec3> {
    let count = ((4.0 * PI / spacing_rad.powi(2)).ceil() as usize).max(1);
    let golden_angle = PI * (3.0 - 5.0_f64.sqrt());
    (0..count)
        .map(|index| {
            let z = 1.0 - 2.0 * (index as f64 + 0.5) / count as f64;
            let radius = (1.0 - z * z).max(0.0).sqrt();
            let longitude = golden_angle * index as f64;
            Vec3::new(radius * longitude.cos(), radius * longitude.sin(), z)
        })
        .collect()
}

fn pattern_roll_angles(count: usize) -> Vec<f64> {
    (0..count)
        .map(|index| index as f64 * PI / count as f64)
        .collect()
}

/// Filter a diagonal-cap result to the exact rectilinear detector footprint.
///
/// Gnomonic coordinates are `axis.dot(star) / forward.dot(star)`. Multiplying
/// the half-width bounds by the positive denominator avoids divisions without
/// changing the inclusive rectangle boundary.
fn filter_gnomonic_rectangle(
    cap_candidates: &[u32],
    stars: &[CatalogStar],
    forward: Vec3,
    horizontal_fov_deg: f64,
    aspect_ratio: f64,
    roll_rad: f64,
) -> Vec<u32> {
    let forward = forward.normalized();
    let (base_right, base_down) = camera_tangent_basis(forward);
    let cosine = roll_rad.cos();
    let sine = roll_rad.sin();
    let right = Vec3::new(
        cosine * base_right.x + sine * base_down.x,
        cosine * base_right.y + sine * base_down.y,
        cosine * base_right.z + sine * base_down.z,
    );
    let down = Vec3::new(
        -sine * base_right.x + cosine * base_down.x,
        -sine * base_right.y + cosine * base_down.y,
        -sine * base_right.z + cosine * base_down.z,
    );
    let half_width = (horizontal_fov_deg.to_radians() * 0.5).tan();
    let half_height = half_width / aspect_ratio;
    cap_candidates
        .iter()
        .copied()
        .filter(|&candidate| {
            let ray = stars[candidate as usize].vector;
            let forward_component = forward.dot(ray);
            forward_component > 0.0
                && right.dot(ray).abs() <= half_width * forward_component
                && down.dot(ray).abs() <= half_height * forward_component
        })
        .collect()
}

/// Camera tangent axes for +X right, +Y down, +Z forward. The reference
/// switches near the poles to keep the cross products well conditioned.
fn camera_tangent_basis(forward: Vec3) -> (Vec3, Vec3) {
    let reference = if forward.z.abs() < 0.9 {
        Vec3::new(0.0, 0.0, 1.0)
    } else {
        Vec3::new(1.0, 0.0, 0.0)
    };
    let right = cross(reference, forward).normalized();
    let down = cross(forward, right).normalized();
    (right, down)
}

fn select_local_pattern_stars(
    candidates: Vec<u32>,
    stars: &[CatalogStar],
    horizontal_fov_deg: f64,
    recipe: &BuildRecipe,
) -> Vec<u32> {
    // A 45-degree cap can still contain thousands of stars after its global
    // brightness cutoff. Retain a bounded bright pool with a max-heap instead
    // of sorting every candidate for every lattice center.
    let pool_limit = recipe.pattern_stars_per_field.saturating_mul(8).max(256);
    let mut brightest = BinaryHeap::with_capacity(pool_limit + 1);
    for candidate in candidates {
        let star = &stars[candidate as usize];
        if star.flags & STAR_FLAG_PATTERN_ELIGIBLE == 0 {
            continue;
        }
        let key = (
            (star.magnitude * 4.0).round() as i16,
            star.source_id,
            candidate,
        );
        if brightest.len() < pool_limit {
            brightest.push(key);
        } else if brightest.peek().is_some_and(|worst| key < *worst) {
            brightest.pop();
            brightest.push(key);
        }
    }
    let mut candidates = brightest
        .into_iter()
        .map(|(_, _, candidate)| candidate)
        .collect::<Vec<_>>();
    candidates.sort_unstable_by_key(|&candidate| {
        let star = &stars[candidate as usize];
        (
            (star.magnitude * 4.0).round() as i16,
            star.source_id,
            candidate,
        )
    });
    let initial_isolation = (horizontal_fov_deg.to_radians() * recipe.isolation_fraction)
        .max(recipe.minimum_isolation_arcsec.to_radians() / 3_600.0);
    // Re-run from the brightest star while relaxing isolation. This is stable
    // across input order, fills sparse fields, and still avoids close/blended
    // candidates whenever the local density permits it.
    for factor in [1.0, 0.5, 0.25, 0.0] {
        let threshold = initial_isolation * factor;
        let mut selected = Vec::with_capacity(recipe.pattern_stars_per_field);
        for &candidate in &candidates {
            if selected.iter().all(|&other: &u32| {
                stars[candidate as usize]
                    .vector
                    .angle_to(stars[other as usize].vector)
                    >= threshold
            }) {
                selected.push(candidate);
                if selected.len() == recipe.pattern_stars_per_field {
                    break;
                }
            }
        }
        if selected.len() == recipe.pattern_stars_per_field || factor == 0.0 {
            return selected;
        }
    }
    unreachable!("adaptive selection always has a zero-isolation pass")
}

fn append_field_patterns(
    selected: &[u32],
    stars: &[CatalogStar],
    diagonal_fov_rad: f64,
    recipe: &BuildRecipe,
    output: &mut Vec<GeneratedPattern>,
) -> Result<FieldQuadStats> {
    let mut stats = FieldQuadStats::default();
    // Enumerate breadth-first by the faintest local brightness rank. The first
    // quad uses ranks 0..=3, then every quad introducing rank 4, and so on.
    // A compact cap therefore retains bright hypotheses while still including
    // alternatives that omit any one of the very brightest detections.
    for d in 3..selected.len() {
        for a in 0..d - 2 {
            for b in a + 1..d - 1 {
                for c in b + 1..d {
                    stats.combinations_considered += 1;
                    let mut catalog_stars = [selected[a], selected[b], selected[c], selected[d]];
                    catalog_stars.sort_unstable();
                    let rays = catalog_stars.map(|index| {
                        let vector = stars[index as usize].vector;
                        UnitVec3::new(vector.x, vector.y, vector.z)
                            .expect("runtime catalog stores normalized finite vectors")
                    });
                    let invariant = match Invariant::from_rays(rays) {
                        Ok(invariant) => invariant,
                        Err(_) => {
                            stats.rejected_degenerate += 1;
                            continue;
                        }
                    };
                    if invariant.largest_edge_rad()
                        < diagonal_fov_rad * recipe.minimum_pattern_span_fraction
                        || invariant.largest_edge_rad() > diagonal_fov_rad * 1.000_001
                    {
                        stats.rejected_span += 1;
                        continue;
                    }
                    if invariant.ratios()[0] < recipe.minimum_edge_ratio {
                        stats.rejected_edge_ratio += 1;
                        continue;
                    }
                    if tangent_covariance_ratio(rays) < recipe.minimum_covariance_ratio {
                        stats.rejected_collinear += 1;
                        continue;
                    }
                    let largest_edge_q16 = ((invariant.largest_edge_rad() / PI) * 65_536.0)
                        .floor()
                        .clamp(1.0, 65_535.0) as u16;
                    output.push(GeneratedPattern {
                        catalog_stars,
                        ratios_q8: invariant.quantized_q8(),
                        largest_edge_q16,
                    });
                    stats.accepted += 1;
                    if recipe.max_patterns_per_field != 0
                        && stats.accepted == recipe.max_patterns_per_field
                    {
                        stats.fields_capped = 1;
                        return Ok(stats);
                    }
                }
            }
        }
    }
    Ok(stats)
}

#[derive(Clone, Copy, Debug, Default)]
struct FieldQuadStats {
    fields_capped: usize,
    combinations_considered: usize,
    rejected_degenerate: usize,
    rejected_span: usize,
    rejected_edge_ratio: usize,
    rejected_collinear: usize,
    accepted: usize,
}

impl FieldQuadStats {
    fn accumulate(&mut self, other: Self) {
        self.fields_capped += other.fields_capped;
        self.combinations_considered += other.combinations_considered;
        self.rejected_degenerate += other.rejected_degenerate;
        self.rejected_span += other.rejected_span;
        self.rejected_edge_ratio += other.rejected_edge_ratio;
        self.rejected_collinear += other.rejected_collinear;
        self.accepted += other.accepted;
    }
}

fn tangent_covariance_ratio(rays: [UnitVec3; 4]) -> f64 {
    let mut center = Vec3::new(0.0, 0.0, 0.0);
    for ray in rays {
        let value = ray.as_vec();
        center.x += value.x;
        center.y += value.y;
        center.z += value.z;
    }
    let center = center.normalized();
    let reference = if center.z.abs() < 0.9 {
        Vec3::new(0.0, 0.0, 1.0)
    } else {
        Vec3::new(1.0, 0.0, 0.0)
    };
    let east = cross(reference, center).normalized();
    let north = cross(center, east).normalized();
    let mut points = [(0.0_f64, 0.0_f64); 4];
    for (point, ray) in points.iter_mut().zip(rays) {
        let value = ray.as_vec();
        let denominator = center.dot(Vec3::new(value.x, value.y, value.z));
        if denominator <= 1e-12 {
            return 0.0;
        }
        *point = (
            east.dot(Vec3::new(value.x, value.y, value.z)) / denominator,
            north.dot(Vec3::new(value.x, value.y, value.z)) / denominator,
        );
    }
    let mean_x = points.iter().map(|point| point.0).sum::<f64>() / 4.0;
    let mean_y = points.iter().map(|point| point.1).sum::<f64>() / 4.0;
    let mut xx = 0.0;
    let mut xy = 0.0;
    let mut yy = 0.0;
    for (x, y) in points {
        let x = x - mean_x;
        let y = y - mean_y;
        xx += x * x;
        xy += x * y;
        yy += y * y;
    }
    let trace = xx + yy;
    let discriminant = ((xx - yy).powi(2) + 4.0 * xy * xy).sqrt();
    let maximum = (trace + discriminant) * 0.5;
    let minimum = (trace - discriminant).max(0.0) * 0.5;
    if maximum <= f64::EPSILON {
        0.0
    } else {
        minimum / maximum
    }
}

fn cross(left: Vec3, right: Vec3) -> Vec3 {
    Vec3::new(
        left.y * right.z - left.z * right.y,
        left.z * right.x - left.x * right.z,
        left.x * right.y - left.y * right.x,
    )
}

fn assign_level_ranges(levels: &mut [GeneratedLevel], minimum: f64, maximum: f64) {
    for index in 0..levels.len() {
        levels[index].min_horizontal_fov_deg = if index == 0 {
            minimum
        } else {
            (levels[index - 1].horizontal_fov_deg * levels[index].horizontal_fov_deg).sqrt()
        };
        levels[index].max_horizontal_fov_deg = if index + 1 == levels.len() {
            maximum
        } else {
            (levels[index].horizontal_fov_deg * levels[index + 1].horizontal_fov_deg).sqrt()
        };
    }
}

struct SkyGrid {
    cell_rad: f64,
    latitude_cells: usize,
    longitude_cells: usize,
    cells: Vec<Vec<u32>>,
}

impl SkyGrid {
    fn new(stars: &[CatalogStar], cell_deg: f64) -> Result<Self> {
        let latitude_cells = (180.0 / cell_deg).ceil() as usize;
        let longitude_cells = (360.0 / cell_deg).ceil() as usize;
        let count = latitude_cells
            .checked_mul(longitude_cells)
            .ok_or_else(|| Error::Build("sky-grid dimensions overflow".into()))?;
        let mut grid = Self {
            cell_rad: TAU / longitude_cells as f64,
            latitude_cells,
            longitude_cells,
            cells: vec![Vec::new(); count],
        };
        for (index, star) in stars.iter().enumerate() {
            let cell = grid.cell_for(star.vector);
            grid.cells[cell].push(index as u32);
        }
        Ok(grid)
    }

    fn cell_for(&self, vector: Vec3) -> usize {
        let (longitude, latitude) = vector.to_radec_deg();
        let latitude_index = (((latitude + 90.0) / 180.0) * self.latitude_cells as f64)
            .floor()
            .clamp(0.0, (self.latitude_cells - 1) as f64) as usize;
        let longitude_index = ((longitude / 360.0) * self.longitude_cells as f64)
            .floor()
            .clamp(0.0, (self.longitude_cells - 1) as f64) as usize;
        latitude_index * self.longitude_cells + longitude_index
    }

    fn query_cap(
        &self,
        center: Vec3,
        radius: f64,
        stars: &[CatalogStar],
        catalog_prefix: u32,
    ) -> Vec<u32> {
        let (longitude_deg, latitude_deg) = center.to_radec_deg();
        let longitude = longitude_deg.to_radians();
        let latitude = latitude_deg.to_radians();
        let lat_min = (latitude - radius).max(-PI * 0.5);
        let lat_max = (latitude + radius).min(PI * 0.5);
        let latitude_cell_rad = PI / self.latitude_cells as f64;
        let first_lat = ((lat_min + PI * 0.5) / latitude_cell_rad)
            .floor()
            .clamp(0.0, (self.latitude_cells - 1) as f64) as usize;
        let last_lat = ((lat_max + PI * 0.5) / latitude_cell_rad)
            .floor()
            .clamp(0.0, (self.latitude_cells - 1) as f64) as usize;
        let reaches_pole = lat_min <= -PI * 0.5 || lat_max >= PI * 0.5;
        let maximum_abs_latitude = lat_min.abs().max(lat_max.abs());
        let longitude_span = if reaches_pole {
            PI
        } else {
            (radius / maximum_abs_latitude.cos().max(1e-9)).min(PI)
        };
        let center_lon = (longitude / self.cell_rad).floor() as isize;
        let half_lon_cells = (longitude_span / self.cell_rad).ceil() as isize + 1;
        let all_longitudes = half_lon_cells * 2 + 1 >= self.longitude_cells as isize;
        let mut candidates = Vec::new();
        for latitude_index in first_lat..=last_lat {
            if all_longitudes {
                for longitude_index in 0..self.longitude_cells {
                    append_brightness_prefix(
                        &mut candidates,
                        &self.cells[latitude_index * self.longitude_cells + longitude_index],
                        catalog_prefix,
                    );
                }
            } else {
                for offset in -half_lon_cells..=half_lon_cells {
                    let longitude_index =
                        (center_lon + offset).rem_euclid(self.longitude_cells as isize) as usize;
                    append_brightness_prefix(
                        &mut candidates,
                        &self.cells[latitude_index * self.longitude_cells + longitude_index],
                        catalog_prefix,
                    );
                }
            }
        }
        let cosine = radius.cos();
        candidates.retain(|&index| center.dot(stars[index as usize].vector) >= cosine);
        candidates
    }
}

fn append_brightness_prefix(output: &mut Vec<u32>, cell: &[u32], exclusive_limit: u32) {
    for &index in cell {
        if index >= exclusive_limit {
            break;
        }
        output.push(index);
    }
}

/// Write a generated structure with `tetra3-index`'s canonical deterministic writer.
pub fn write_index(
    path: impl AsRef<Path>,
    catalog: &CompactCatalog,
    generated: &GeneratedIndex,
    recipe: &BuildRecipe,
) -> Result<()> {
    use tetra3_index::writer::{IndexBuilder, LevelInput, PatternInput, VerificationStarInput};

    let path = path.as_ref();
    let mut builder = IndexBuilder::new();
    let epoch_milliyear = catalog_epoch_milliyear(catalog.info.epoch)?;
    builder
        .set_catalog_epoch_milliyear(epoch_milliyear)
        .map_err(|error| Error::Build(error.to_string()))?;
    builder
        .set_spatial_grid(
            recipe.spatial_grid.spatial_z_bins,
            recipe.spatial_grid.spatial_lon_bins,
        )
        .map_err(|error| Error::Build(error.to_string()))?;
    let mut verification_ids = Vec::with_capacity(catalog.stars.len());
    for star in &catalog.stars {
        let flags = star.flags;
        verification_ids.push(
            builder
                .add_verification_star(VerificationStarInput {
                    direction: [star.vector.x, star.vector.y, star.vector.z],
                    magnitude: star.magnitude,
                    flags,
                })
                .map_err(|error| Error::Build(error.to_string()))?,
        );
    }
    for level in &generated.levels {
        let patterns = level
            .patterns
            .iter()
            .map(|pattern| PatternInput {
                ratios: pattern.ratios_q8,
                verification_stars: pattern
                    .catalog_stars
                    .map(|catalog_id| verification_ids[catalog_id as usize]),
                largest_edge_q16: pattern.largest_edge_q16,
            })
            .collect();
        builder
            .add_level(LevelInput {
                min_fov_mdeg: degrees_to_millidegrees(level.min_horizontal_fov_deg),
                max_fov_mdeg: degrees_to_millidegrees(level.max_horizontal_fov_deg),
                default_tolerance_q8: recipe.default_tolerance_q8,
                neighbor_level_radius: 1,
                max_verification_magnitude_q4: Some(level.max_verification_magnitude_q4),
                patterns,
            })
            .map_err(|error| Error::Build(error.to_string()))?;
    }
    let bytes = builder
        .build()
        .map_err(|error| Error::Build(error.to_string()))?;
    write_bytes_atomic(path, &bytes)
}

fn catalog_epoch_milliyear(epoch: f64) -> Result<u32> {
    let scaled = epoch * 1_000.0;
    if !scaled.is_finite() || !(0.0..=10_000_000.0).contains(&scaled) {
        return Err(Error::Build(format!(
            "catalog epoch {epoch} is outside index range 0..=10000"
        )));
    }
    Ok(scaled.round() as u32)
}

fn degrees_to_millidegrees(value: f64) -> u32 {
    (value * 1_000.0).round().clamp(0.0, u32::MAX as f64) as u32
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::catalog::{CatalogInfo, STAR_FLAG_PATTERN_ELIGIBLE};

    #[test]
    fn default_levels_have_exact_endpoints_and_constant_ratio() {
        let levels = logarithmic_fov_levels(3.0, 45.0, 8);
        assert_eq!(levels[0], 3.0);
        assert_eq!(levels[7], 45.0);
        let ratio = levels[1] / levels[0];
        for pair in levels.windows(2) {
            assert!((pair[1] / pair[0] - ratio).abs() < 1e-12);
        }
    }

    #[test]
    fn single_level_requires_a_single_fov() {
        let recipe = BuildRecipe {
            level_count: 1,
            ..BuildRecipe::default()
        };
        let error = validate_build_options(&recipe, 4).unwrap_err();
        assert!(error.to_string().contains("--levels=1 requires equal"));

        let recipe = BuildRecipe {
            horizontal_fov_deg: [3.0, 3.0].into(),
            level_count: 1,
            pattern_rolls_per_field: 0,
            ..BuildRecipe::default()
        };
        let error = validate_build_options(&recipe, 4).unwrap_err();
        assert!(error.to_string().contains("--pattern-rolls-per-field"));
    }

    #[test]
    fn diagonal_uses_rectilinear_sensor_geometry() {
        let diagonal = diagonal_fov_deg(45.0, 4.0 / 3.0);
        assert!((diagonal - 54.747_255_743).abs() < 1e-9, "{diagonal}");
    }

    #[test]
    fn gnomonic_rectangle_filters_the_cap_for_each_roll() {
        let forward = Vec3::from_radec_deg(123.0, 37.0);
        let (right, down) = camera_tangent_basis(forward);
        assert!(cross(right, down).dot(forward) > 1.0 - 1e-12);
        assert!(right.dot(forward).abs() < 1e-12);
        assert!(down.dot(forward).abs() < 1e-12);
        let half_width = (60.0_f64.to_radians() * 0.5).tan();
        let half_height = half_width / 2.0;
        let coordinates = [
            (0.0, 0.0),
            (half_width * 0.999, 0.0),
            (half_width * 1.001, 0.0),
            (0.0, half_height * 0.999),
            (0.0, half_height * 1.001),
            (half_width * 0.75, half_height * 0.75),
        ];
        let stars = coordinates
            .into_iter()
            .enumerate()
            .map(|(index, (x, y))| CatalogStar {
                source_id: index as u64 + 1,
                vector: gnomonic_ray(forward, right, down, x, y),
                magnitude: index as f32,
                flags: STAR_FLAG_PATTERN_ELIGIBLE,
            })
            .collect::<Vec<_>>();
        let cap = (0..stars.len() as u32).collect::<Vec<_>>();
        assert_eq!(
            filter_gnomonic_rectangle(&cap, &stars, forward, 60.0, 2.0, 0.0),
            vec![0, 1, 3, 5]
        );
        let quarter_turn = filter_gnomonic_rectangle(&cap, &stars, forward, 60.0, 2.0, PI * 0.5);
        assert!(!quarter_turn.contains(&1));
        assert!(quarter_turn.contains(&3));

        let rolls = pattern_roll_angles(4);
        assert_eq!(rolls.len(), 4);
        for (index, roll) in rolls.into_iter().enumerate() {
            assert!((roll - index as f64 * PI / 4.0).abs() < f64::EPSILON);
            assert!((0.0..PI).contains(&roll));
        }
    }

    #[test]
    fn wide_levels_choose_a_brighter_verification_cutoff() {
        let stars = (0..10_000)
            .map(|index| CatalogStar {
                source_id: index as u64 + 1,
                vector: Vec3::from_radec_deg((index as f64 * 0.618_033) % 360.0, 0.0),
                magnitude: index as f32 / 1_000.0,
                flags: STAR_FLAG_PATTERN_ELIGIBLE,
            })
            .collect::<Vec<_>>();
        let recipe = BuildRecipe::default();
        let narrow = verification_magnitude_cutoff(&stars, 3.0, &recipe).unwrap();
        let wide = verification_magnitude_cutoff(&stars, 45.0, &recipe).unwrap();
        assert_eq!(narrow.catalog_prefix, stars.len());
        assert!(wide.catalog_prefix < narrow.catalog_prefix);
        assert!(wide.magnitude_q4 < narrow.magnitude_q4);
        assert!(rectangular_solid_angle(45.0, 4.0 / 3.0) > 0.4);
    }

    #[test]
    fn invariant_matches_known_ra_wrap_fixture() {
        let positions = [(359.30, -0.35), (359.85, 0.48), (0.37, -0.44), (0.66, 0.31)];
        let rays = positions.map(|(ra, dec)| {
            let value = Vec3::from_radec_deg(ra, dec);
            UnitVec3::new(value.x, value.y, value.z).unwrap()
        });
        let invariant = Invariant::from_rays(rays).unwrap();
        assert_eq!(invariant.quantized_q8(), [136, 140, 168, 178, 181]);
        assert!((invariant.largest_edge_rad().to_degrees() - 1.511680959).abs() < 1e-8);
    }

    #[test]
    fn runtime_vector_quantization_is_writer_idempotent() {
        let input = CatalogStar {
            source_id: 7,
            vector: Vec3::from_radec_deg(123.456_789, -78.901_234),
            magnitude: 5.432,
            flags: STAR_FLAG_PATTERN_ELIGIBLE,
        };
        let quantized = runtime_quantized_stars(&[input]).unwrap()[0];
        let first =
            tetra3_index::writer::encode_oct24([input.vector.x, input.vector.y, input.vector.z])
                .unwrap();
        let second = tetra3_index::writer::encode_oct24([
            quantized.vector.x,
            quantized.vector.y,
            quantized.vector.z,
        ])
        .unwrap();
        assert_eq!(first, second);
        let stored = VerificationStar {
            oct_x: first[0],
            oct_y: first[1],
            magnitude_q4: 22,
            flags: STAR_FLAG_PATTERN_ELIGIBLE,
        };
        assert_eq!(
            [quantized.vector.x, quantized.vector.y, quantized.vector.z],
            stored.direction_f64()
        );
        assert_eq!(quantized.magnitude, 5.5);
    }

    #[test]
    fn core_and_writer_share_floor_times_256_boundaries() {
        let boundary = 128.0 / 256.0;
        for middle in [boundary - 1e-12, boundary, boundary + 1e-12] {
            let ratios = [0.1, 0.2, middle, 0.8, 0.9];
            let invariant = Invariant::from_parts(ratios, 0.5).unwrap();
            let writer = ratios.map(|ratio| tetra3_index::writer::quantize_ratio(ratio).unwrap());
            assert_eq!(invariant.quantized_q8(), writer);
        }
    }

    #[test]
    fn compact_field_cap_and_collinearity_filter_are_enforced() {
        let stars = [
            (-1.0, -0.5),
            (-0.7, 0.8),
            (-0.2, -0.9),
            (0.1, 0.6),
            (0.4, -0.4),
            (0.8, 0.9),
            (1.0, -0.1),
        ]
        .into_iter()
        .enumerate()
        .map(|(index, (ra, dec))| CatalogStar {
            source_id: index as u64 + 1,
            vector: Vec3::from_radec_deg(ra, dec),
            magnitude: index as f32,
            flags: STAR_FLAG_PATTERN_ELIGIBLE,
        })
        .collect::<Vec<_>>();
        let selected = (0..stars.len() as u32).collect::<Vec<_>>();
        let recipe = BuildRecipe {
            max_patterns_per_field: 7,
            minimum_pattern_span_fraction: 0.0,
            minimum_edge_ratio: 0.0,
            minimum_covariance_ratio: 0.0,
            ..BuildRecipe::default()
        };
        let mut patterns = Vec::new();
        let stats = append_field_patterns(
            &selected,
            &stars,
            5.0_f64.to_radians(),
            &recipe,
            &mut patterns,
        )
        .unwrap();
        assert_eq!(patterns.len(), 7);
        assert_eq!(stats.fields_capped, 1);

        let collinear = (0..4)
            .map(|index| CatalogStar {
                source_id: index + 1,
                vector: Vec3::from_radec_deg(index as f64, 0.0),
                magnitude: index as f32,
                flags: STAR_FLAG_PATTERN_ELIGIBLE,
            })
            .collect::<Vec<_>>();
        let mut output = Vec::new();
        let quality = BuildRecipe {
            max_patterns_per_field: 0,
            minimum_pattern_span_fraction: 0.0,
            minimum_edge_ratio: 0.0,
            minimum_covariance_ratio: 0.02,
            ..BuildRecipe::default()
        };
        let rejected = append_field_patterns(
            &[0, 1, 2, 3],
            &collinear,
            5.0_f64.to_radians(),
            &quality,
            &mut output,
        )
        .unwrap();
        assert!(output.is_empty());
        assert_eq!(rejected.rejected_collinear, 1);
    }

    #[test]
    fn coverage_requires_an_accepted_quad_and_fails_closed() {
        let stars = (0..4)
            .map(|index| CatalogStar {
                source_id: index + 1,
                vector: Vec3::from_radec_deg(index as f64, 0.0),
                magnitude: index as f32,
                flags: STAR_FLAG_PATTERN_ELIGIBLE,
            })
            .collect::<Vec<_>>();
        let catalog = CompactCatalog {
            info: test_catalog_info(stars.len()),
            stars,
        };
        let exploratory = BuildRecipe {
            horizontal_fov_deg: [45.0, 45.0].into(),
            level_count: 1,
            pattern_rolls_per_field: 3,
            pattern_stars_per_field: 4,
            minimum_pattern_span_fraction: 0.0,
            minimum_edge_ratio: 0.0,
            minimum_covariance_ratio: 0.02,
            allow_uncovered_fields: true,
            ..BuildRecipe::default()
        };
        let generated = generate_index(&catalog, &exploratory, 0).unwrap();
        let stats = &generated.stats.levels[0];
        assert_eq!(stats.pattern_rolls_per_field, 3);
        assert_eq!(stats.lattice_fields, stats.lattice_centers * 3);
        assert_eq!(stats.fields_with_quad, 0);
        assert_eq!(stats.fields_without_quad, stats.lattice_fields);
        assert_eq!(generated.stats.patterns, 0);

        let strict = BuildRecipe {
            allow_uncovered_fields: false,
            ..exploratory
        };
        let error = generate_index(&catalog, &strict, 0).unwrap_err();
        assert!(error.to_string().contains("--allow-uncovered-fields"));
    }

    #[test]
    fn cross_level_duplicates_preserve_each_levels_edge_window() {
        let positions = [
            (101, 359.30, -0.35),
            (102, 359.85, 0.48),
            (103, 0.37, -0.44),
            (104, 0.66, 0.31),
            (105, 0.05, 0.80),
        ];
        let stars = positions
            .into_iter()
            .enumerate()
            .map(|(rank, (source_id, ra, dec))| CatalogStar {
                source_id,
                vector: Vec3::from_radec_deg(ra, dec),
                magnitude: rank as f32,
                flags: STAR_FLAG_PATTERN_ELIGIBLE,
            })
            .collect::<Vec<_>>();
        let catalog = CompactCatalog {
            info: test_catalog_info(stars.len()),
            stars,
        };
        let recipe = BuildRecipe {
            horizontal_fov_deg: [30.0, 45.0].into(),
            level_count: 2,
            pattern_stars_per_field: 5,
            max_patterns_per_field: 0,
            minimum_pattern_span_fraction: 0.0,
            minimum_edge_ratio: 0.0,
            minimum_covariance_ratio: 0.0,
            allow_uncovered_fields: true,
            ..BuildRecipe::default()
        };
        let generated = generate_index(&catalog, &recipe, 0).unwrap();
        let first = generated.levels[0]
            .patterns
            .iter()
            .map(|pattern| pattern.catalog_stars)
            .collect::<BTreeSet<_>>();
        let second = generated.levels[1]
            .patterns
            .iter()
            .map(|pattern| pattern.catalog_stars)
            .collect::<BTreeSet<_>>();
        assert!(first.intersection(&second).next().is_some());
        let union = first.union(&second).copied().collect::<BTreeSet<_>>();
        assert_eq!(generated.stats.patterns, union.len());
        assert_eq!(
            generated
                .stats
                .levels
                .iter()
                .map(|level| level.patterns_after_global_dedup)
                .sum::<usize>(),
            union.len()
        );
        assert!(
            generated
                .levels
                .iter()
                .map(|level| level.patterns.len())
                .sum::<usize>()
                > union.len()
        );

        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("shared-levels.t3ix");
        write_index(&path, &catalog, &generated, &recipe).unwrap();
        let bytes = std::fs::read(path).unwrap();
        let view = tetra3_index::IndexView::parse(&bytes).unwrap();
        for (level_index, level) in generated.levels.iter().enumerate() {
            let descriptor = view.level_descriptor(level_index as u16).unwrap();
            let expected_min = level
                .patterns
                .iter()
                .map(|pattern| pattern.largest_edge_q16)
                .min()
                .unwrap();
            let expected_max = level
                .patterns
                .iter()
                .map(|pattern| pattern.largest_edge_q16)
                .max()
                .unwrap();
            assert_eq!(descriptor.min_largest_edge_q16, expected_min);
            assert_eq!(descriptor.max_largest_edge_q16, expected_max);
        }
    }

    #[test]
    fn tiny_build_is_byte_deterministic_across_thread_counts() {
        let positions = [
            (101, 359.30, -0.35, 1.0),
            (102, 359.85, 0.48, 2.0),
            (103, 0.37, -0.44, 3.0),
            (104, 0.66, 0.31, 4.0),
            (105, 0.05, 0.80, 5.0),
            (201, 179.5, -0.4, 8.0),
            (202, 180.4, -0.3, 9.0),
            (203, 180.3, 0.6, 10.0),
            (204, 179.6, 0.5, 11.0),
        ];
        let stars = positions
            .map(|(source_id, ra, dec, magnitude)| CatalogStar {
                source_id,
                vector: Vec3::from_radec_deg(ra, dec),
                magnitude,
                flags: STAR_FLAG_PATTERN_ELIGIBLE,
            })
            .to_vec();
        let catalog = CompactCatalog {
            info: CatalogInfo {
                version: 1,
                records: stars.len() as u64,
                epoch: 2026.0,
                min_magnitude: -5.0,
                max_magnitude: 12.0,
                duplicate_radius_arcsec: 0.0,
                close_pair_radius_arcsec: 0.0,
                rows_invalid: 0,
                rows_removed: 0,
                records_sha256: [0; 32],
            },
            stars,
        };
        let recipe = BuildRecipe {
            horizontal_fov_deg: [45.0, 45.0].into(),
            level_count: 1,
            lattice_spacing_fraction: 0.55,
            pattern_stars_per_field: 5,
            minimum_pattern_span_fraction: 0.01,
            allow_uncovered_fields: true,
            ..BuildRecipe::default()
        };
        let generated_single = generate_index(&catalog, &recipe, 1).unwrap();
        let generated_parallel = generate_index(&catalog, &recipe, 4).unwrap();
        assert!(generated_single.stats.patterns >= 6);
        assert_eq!(
            generated_single
                .levels
                .iter()
                .flat_map(|level| &level.patterns)
                .copied()
                .collect::<Vec<_>>(),
            generated_parallel
                .levels
                .iter()
                .flat_map(|level| &level.patterns)
                .copied()
                .collect::<Vec<_>>()
        );

        let directory = tempfile::tempdir().unwrap();
        let first = directory.path().join("one.t3ix");
        let second = directory.path().join("four.t3ix");
        write_index(&first, &catalog, &generated_single, &recipe).unwrap();
        write_index(&second, &catalog, &generated_parallel, &recipe).unwrap();
        let bytes = std::fs::read(first).unwrap();
        assert_eq!(bytes, std::fs::read(second).unwrap());
        let index = tetra3_index::IndexView::parse(&bytes).unwrap();
        assert_eq!(index.catalog_epoch(), 2026.0);
        assert_eq!(
            index
                .level_descriptor(0)
                .unwrap()
                .max_verification_magnitude_q4,
            Some(generated_single.levels[0].max_verification_magnitude_q4)
        );
    }

    fn test_catalog_info(stars: usize) -> CatalogInfo {
        CatalogInfo {
            version: 1,
            records: stars as u64,
            epoch: 2026.0,
            min_magnitude: -5.0,
            max_magnitude: 12.0,
            duplicate_radius_arcsec: 0.0,
            close_pair_radius_arcsec: 0.0,
            rows_invalid: 0,
            rows_removed: 0,
            records_sha256: [0; 32],
        }
    }

    fn gnomonic_ray(forward: Vec3, right: Vec3, down: Vec3, x: f64, y: f64) -> Vec3 {
        Vec3::new(
            forward.x + x * right.x + y * down.x,
            forward.y + x * right.y + y * down.y,
            forward.z + x * right.z + y * down.z,
        )
        .normalized()
    }
}

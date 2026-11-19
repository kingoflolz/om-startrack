use std::collections::BTreeSet;
use std::fs::File;
use std::hint::black_box;
use std::io::Read;
use std::ops::ControlFlow;
use std::path::{Path, PathBuf};
use std::time::Instant;

use clap::{Args, Parser, Subcommand};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tetra3_index::IndexView;

use crate::builder::{BuildRecipe, generate_index, write_index};
use crate::catalog::{CATALOG_MAGIC, CompactCatalog, ConvertOptions, convert_gaia_csv};
use crate::fpr::ValidateFprArgs;
use crate::io_utils::{write_bytes_atomic, write_json_atomic};
use crate::validate::ValidateSolvesArgs;
use crate::validation_common::open_mapped;
use crate::{Error, Result};

#[derive(Debug, Parser)]
#[command(
    name = "tetra3",
    version,
    about = "Build and examine compact tetra3 plate-solver indexes"
)]
pub struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Calculate rectilinear FOV from detector pixels, pixel pitch, and focal length.
    Fov(FovArgs),
    /// Prepare source catalogs.
    Catalog(CatalogArgs),
    /// Build a deterministic multilevel flash index from a .t3cat catalog.
    Build(BuildArgs),
    /// Print catalog or index metadata without copying an index into memory.
    Inspect(InspectArgs),
    /// Fully validate checksums, directories, records, and references.
    Verify(VerifyArgs),
    /// Measure warm-cache index lookup overhead on the host.
    Bench(BenchArgs),
    /// Compile selected Lensfun XML profiles without linking Lensfun.
    Lensfun(LensfunArgs),
    /// Exercise the real solver against deterministic synthetic camera fields.
    ValidateSolves(ValidateSolvesArgs),
    /// Measure false locks on deterministic random camera fields.
    ValidateFpr(ValidateFprArgs),
}

#[derive(Debug, Args)]
struct FovArgs {
    #[arg(long)]
    active_width_px: u32,
    #[arg(long)]
    active_height_px: u32,
    #[arg(long)]
    pixel_pitch_um: f64,
    #[arg(long)]
    focal_length_mm: f64,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct CatalogArgs {
    #[command(subcommand)]
    command: CatalogCommand,
}

#[derive(Debug, Subcommand)]
enum CatalogCommand {
    /// Convert native Gaia or canonical merged CSV into a compact .t3cat file.
    Convert(ConvertArgs),
}

#[derive(Debug, Args)]
struct ConvertArgs {
    #[arg(short, long)]
    input: PathBuf,
    #[arg(short, long)]
    output: PathBuf,
    /// Fallback reference epoch for rows without ref_epoch_jyear.
    #[arg(long, default_value_t = ConvertOptions::default().input_epoch)]
    input_epoch: f64,
    #[arg(long, default_value_t = ConvertOptions::default().output_epoch)]
    epoch: f64,
    #[arg(long, default_value_t = ConvertOptions::default().min_magnitude)]
    min_magnitude: f32,
    #[arg(long, default_value_t = ConvertOptions::default().max_magnitude)]
    max_magnitude: f32,
    #[arg(
        long,
        default_value_t = ConvertOptions::default().duplicate_radius_arcsec
    )]
    duplicate_radius_arcsec: f64,
    #[arg(
        long,
        default_value_t = ConvertOptions::default().close_pair_radius_arcsec
    )]
    close_pair_radius_arcsec: f64,
    /// Skip malformed rows and count them instead of stopping conversion.
    #[arg(long)]
    skip_invalid: bool,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct BuildArgs {
    #[arg(short, long)]
    catalog: PathBuf,
    #[arg(short, long)]
    output: PathBuf,
    /// Deterministic provenance path; defaults to OUTPUT.manifest.json.
    #[arg(long)]
    manifest: Option<PathBuf>,
    #[command(flatten)]
    recipe: BuildRecipe,
    /// Worker threads; zero selects host parallelism.
    #[arg(long, default_value_t = 0)]
    threads: usize,
    /// If an existing output passes full validation, do not rebuild it.
    #[arg(long)]
    resume: bool,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct InspectArgs {
    path: PathBuf,
    #[arg(long)]
    json: bool,
    /// For a catalog, skip the records SHA; indexes always remain structurally checked.
    #[arg(long)]
    no_checksums: bool,
}

#[derive(Debug, Args)]
struct VerifyArgs {
    path: PathBuf,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct BenchArgs {
    path: PathBuf,
    #[arg(long, default_value_t = 100_000)]
    lookups: u64,
    #[arg(long, default_value_t = 2)]
    tolerance_q8: u8,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct LensfunArgs {
    #[command(subcommand)]
    command: LensfunCommand,
}

#[derive(Debug, Subcommand)]
enum LensfunCommand {
    /// Compile exact camera/lens selections to a compact .t3lf database.
    Compile(LensfunCompileArgs),
}

#[derive(Debug, Args)]
struct LensfunCompileArgs {
    /// Lensfun XML file or directory; repeat to supply additional documents.
    /// A directory expands to its immediate `*.xml` files in lexical order.
    #[arg(long, required = true)]
    xml: Vec<PathBuf>,
    /// JSON recipe containing exact and/or compatible-lens selections.
    #[arg(long)]
    profiles: PathBuf,
    #[arg(short, long)]
    output: PathBuf,
    /// Optional JSON selection manifest (included, skipped, no-distortion).
    #[arg(long)]
    report: Option<PathBuf>,
    #[arg(long)]
    json: bool,
}

pub fn run() -> Result<()> {
    run_cli(Cli::parse())
}

fn run_cli(cli: Cli) -> Result<()> {
    match cli.command {
        Command::Fov(arguments) => fov(arguments),
        Command::Catalog(arguments) => match arguments.command {
            CatalogCommand::Convert(arguments) => convert(arguments),
        },
        Command::Build(arguments) => build(arguments),
        Command::Inspect(arguments) => inspect(arguments),
        Command::Verify(arguments) => verify(arguments),
        Command::Bench(arguments) => bench(arguments),
        Command::Lensfun(arguments) => match arguments.command {
            LensfunCommand::Compile(arguments) => lensfun_compile(arguments),
        },
        Command::ValidateSolves(arguments) => crate::validate::run(arguments),
        Command::ValidateFpr(arguments) => crate::fpr::run(arguments),
    }
}

fn fov(arguments: FovArgs) -> Result<()> {
    let calculation = crate::fov::calculate_rectilinear_fov(
        arguments.active_width_px,
        arguments.active_height_px,
        arguments.pixel_pitch_um,
        arguments.focal_length_mm,
    )?;
    print_value(&calculation, arguments.json)
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct LensfunRequestDocument {
    #[serde(default)]
    source: Option<LensfunSourceSpec>,
    #[serde(default)]
    profiles: Vec<LensfunProfileSpec>,
    #[serde(default)]
    compatible_lenses: Vec<LensfunCompatibleSpec>,
    #[serde(default)]
    audit_lenses: Vec<LensfunAuditSpec>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct LensfunSourceSpec {
    repository: String,
    revision: String,
    database_format: u16,
    database_license: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct LensfunAuditSpec {
    lens_maker: String,
    lens_model: String,
    #[serde(default)]
    do_not_substitute: bool,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct LensfunProfileSpec {
    profile_id: String,
    camera_maker: String,
    camera_model: String,
    #[serde(default)]
    camera_variant: Option<String>,
    lens_maker: String,
    lens_model: String,
    calibration_width: u32,
    calibration_height: u32,
    crop_x: u32,
    crop_y: u32,
    crop_width: u32,
    crop_height: u32,
    encoded_width: u32,
    encoded_height: u32,
    #[serde(default = "normal_orientation")]
    orientation: u8,
    #[serde(default)]
    calibration_crop_factor: Option<f32>,
    #[serde(default)]
    calibration_aspect_ratio: Option<f32>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct LensfunCompatibleSpec {
    all_compatible_lenses: bool,
    profile_id_prefix: String,
    camera_maker: String,
    camera_model: String,
    #[serde(default)]
    camera_variant: Option<String>,
    /// Optional exact native-mount filter after compatibility traversal.
    #[serde(default)]
    lens_mount: Option<String>,
    calibration_width: u32,
    calibration_height: u32,
    crop_x: u32,
    crop_y: u32,
    crop_width: u32,
    crop_height: u32,
    encoded_width: u32,
    encoded_height: u32,
    #[serde(default = "normal_orientation")]
    orientation: u8,
    #[serde(default)]
    calibration_crop_factor: Option<f32>,
    #[serde(default)]
    calibration_aspect_ratio: Option<f32>,
}

const fn normal_orientation() -> u8 {
    1
}

fn lensfun_compile(arguments: LensfunCompileArgs) -> Result<()> {
    let request_bytes = std::fs::read(&arguments.profiles)
        .map_err(|error| Error::io(&arguments.profiles, error))?;
    let recipe_sha256 = hex(&sha256_bytes(&request_bytes));
    let document: LensfunRequestDocument = serde_json::from_slice(&request_bytes)
        .map_err(|error| Error::Serialization(error.to_string()))?;
    if document.profiles.is_empty() && document.compatible_lenses.is_empty() {
        return Err(Error::InvalidArgument(
            "Lensfun request contains no profile recipes".into(),
        ));
    }
    let declared_source = document.source;
    let audit_lenses = document.audit_lenses;
    let requests = document
        .profiles
        .into_iter()
        .map(|profile| {
            Ok(crate::lensfun_xml::ProfileRequest {
                profile_id: profile.profile_id,
                camera: crate::lensfun_xml::CameraId {
                    maker: profile.camera_maker,
                    model: profile.camera_model,
                    variant: profile.camera_variant,
                },
                lens: crate::lensfun_xml::LensId {
                    maker: profile.lens_maker,
                    model: profile.lens_model,
                },
                raster: crate::lensfun_xml::RasterGeometry {
                    calibration_width: profile.calibration_width,
                    calibration_height: profile.calibration_height,
                    crop: tetra3_lens::CropRect::new(
                        profile.crop_x,
                        profile.crop_y,
                        profile.crop_width,
                        profile.crop_height,
                    ),
                    encoded_width: profile.encoded_width,
                    encoded_height: profile.encoded_height,
                    orientation: tetra3_lens::Orientation::from_exif(profile.orientation)
                        .map_err(|error| Error::InvalidArgument(error.to_string()))?,
                },
                calibration: crate::lensfun_xml::CalibrationSelector {
                    crop_factor: profile.calibration_crop_factor,
                    aspect_ratio: profile.calibration_aspect_ratio,
                },
            })
        })
        .collect::<Result<Vec<_>>>()?;
    let compatible_requests = document
        .compatible_lenses
        .into_iter()
        .map(|profile| {
            if !profile.all_compatible_lenses {
                return Err(Error::InvalidArgument(
                    "compatible_lenses entries require all_compatible_lenses=true".into(),
                ));
            }
            Ok(crate::lensfun_xml::CompatibleProfileRequest {
                profile_id_prefix: profile.profile_id_prefix,
                camera: crate::lensfun_xml::CameraId {
                    maker: profile.camera_maker,
                    model: profile.camera_model,
                    variant: profile.camera_variant,
                },
                raster: crate::lensfun_xml::RasterGeometry {
                    calibration_width: profile.calibration_width,
                    calibration_height: profile.calibration_height,
                    crop: tetra3_lens::CropRect::new(
                        profile.crop_x,
                        profile.crop_y,
                        profile.crop_width,
                        profile.crop_height,
                    ),
                    encoded_width: profile.encoded_width,
                    encoded_height: profile.encoded_height,
                    orientation: tetra3_lens::Orientation::from_exif(profile.orientation)
                        .map_err(|error| Error::InvalidArgument(error.to_string()))?,
                },
                calibration: crate::lensfun_xml::CalibrationSelector {
                    crop_factor: profile.calibration_crop_factor,
                    aspect_ratio: profile.calibration_aspect_ratio,
                },
                lens_mount: profile.lens_mount,
            })
        })
        .collect::<Result<Vec<_>>>()?;
    let xml_paths = expand_lensfun_xml_paths(&arguments.xml)?;
    let mut xml_storage = Vec::with_capacity(xml_paths.len());
    for path in &xml_paths {
        let contents = std::fs::read_to_string(path).map_err(|error| Error::io(path, error))?;
        xml_storage.push((path.to_string_lossy().into_owned(), contents));
    }
    let (source_set_sha256, source_documents) = lensfun_source_provenance(&xml_storage);
    let xml = xml_storage
        .iter()
        .map(|(source_name, contents)| crate::lensfun_xml::LensfunXml {
            source_name,
            contents,
        })
        .collect::<Vec<_>>();
    let (bytes, report) =
        crate::lensfun_xml::compile_t3lf_with_compatible(&xml, &requests, &compatible_requests)
            .map_err(|error| Error::Build(error.to_string()))?;
    write_bytes_atomic(&arguments.output, &bytes)?;
    let database = tetra3_lens::Database::from_t3lf(&bytes)
        .map_err(|error| Error::Build(error.to_string()))?;
    let included = report
        .included
        .iter()
        .map(|entry| {
            serde_json::json!({
                "profile_id": entry.profile_id,
                "lens_maker": entry.lens.maker,
                "lens_model": entry.lens.model,
            })
        })
        .collect::<Vec<_>>();
    let skipped = report
        .skipped
        .iter()
        .map(|entry| match entry.reason {
            crate::lensfun_xml::SkipReason::NoMatchingCalibration => serde_json::json!({
                "lens_maker": entry.lens.maker,
                "lens_model": entry.lens.model,
                "reason": "no_matching_calibration",
            }),
            crate::lensfun_xml::SkipReason::AmbiguousCalibration { matches } => serde_json::json!({
                "lens_maker": entry.lens.maker,
                "lens_model": entry.lens.model,
                "reason": "ambiguous_calibration",
                "matches": matches,
            }),
        })
        .collect::<Vec<_>>();
    let no_distortion = report
        .no_distortion
        .iter()
        .map(|lens| {
            serde_json::json!({
                "lens_maker": lens.maker,
                "lens_model": lens.model,
            })
        })
        .collect::<Vec<_>>();
    let lens_audit = audit_lenses
        .iter()
        .map(|audit| {
            let lens = crate::lensfun_xml::LensId {
                maker: audit.lens_maker.clone(),
                model: audit.lens_model.clone(),
            };
            let status = if report.included.iter().any(|entry| entry.lens == lens) {
                "included"
            } else if report.skipped.iter().any(|entry| entry.lens == lens) {
                "skipped"
            } else if report.no_distortion.iter().any(|entry| entry == &lens) {
                "no_distortion"
            } else {
                "absent_from_selected_lens_set"
            };
            serde_json::json!({
                "lens_maker": audit.lens_maker,
                "lens_model": audit.lens_model,
                "status": status,
                "do_not_substitute": audit.do_not_substitute,
            })
        })
        .collect::<Vec<_>>();
    let output_sha256 = hex(&sha256_bytes(&bytes));
    let output = serde_json::json!({
        "format": "t3lf",
        "profiles": database.profiles().len(),
        "profile_ids": database
            .profiles()
            .iter()
            .map(|profile| profile.id().as_str())
            .collect::<Vec<_>>(),
        "bytes": bytes.len(),
        "xml_documents": xml_paths.len(),
        "compatible_lenses": report.compatible_lenses,
        "included_count": included.len(),
        "skipped_count": skipped.len(),
        "no_distortion_count": no_distortion.len(),
        "included": included,
        "skipped": skipped,
        "no_distortion": no_distortion,
        "lens_audit": lens_audit,
        "output_sha256": output_sha256,
        "provenance": {
            "compiler": format!("tetra3-cli {}", env!("CARGO_PKG_VERSION")),
            "declared_source": declared_source,
            "recipe_path": arguments.profiles.to_string_lossy(),
            "recipe_sha256": recipe_sha256,
            "source_set_sha256": source_set_sha256,
            "source_documents": source_documents,
        },
    });
    if let Some(path) = arguments.report {
        write_json_atomic(&path, &output)?;
    }
    print_value(&output, arguments.json)
}

fn lensfun_source_provenance(xml_storage: &[(String, String)]) -> (String, Vec<serde_json::Value>) {
    let mut documents = xml_storage
        .iter()
        .map(|(path, contents)| {
            let name = Path::new(path)
                .file_name()
                .map_or_else(|| path.clone(), |name| name.to_string_lossy().into_owned());
            (name, sha256_bytes(contents.as_bytes()))
        })
        .collect::<Vec<_>>();
    documents.sort_unstable_by(|left, right| left.0.cmp(&right.0).then(left.1.cmp(&right.1)));

    let mut digest = Sha256::new();
    digest.update(b"tetra3-lensfun-source-set-v1\0");
    for (name, sha256) in &documents {
        digest.update((name.len() as u64).to_le_bytes());
        digest.update(name.as_bytes());
        digest.update(sha256);
    }
    let source_set_sha256 = hex(&digest.finalize());
    let documents = documents
        .into_iter()
        .map(|(name, sha256)| {
            serde_json::json!({
                "name": name,
                "sha256": hex(&sha256),
            })
        })
        .collect();
    (source_set_sha256, documents)
}

fn expand_lensfun_xml_paths(inputs: &[PathBuf]) -> Result<Vec<PathBuf>> {
    let mut paths = BTreeSet::new();
    for input in inputs {
        let metadata = std::fs::metadata(input).map_err(|error| Error::io(input, error))?;
        if metadata.is_dir() {
            let entries = std::fs::read_dir(input).map_err(|error| Error::io(input, error))?;
            for entry in entries {
                let entry = entry.map_err(|error| Error::io(input, error))?;
                let path = entry.path();
                let file_type = entry.file_type().map_err(|error| Error::io(&path, error))?;
                if file_type.is_file() && path.extension().is_some_and(|value| value == "xml") {
                    paths.insert(path);
                }
            }
        } else if metadata.is_file() {
            paths.insert(input.clone());
        } else {
            return Err(Error::InvalidArgument(format!(
                "Lensfun XML input {} is neither a file nor a directory",
                input.display()
            )));
        }
    }
    if paths.is_empty() {
        return Err(Error::InvalidArgument(
            "Lensfun XML inputs contain no documents".into(),
        ));
    }
    Ok(paths.into_iter().collect())
}

fn convert(arguments: ConvertArgs) -> Result<()> {
    let options = ConvertOptions {
        input_epoch: arguments.input_epoch,
        output_epoch: arguments.epoch,
        min_magnitude: arguments.min_magnitude,
        max_magnitude: arguments.max_magnitude,
        duplicate_radius_arcsec: arguments.duplicate_radius_arcsec,
        close_pair_radius_arcsec: arguments.close_pair_radius_arcsec,
        strict: !arguments.skip_invalid,
    };
    let stats = convert_gaia_csv(arguments.input, arguments.output, &options)?;
    print_value(&stats, arguments.json)
}

fn build(arguments: BuildArgs) -> Result<()> {
    let BuildArgs {
        catalog: catalog_path,
        output,
        manifest,
        recipe,
        threads,
        resume,
        json,
    } = arguments;
    let catalog_sha256 = sha256_file(&catalog_path)?;
    let manifest_path = manifest.unwrap_or_else(|| appended_extension(&output, "manifest.json"));
    if resume && resume_matches(&output, &manifest_path, catalog_sha256, &recipe)? {
        let output = serde_json::json!({
            "resumed": true,
            "output": output,
            "manifest": manifest_path,
            "status": "index, provenance, and build options match",
        });
        return print_value(&output, json);
    }
    let catalog = CompactCatalog::read(&catalog_path, true)?;
    let generated = generate_index(&catalog, &recipe, threads)?;
    write_index(&output, &catalog, &generated, &recipe)?;
    // Re-open through the exact Android-side parser before publishing provenance.
    let mapped = open_mapped(&output)?;
    let mapped_view = mapped.view();
    if (mapped_view.catalog_epoch() - catalog.info.epoch).abs() > 0.000_500_1 {
        return Err(Error::InvalidIndex(
            "written index catalog epoch disagrees with source catalog".into(),
        ));
    }
    let index_sha256 = sha256_bytes(mapped.as_bytes());
    let manifest = BuildManifest::new(
        &catalog,
        catalog_sha256,
        index_sha256,
        recipe,
        &generated.stats,
    );
    write_json_atomic(&manifest_path, &manifest)?;
    print_value(&manifest, json)
}

fn resume_matches(
    output: &Path,
    manifest: &Path,
    catalog_sha256: [u8; 32],
    recipe: &BuildRecipe,
) -> Result<bool> {
    if !output.exists() || !manifest.exists() {
        return Ok(false);
    }
    let mapped = match open_mapped(output) {
        Ok(mapped) => mapped,
        Err(_) => return Ok(false),
    };
    let manifest_bytes = std::fs::read(manifest).map_err(|error| Error::io(manifest, error))?;
    let value: serde_json::Value = serde_json::from_slice(&manifest_bytes)
        .map_err(|error| Error::Serialization(error.to_string()))?;
    let mapped_view = mapped.view();
    if value
        .get("catalog_epoch")
        .and_then(serde_json::Value::as_f64)
        != Some(mapped_view.catalog_epoch())
    {
        return Ok(false);
    }
    let expected = [
        ("format", serde_json::json!("tetra3-build-manifest-v3")),
        (
            "generator",
            serde_json::json!(concat!(
                env!("CARGO_PKG_NAME"),
                " ",
                env!("CARGO_PKG_VERSION")
            )),
        ),
        ("catalog_sha256", serde_json::json!(hex(&catalog_sha256))),
        (
            "index_sha256",
            serde_json::json!(hex(&sha256_bytes(mapped.as_bytes()))),
        ),
        (
            "index_format_version",
            serde_json::json!(index_format_version()),
        ),
        (
            "verification_density_metadata",
            verification_density_metadata(),
        ),
    ];
    let fixed_fields_match = expected
        .iter()
        .all(|(key, expected)| value.get(*key) == Some(expected));
    let recorded_recipe = serde_json::from_value::<BuildRecipe>(value).ok();
    Ok(fixed_fields_match && recorded_recipe.as_ref() == Some(recipe))
}

#[derive(Debug, Serialize)]
struct BuildManifest<'a> {
    format: &'static str,
    generator: &'static str,
    catalog_sha256: String,
    catalog_epoch: f64,
    catalog_records_sha256: String,
    index_sha256: String,
    index_format_version: String,
    verification_density_metadata: serde_json::Value,
    #[serde(flatten)]
    recipe: BuildRecipe,
    stats: &'a crate::builder::BuildStats,
}

impl<'a> BuildManifest<'a> {
    fn new(
        catalog: &CompactCatalog,
        catalog_sha256: [u8; 32],
        index_sha256: [u8; 32],
        recipe: BuildRecipe,
        stats: &'a crate::builder::BuildStats,
    ) -> Self {
        Self {
            format: "tetra3-build-manifest-v3",
            generator: concat!(env!("CARGO_PKG_NAME"), " ", env!("CARGO_PKG_VERSION")),
            catalog_sha256: hex(&catalog_sha256),
            catalog_epoch: (catalog.info.epoch * 1_000.0).round() / 1_000.0,
            catalog_records_sha256: hex(&catalog.info.records_sha256),
            index_sha256: hex(&index_sha256),
            index_format_version: index_format_version(),
            verification_density_metadata: verification_density_metadata(),
            recipe,
            stats,
        }
    }
}

fn index_format_version() -> String {
    format!(
        "{}.{}",
        tetra3_index::VERSION_MAJOR,
        tetra3_index::VERSION_MINOR
    )
}

fn verification_density_metadata() -> serde_json::Value {
    serde_json::json!({
        "stored_value": "exact verification row count at the level magnitude cutoff",
        "magnitude_units": "signed quarter magnitudes",
        "cutoff_inclusive": true,
        "density_interpretation": "runtime applies its clustering safety factor to count/(4*pi); no density scalar is stored",
    })
}

#[derive(Debug, Serialize)]
struct CatalogSummary {
    format: &'static str,
    version: u16,
    file_bytes: u64,
    records: u64,
    epoch: f64,
    magnitude_range: [f32; 2],
    duplicate_radius_arcsec: f32,
    close_pair_radius_arcsec: f32,
    rows_invalid: u64,
    rows_removed: u64,
    records_sha256: String,
    checksum_verified: bool,
}

#[derive(Debug, Serialize)]
struct IndexLevelSummary {
    level: u16,
    min_horizontal_fov_deg: f32,
    max_horizontal_fov_deg: f32,
    patterns: u32,
    default_tolerance_q8: u8,
    neighbor_level_radius: u8,
    largest_edge_q16: [u16; 2],
    max_verification_magnitude_q4: Option<i8>,
    max_verification_magnitude: Option<f32>,
    verification_stars: u32,
    verification_density_per_steradian: f64,
}

#[derive(Debug, Serialize)]
struct IndexSummary {
    format: &'static str,
    version: String,
    file_bytes: usize,
    catalog_epoch: f64,
    sections: u32,
    verification_stars: u32,
    pattern_stars: u32,
    spatial_grid: [u16; 2],
    levels: Vec<IndexLevelSummary>,
    fully_verified: bool,
}

fn inspect(arguments: InspectArgs) -> Result<()> {
    let mut prefix = [0_u8; 8];
    let mut file =
        File::open(&arguments.path).map_err(|error| Error::io(&arguments.path, error))?;
    file.read_exact(&mut prefix)
        .map_err(|error| Error::io(&arguments.path, error))?;
    if prefix == CATALOG_MAGIC {
        let catalog = CompactCatalog::read(&arguments.path, !arguments.no_checksums)?;
        let summary = CatalogSummary {
            format: "t3cat",
            version: catalog.info.version,
            file_bytes: std::fs::metadata(&arguments.path)
                .map_err(|error| Error::io(&arguments.path, error))?
                .len(),
            records: catalog.info.records,
            epoch: catalog.info.epoch,
            magnitude_range: [catalog.info.min_magnitude, catalog.info.max_magnitude],
            duplicate_radius_arcsec: catalog.info.duplicate_radius_arcsec,
            close_pair_radius_arcsec: catalog.info.close_pair_radius_arcsec,
            rows_invalid: catalog.info.rows_invalid,
            rows_removed: catalog.info.rows_removed,
            records_sha256: hex(&catalog.info.records_sha256),
            checksum_verified: !arguments.no_checksums,
        };
        return print_value(&summary, arguments.json);
    }
    if prefix == tetra3_lens::T3LF_MAGIC {
        let bytes =
            std::fs::read(&arguments.path).map_err(|error| Error::io(&arguments.path, error))?;
        let database = tetra3_lens::Database::from_t3lf(&bytes)
            .map_err(|error| Error::InvalidArgument(error.to_string()))?;
        let profiles = database
            .profiles()
            .iter()
            .map(|profile| {
                serde_json::json!({
                    "id": profile.id().as_str(),
                    "projection": format!("{:?}", profile.projection()),
                    "distortion_model": format!("{:?}", profile.knots()[0].model()),
                    "knots": profile.knots().len(),
                    "focal_range_mm": [
                        profile.knots().first().map(|knot| knot.focal_mm()),
                        profile.knots().last().map(|knot| knot.focal_mm()),
                    ],
                    "calibration_crop_factor": profile.calibration().crop_factor(),
                    "calibration_aspect_ratio": profile.calibration().aspect_ratio(),
                    "center": [
                        profile.calibration().center_x(),
                        profile.calibration().center_y(),
                    ],
                })
            })
            .collect::<Vec<_>>();
        return print_value(
            &serde_json::json!({
                "format": "t3lf",
                "version": tetra3_lens::T3LF_VERSION,
                "file_bytes": bytes.len(),
                "profiles": profiles,
                "fully_verified": true,
            }),
            arguments.json,
        );
    }
    if prefix[0..4] == tetra3_index::MAGIC {
        let mapping = map_read_only(&arguments.path)?;
        let view = if arguments.no_checksums {
            IndexView::parse_without_checksums(&mapping)
        } else {
            IndexView::parse(&mapping)
        }
        .map_err(|error| Error::InvalidIndex(error.to_string()))?;
        return print_value(
            &summarize_index(view, !arguments.no_checksums)?,
            arguments.json,
        );
    }
    Err(Error::InvalidArgument(format!(
        "{} is not a .t3cat catalog, .t3ix index, or .t3lf lens database",
        arguments.path.display()
    )))
}

fn verify(arguments: VerifyArgs) -> Result<()> {
    let mut prefix = [0_u8; 8];
    let mut file =
        File::open(&arguments.path).map_err(|error| Error::io(&arguments.path, error))?;
    file.read_exact(&mut prefix)
        .map_err(|error| Error::io(&arguments.path, error))?;
    let output = if prefix == CATALOG_MAGIC {
        let catalog = CompactCatalog::read(&arguments.path, true)?;
        serde_json::json!({
            "format": "t3cat",
            "records": catalog.info.records,
            "verified": true,
        })
    } else if prefix == tetra3_lens::T3LF_MAGIC {
        let bytes =
            std::fs::read(&arguments.path).map_err(|error| Error::io(&arguments.path, error))?;
        let database = tetra3_lens::Database::from_t3lf(&bytes)
            .map_err(|error| Error::InvalidArgument(error.to_string()))?;
        serde_json::json!({
            "format": "t3lf",
            "bytes": bytes.len(),
            "profiles": database.profiles().len(),
            "verified": true,
        })
    } else if prefix[0..4] == tetra3_index::MAGIC {
        let mapped = open_mapped(&arguments.path)?;
        let view = mapped.view();
        serde_json::json!({
            "format": "t3ix",
            "bytes": view.as_bytes().len(),
            "levels": view.level_count(),
            "verification_stars": view.verification_star_count(),
            "pattern_stars": view.pattern_star_count(),
            "catalog_epoch": view.catalog_epoch(),
            "verified": true,
        })
    } else {
        return Err(Error::InvalidArgument("unknown input magic".into()));
    };
    print_value(&output, arguments.json)
}

#[derive(Debug, Serialize)]
struct BenchSummary {
    lookups: u64,
    elapsed_seconds: f64,
    lookups_per_second: f64,
    records_examined: u64,
    candidates_emitted: u64,
    mapped_bytes: usize,
}

fn bench(arguments: BenchArgs) -> Result<()> {
    if arguments.lookups == 0 {
        return Err(Error::InvalidArgument("--lookups must be positive".into()));
    }
    let mapped = open_mapped(&arguments.path)?;
    let index = mapped.view();
    if index.level_count() == 0 {
        return Err(Error::InvalidIndex("index contains no levels".into()));
    }
    let mut state = 0x4d59_5df4_d0f3_3173_u64;
    let start = Instant::now();
    let mut records_examined = 0_u64;
    let mut candidates_emitted = 0_u64;
    for lookup in 0..arguments.lookups {
        let mut ratios = [0_u8; 5];
        for ratio in &mut ratios {
            state = xorshift64(state);
            *ratio = state as u8;
        }
        ratios.sort_unstable();
        let level_number = (lookup % u64::from(index.level_count())) as u16;
        let level = index
            .level(level_number)
            .map_err(|error| Error::InvalidIndex(error.to_string()))?;
        let descriptor = level.descriptor();
        let stats = level
            .visit_candidates(
                ratios,
                arguments.tolerance_q8,
                descriptor.min_largest_edge_q16..=descriptor.max_largest_edge_q16,
                &mut |candidate| {
                    black_box(candidate);
                    ControlFlow::Continue(())
                },
            )
            .map_err(|error| Error::InvalidIndex(error.to_string()))?;
        records_examined += u64::from(stats.records_examined);
        candidates_emitted += u64::from(stats.candidates_emitted);
    }
    let elapsed = start.elapsed().as_secs_f64();
    print_value(
        &BenchSummary {
            lookups: arguments.lookups,
            elapsed_seconds: elapsed,
            lookups_per_second: arguments.lookups as f64 / elapsed,
            records_examined,
            candidates_emitted,
            mapped_bytes: index.as_bytes().len(),
        },
        arguments.json,
    )
}

fn summarize_index(index: IndexView<'_>, fully_verified: bool) -> Result<IndexSummary> {
    let mut levels = Vec::with_capacity(index.level_count() as usize);
    for level in 0..index.level_count() {
        let descriptor = index
            .level_descriptor(level)
            .map_err(|error| Error::InvalidIndex(error.to_string()))?;
        levels.push(IndexLevelSummary {
            level,
            min_horizontal_fov_deg: descriptor.min_fov_degrees(),
            max_horizontal_fov_deg: descriptor.max_fov_degrees(),
            patterns: descriptor.pattern_count,
            default_tolerance_q8: descriptor.default_tolerance_q8,
            neighbor_level_radius: descriptor.neighbor_level_radius,
            largest_edge_q16: [
                descriptor.min_largest_edge_q16,
                descriptor.max_largest_edge_q16,
            ],
            max_verification_magnitude_q4: descriptor.max_verification_magnitude_q4,
            max_verification_magnitude: descriptor
                .max_verification_magnitude_q4
                .map(|value| f32::from(value) * 0.25),
            verification_stars: descriptor.verification_star_count,
            verification_density_per_steradian: f64::from(descriptor.verification_star_count)
                / (4.0 * std::f64::consts::PI),
        });
    }
    let grid = index
        .spatial_grid()
        .map_err(|error| Error::InvalidIndex(error.to_string()))?;
    Ok(IndexSummary {
        format: "t3ix",
        version: format!(
            "{}.{}",
            tetra3_index::VERSION_MAJOR,
            tetra3_index::VERSION_MINOR
        ),
        file_bytes: index.as_bytes().len(),
        catalog_epoch: index.catalog_epoch(),
        sections: index.section_count(),
        verification_stars: index.verification_star_count(),
        pattern_stars: index.pattern_star_count(),
        spatial_grid: [grid.z_bins, grid.longitude_bins],
        levels,
        fully_verified,
    })
}

fn map_read_only(path: &Path) -> Result<memmap2::Mmap> {
    let file = File::open(path).map_err(|error| Error::io(path, error))?;
    // SAFETY: the mapping is read-only and MappedIndex owns it for the entire
    // lifetime of every borrowed IndexView. External mutation is detected as a
    // normal index validation failure at open time; the CLI never mutates it.
    let mapping = unsafe { memmap2::MmapOptions::new().map(&file) }
        .map_err(|error| Error::io(path, error))?;
    Ok(mapping)
}

fn sha256_file(path: &Path) -> Result<[u8; 32]> {
    let mut file = File::open(path).map_err(|error| Error::io(path, error))?;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 64 * 1_024];
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|error| Error::io(path, error))?;
        if count == 0 {
            break;
        }
        digest.update(&buffer[..count]);
    }
    Ok(digest.finalize().into())
}

fn sha256_bytes(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}

fn appended_extension(path: &Path, suffix: &str) -> PathBuf {
    let mut value = path.as_os_str().to_owned();
    value.push(".");
    value.push(suffix);
    value.into()
}

fn xorshift64(mut value: u64) -> u64 {
    value ^= value << 13;
    value ^= value >> 7;
    value ^ (value << 17)
}

pub(crate) fn print_value(value: &impl Serialize, json: bool) -> Result<()> {
    if json {
        println!(
            "{}",
            serde_json::to_string_pretty(value)
                .map_err(|error| Error::Serialization(error.to_string()))?
        );
    } else {
        println!(
            "{}",
            serde_json::to_string(value)
                .map_err(|error| Error::Serialization(error.to_string()))?
        );
    }
    Ok(())
}

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut result = String::with_capacity(bytes.len() * 2);
    for &byte in bytes {
        result.push(DIGITS[usize::from(byte >> 4)] as char);
        result.push(DIGITS[usize::from(byte & 0x0f)] as char);
    }
    result
}

#[cfg(test)]
mod build_cli_tests {
    use super::*;
    use crate::builder::BuildStats;
    use crate::catalog::CatalogInfo;

    fn parse_build(arguments: &[&str]) -> BuildArgs {
        let cli = Cli::try_parse_from(arguments).unwrap();
        let Command::Build(arguments) = cli.command else {
            panic!("expected build command");
        };
        arguments
    }

    #[test]
    fn catalog_convert_cli_defaults_match_convert_options() {
        let cli = Cli::try_parse_from([
            "tetra3",
            "catalog",
            "convert",
            "--input",
            "stars.csv",
            "--output",
            "stars.t3cat",
        ])
        .unwrap();
        let Command::Catalog(CatalogArgs {
            command: CatalogCommand::Convert(arguments),
        }) = cli.command
        else {
            panic!("expected catalog convert command");
        };
        let defaults = ConvertOptions::default();
        assert_eq!(arguments.input_epoch, defaults.input_epoch);
        assert_eq!(arguments.epoch, defaults.output_epoch);
        assert_eq!(arguments.min_magnitude, defaults.min_magnitude);
        assert_eq!(arguments.max_magnitude, defaults.max_magnitude);
        assert_eq!(
            arguments.duplicate_radius_arcsec,
            defaults.duplicate_radius_arcsec
        );
        assert_eq!(
            arguments.close_pair_radius_arcsec,
            defaults.close_pair_radius_arcsec
        );
        assert_eq!(arguments.skip_invalid, !defaults.strict);
    }

    #[test]
    fn build_cli_names_defaults_and_removed_temp_dir_are_stable() {
        let defaults = parse_build(&[
            "tetra3",
            "build",
            "--catalog",
            "stars.t3cat",
            "--output",
            "index.t3ix",
        ]);
        assert_eq!(defaults.recipe, BuildRecipe::default());
        assert_eq!(defaults.threads, 0);
        assert!(defaults.manifest.is_none());
        assert!(!defaults.resume);
        assert!(!defaults.json);

        let explicit = parse_build(&[
            "tetra3",
            "build",
            "--catalog",
            "stars.t3cat",
            "--output",
            "index.t3ix",
            "--manifest",
            "provenance.json",
            "--min-fov-deg",
            "4",
            "--max-fov-deg",
            "40",
            "--levels",
            "7",
            "--aspect-ratio",
            "1.5",
            "--lattice-spacing-fraction",
            "0.6",
            "--pattern-rolls-per-field",
            "6",
            "--pattern-stars-per-field",
            "11",
            "--patterns-per-field",
            "63",
            "--isolation-fraction",
            "0.004",
            "--minimum-isolation-arcsec",
            "21",
            "--minimum-pattern-span-fraction",
            "0.16",
            "--minimum-edge-ratio",
            "0.09",
            "--minimum-covariance-ratio",
            "0.03",
            "--target-verification-stars-per-field",
            "129",
            "--pattern-anchor-safety-multiplier",
            "5",
            "--tolerance-q8",
            "3",
            "--spatial-z-bins",
            "181",
            "--spatial-lon-bins",
            "361",
            "--threads",
            "2",
            "--allow-uncovered-fields",
            "--resume",
            "--json",
        ]);
        assert_eq!(explicit.recipe.horizontal_fov_deg.min_fov_deg, 4.0);
        assert_eq!(explicit.recipe.horizontal_fov_deg.max_fov_deg, 40.0);
        assert_eq!(explicit.recipe.level_count, 7);
        assert_eq!(explicit.recipe.max_patterns_per_field, 63);
        assert_eq!(explicit.recipe.default_tolerance_q8, 3);
        assert_eq!(explicit.recipe.spatial_grid.spatial_z_bins, 181);
        assert_eq!(explicit.recipe.spatial_grid.spatial_lon_bins, 361);
        assert!(explicit.recipe.allow_uncovered_fields);
        assert_eq!(explicit.threads, 2);
        assert!(explicit.resume);
        assert!(explicit.json);

        let removed_temp_dir = Cli::try_parse_from([
            "tetra3",
            "build",
            "--catalog",
            "stars.t3cat",
            "--output",
            "index.t3ix",
            "--temp-dir",
            "tmp",
        ]);
        assert!(removed_temp_dir.is_err());
    }

    #[test]
    fn build_manifest_recipe_remains_flat_and_resume_readable() {
        let catalog = CompactCatalog {
            info: CatalogInfo {
                version: 1,
                records: 0,
                epoch: 2026.0,
                min_magnitude: -5.0,
                max_magnitude: 12.0,
                duplicate_radius_arcsec: 0.5,
                close_pair_radius_arcsec: 15.0,
                rows_invalid: 0,
                rows_removed: 0,
                records_sha256: [1; 32],
            },
            stars: Vec::new(),
        };
        let stats = BuildStats {
            catalog_stars: 0,
            pattern_stars: 0,
            patterns: 0,
            levels: Vec::new(),
        };
        let recipe = BuildRecipe::default();
        let manifest = BuildManifest::new(&catalog, [2; 32], [3; 32], recipe.clone(), &stats);
        let value = serde_json::to_value(manifest).unwrap();
        let object = value.as_object().unwrap();
        let actual_keys = object.keys().map(String::as_str).collect::<BTreeSet<_>>();
        let expected_keys = [
            "allow_uncovered_fields",
            "aspect_ratio",
            "catalog_epoch",
            "catalog_records_sha256",
            "catalog_sha256",
            "default_tolerance_q8",
            "format",
            "generator",
            "horizontal_fov_deg",
            "index_format_version",
            "index_sha256",
            "isolation_fraction",
            "lattice_spacing_fraction",
            "level_count",
            "max_patterns_per_field",
            "minimum_covariance_ratio",
            "minimum_edge_ratio",
            "minimum_isolation_arcsec",
            "minimum_pattern_span_fraction",
            "pattern_anchor_safety_multiplier",
            "pattern_rolls_per_field",
            "pattern_stars_per_field",
            "spatial_grid",
            "stats",
            "target_verification_stars_per_field",
            "verification_density_metadata",
        ]
        .into_iter()
        .collect();
        assert_eq!(actual_keys, expected_keys);
        assert_eq!(value["horizontal_fov_deg"], serde_json::json!([3.0, 45.0]));
        assert_eq!(value["spatial_grid"], serde_json::json!([180, 360]));
        assert!(value.get("recipe").is_none());
        assert!(value.get("threads").is_none());
        assert_eq!(
            serde_json::from_value::<BuildRecipe>(value).unwrap(),
            recipe
        );
    }
}

#[cfg(test)]
mod lensfun_cli_tests {
    use super::*;

    #[test]
    fn lensfun_directory_inputs_are_sorted_filtered_and_deduplicated() {
        let directory = tempfile::tempdir().unwrap();
        let a = directory.path().join("a.xml");
        let b = directory.path().join("b.xml");
        std::fs::write(&b, "<lensdatabase version=\"2\"/>").unwrap();
        std::fs::write(&a, "<lensdatabase version=\"2\"/>").unwrap();
        std::fs::write(directory.path().join("README.txt"), "ignored").unwrap();

        let paths = expand_lensfun_xml_paths(&[directory.path().into(), b.clone()]).unwrap();
        assert_eq!(paths, vec![a, b]);
    }

    #[test]
    fn compatible_recipe_requires_an_explicit_true_guard() {
        let json = br#"{
          "compatible_lenses": [{
            "all_compatible_lenses": false,
            "profile_id_prefix": "phone",
            "camera_maker": "Acme",
            "camera_model": "Night Phone",
            "lens_mount": "Phone Mount",
            "calibration_width": 4000,
            "calibration_height": 3000,
            "crop_x": 0,
            "crop_y": 0,
            "crop_width": 4000,
            "crop_height": 3000,
            "encoded_width": 1000,
            "encoded_height": 750
          }]
        }"#;
        let directory = tempfile::tempdir().unwrap();
        let recipe = directory.path().join("recipe.json");
        std::fs::write(&recipe, json).unwrap();
        let error = lensfun_compile(LensfunCompileArgs {
            xml: vec![directory.path().join("unused.xml")],
            profiles: recipe,
            output: directory.path().join("unused.t3lf"),
            report: None,
            json: true,
        })
        .unwrap_err();
        assert!(matches!(error, Error::InvalidArgument(_)));
        assert!(error.to_string().contains("all_compatible_lenses=true"));
    }

    #[test]
    fn lensfun_source_digest_is_path_and_enumeration_independent() {
        let forward = vec![
            ("/first/tree/a.xml".into(), "alpha".into()),
            ("/first/tree/b.xml".into(), "beta".into()),
        ];
        let reverse_relocated = vec![
            ("/other/tree/b.xml".into(), "beta".into()),
            ("/other/tree/a.xml".into(), "alpha".into()),
        ];
        let (forward_digest, forward_documents) = lensfun_source_provenance(&forward);
        let (reverse_digest, reverse_documents) = lensfun_source_provenance(&reverse_relocated);
        assert_eq!(forward_digest, reverse_digest);
        assert_eq!(forward_documents, reverse_documents);
    }
}

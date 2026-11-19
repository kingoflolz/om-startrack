//! Host-side compiler from selected Lensfun XML entries to compact `.t3lf` data.
//!
//! Lensfun does not assign stable IDs to camera or lens entries. Selection is
//! therefore exact and case-sensitive on the canonical (non-localized)
//! maker/model/variant fields. Explicit requests supply their runtime profile
//! ID; bulk requests derive an exact, deterministic ID containing the
//! canonical lens maker and model.

use std::collections::{BTreeMap, BTreeSet, VecDeque};

use roxmltree::{Document, Node, ParsingOptions};
use tetra3_lens::{
    CalibrationGeometry, CameraGeometry, CropRect, Database, DistortionKnot, DistortionModel,
    LensProfile, Orientation, ProfileId, Projection,
};
use thiserror::Error;

const DEFAULT_CALIBRATION_ASPECT_RATIO: f32 = 1.5;
const XML_NODE_LIMIT: u32 = 2_000_000;
const ATTRIBUTE_MATCH_EPSILON: f32 = 1.0e-5;

/// One UTF-8 Lensfun database document and its diagnostic name.
#[derive(Debug, Clone, Copy)]
pub struct LensfunXml<'a> {
    pub source_name: &'a str,
    pub contents: &'a str,
}

/// Exact, case-sensitive identity of a Lensfun camera entry.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct CameraId {
    pub maker: String,
    pub model: String,
    pub variant: Option<String>,
}

/// Exact, case-sensitive identity of a Lensfun lens entry.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct LensId {
    pub maker: String,
    pub model: String,
}

/// Image geometry which is deliberately not present in the Lensfun database.
///
/// `calibration_*` and `crop` describe the full, un-oriented sensor raster and
/// the active rectangle within it. `encoded_*` describe the image delivered to
/// the solver after orientation and resizing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RasterGeometry {
    pub calibration_width: u32,
    pub calibration_height: u32,
    pub crop: CropRect,
    pub encoded_width: u32,
    pub encoded_height: u32,
    pub orientation: Orientation,
}

/// Optional exact selection when one Lensfun entry contains multiple
/// calibration sets.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct CalibrationSelector {
    pub crop_factor: Option<f32>,
    pub aspect_ratio: Option<f32>,
}

/// A single camera/lens profile to include in the output database.
#[derive(Debug, Clone, PartialEq)]
pub struct ProfileRequest {
    pub profile_id: String,
    pub camera: CameraId,
    pub lens: LensId,
    pub raster: RasterGeometry,
    pub calibration: CalibrationSelector,
}

/// A request for every lens compatible with one exact camera entry.
///
/// `lens_mount`, when present, is an additional exact filter. This is useful
/// for compiling native Micro Four Thirds lenses without also including every
/// legacy mount reachable through Lensfun's adapter compatibility graph.
#[derive(Debug, Clone, PartialEq)]
pub struct CompatibleProfileRequest {
    pub profile_id_prefix: String,
    pub camera: CameraId,
    pub raster: RasterGeometry,
    pub calibration: CalibrationSelector,
    pub lens_mount: Option<String>,
}

/// Exact source identity recorded for one generated runtime profile.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IncludedProfile {
    pub profile_id: String,
    pub lens: LensId,
}

/// Why a compatible lens with distortion data was not included.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SkipReason {
    NoMatchingCalibration,
    AmbiguousCalibration { matches: usize },
}

/// One compatible lens deliberately omitted from a bulk compilation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SkippedProfile {
    pub lens: LensId,
    pub reason: SkipReason,
}

/// Deterministic report for bulk selection. A no-distortion lens is reported
/// separately from a lens whose available calibration could not be selected.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CompatibleCompileReport {
    pub compatible_lenses: usize,
    pub included: Vec<IncludedProfile>,
    pub skipped: Vec<SkippedProfile>,
    pub no_distortion: Vec<LensId>,
}

/// A compiled database plus the exact bulk-selection report.
#[derive(Debug, Clone, PartialEq)]
pub struct CompileOutput {
    pub database: Database,
    pub report: CompatibleCompileReport,
}

#[derive(Debug, Error)]
pub enum CompileError {
    #[error("failed to parse Lensfun XML {source_name:?}: {error}")]
    Xml {
        source_name: String,
        #[source]
        error: roxmltree::Error,
    },
    #[error("Lensfun XML {source_name:?} has root <{actual}> instead of <lensdatabase>")]
    InvalidRoot { source_name: String, actual: String },
    #[error("Lensfun XML {source_name:?} has unsupported database version {version:?}")]
    UnsupportedDatabaseVersion {
        source_name: String,
        version: Option<String>,
    },
    #[error("Lensfun {element} in {source_name:?} is missing required {field}")]
    MissingField {
        source_name: String,
        element: &'static str,
        field: &'static str,
    },
    #[error("invalid {attribute}={value:?} on <{element}> in {source_name:?}")]
    InvalidNumber {
        source_name: String,
        element: &'static str,
        attribute: &'static str,
        value: String,
    },
    #[error("camera {maker:?} / {model:?} / variant {variant:?} was not found")]
    CameraNotFound {
        maker: String,
        model: String,
        variant: Option<String>,
    },
    #[error("camera {maker:?} / {model:?} / variant {variant:?} matched {matches} entries")]
    AmbiguousCamera {
        maker: String,
        model: String,
        variant: Option<String>,
        matches: usize,
    },
    #[error("lens {maker:?} / {model:?} was not found")]
    LensNotFound { maker: String, model: String },
    #[error(
        "lens {lens_maker:?} / {lens_model:?} is not compatible with camera mount {camera_mount:?}"
    )]
    IncompatibleMount {
        lens_maker: String,
        lens_model: String,
        camera_mount: String,
    },
    #[error(
        "no distortion calibration matched lens {maker:?} / {model:?} (crop {crop_factor:?}, aspect {aspect_ratio:?})"
    )]
    CalibrationNotFound {
        maker: String,
        model: String,
        crop_factor: Option<f32>,
        aspect_ratio: Option<f32>,
    },
    #[error(
        "lens {maker:?} / {model:?} has {matches} possible distortion calibrations; select crop factor and aspect ratio explicitly"
    )]
    AmbiguousCalibration {
        maker: String,
        model: String,
        matches: usize,
    },
    #[error("Adobe Camera Model distortion is unsupported in runtime profile {profile_id:?}")]
    UnsupportedAcm { profile_id: String },
    #[error("unsupported Lensfun distortion model {model:?} in runtime profile {profile_id:?}")]
    UnsupportedDistortionModel { profile_id: String, model: String },
    #[error("unsupported Lensfun projection {projection:?} in runtime profile {profile_id:?}")]
    UnsupportedProjection {
        profile_id: String,
        projection: String,
    },
    #[error("invalid Lensfun profile {profile_id:?}: {error}")]
    Profile {
        profile_id: String,
        #[source]
        error: tetra3_lens::Error,
    },
    #[error("invalid output Lensfun database: {0}")]
    Database(#[source] tetra3_lens::Error),
}

pub type Result<T> = std::result::Result<T, CompileError>;

/// Compile explicit selections and compatible-lens recipes after parsing the
/// supplied Lensfun documents once.
pub fn compile_database_with_compatible(
    documents: &[LensfunXml<'_>],
    requests: &[ProfileRequest],
    compatible_requests: &[CompatibleProfileRequest],
) -> Result<CompileOutput> {
    let catalog = ParsedCatalog::parse(documents)?;
    let mut profiles = requests
        .iter()
        .map(|request| catalog.compile_profile(request))
        .collect::<Result<Vec<_>>>()?;
    let mut report = CompatibleCompileReport::default();
    for request in compatible_requests {
        catalog.compile_compatible(request, &mut profiles, &mut report)?;
    }
    let database = Database::new(profiles).map_err(CompileError::Database)?;
    Ok(CompileOutput { database, report })
}

/// Compile canonical, checked little-endian `.t3lf` bytes and return the
/// compatible-lens selection report used to produce them.
pub fn compile_t3lf_with_compatible(
    documents: &[LensfunXml<'_>],
    requests: &[ProfileRequest],
    compatible_requests: &[CompatibleProfileRequest],
) -> Result<(Vec<u8>, CompatibleCompileReport)> {
    let output = compile_database_with_compatible(documents, requests, compatible_requests)?;
    let bytes = output.database.to_t3lf().map_err(CompileError::Database)?;
    Ok((bytes, output.report))
}

/// Derive the runtime lookup ID for a canonical Lensfun maker/model pair.
///
/// Byte lengths make the mapping unambiguous without lossy normalization or
/// fuzzy matching. The same function can be used by host metadata ingestion
/// before exact [`Database::get`] lookup.
#[must_use]
pub fn compatible_profile_id(prefix: &str, lens: &LensId) -> String {
    format!(
        "{prefix}|lens={}:{}{}:{}",
        lens.maker.len(),
        lens.maker,
        lens.model.len(),
        lens.model
    )
}

#[derive(Debug, Default)]
struct ParsedCatalog {
    cameras: Vec<ParsedCamera>,
    lenses: Vec<ParsedLens>,
    mount_compatibility: BTreeMap<String, BTreeSet<String>>,
}

#[derive(Debug)]
struct ParsedCamera {
    id: CameraId,
    mount: String,
    crop_factor: f32,
}

#[derive(Debug)]
struct ParsedLens {
    id: LensId,
    mounts: Vec<String>,
    projection: String,
    center_x: f32,
    center_y: f32,
    calibrations: Vec<ParsedCalibration>,
}

#[derive(Debug)]
struct ParsedCalibration {
    crop_factor: Option<f32>,
    aspect_ratio: f32,
    distortions: Vec<ParsedDistortion>,
}

#[derive(Debug)]
struct ParsedDistortion {
    model: String,
    focal_mm: f32,
    real_focal_mm: Option<f32>,
    k1: Option<f32>,
    k2: Option<f32>,
    a: Option<f32>,
    b: Option<f32>,
    c: Option<f32>,
}

impl ParsedCatalog {
    fn parse(documents: &[LensfunXml<'_>]) -> Result<Self> {
        let mut parsed = Self::default();
        for input in documents {
            parsed.parse_document(*input)?;
        }
        Ok(parsed)
    }

    fn parse_document(&mut self, input: LensfunXml<'_>) -> Result<()> {
        let options = ParsingOptions {
            allow_dtd: true,
            nodes_limit: XML_NODE_LIMIT,
            ..ParsingOptions::default()
        };
        let document = Document::parse_with_options(input.contents, options).map_err(|error| {
            CompileError::Xml {
                source_name: input.source_name.to_owned(),
                error,
            }
        })?;
        let root = document.root_element();
        if !root.has_tag_name("lensdatabase") {
            return Err(CompileError::InvalidRoot {
                source_name: input.source_name.to_owned(),
                actual: root.tag_name().name().to_owned(),
            });
        }
        match root.attribute("version") {
            Some("2") => {}
            version => {
                return Err(CompileError::UnsupportedDatabaseVersion {
                    source_name: input.source_name.to_owned(),
                    version: version.map(str::to_owned),
                });
            }
        }

        for child in element_children(root) {
            if child.has_tag_name("mount") {
                self.parse_mount(child);
            } else if child.has_tag_name("camera") {
                self.cameras.push(parse_camera(child, input.source_name)?);
            } else if child.has_tag_name("lens") {
                self.lenses.push(parse_lens(child, input.source_name)?);
            }
        }
        Ok(())
    }

    fn parse_mount(&mut self, node: Node<'_, '_>) {
        let Some(name) = canonical_child_text(node, "name") else {
            return;
        };
        let compatibility = self.mount_compatibility.entry(name).or_default();
        for child in element_children(node).filter(|child| child.has_tag_name("compat")) {
            if let Some(value) = trimmed_text(child) {
                compatibility.insert(value.to_owned());
            }
        }
    }

    fn compile_profile(&self, request: &ProfileRequest) -> Result<LensProfile> {
        let camera = self.select_camera(&request.camera)?;
        let (lens, calibration) = self.select_lens_calibration(request, camera)?;
        self.compile_selected_profile(request, camera, lens, calibration)
    }

    fn compile_selected_profile(
        &self,
        request: &ProfileRequest,
        camera: &ParsedCamera,
        lens: &ParsedLens,
        calibration: &ParsedCalibration,
    ) -> Result<LensProfile> {
        let camera_geometry = CameraGeometry::new(
            request.raster.calibration_width,
            request.raster.calibration_height,
            request.raster.crop,
            request.raster.encoded_width,
            request.raster.encoded_height,
            request.raster.orientation,
            camera.crop_factor,
        )
        .map_err(|error| profile_error(request, error))?;
        let calibration_geometry = CalibrationGeometry::new(
            calibration
                .crop_factor
                .ok_or_else(|| CompileError::CalibrationNotFound {
                    maker: request.lens.maker.clone(),
                    model: request.lens.model.clone(),
                    crop_factor: request.calibration.crop_factor,
                    aspect_ratio: request.calibration.aspect_ratio,
                })?,
            calibration.aspect_ratio,
            lens.center_x,
            lens.center_y,
        )
        .map_err(|error| profile_error(request, error))?;
        let projection = parse_projection(&lens.projection, request)?;
        let mut knots = calibration
            .distortions
            .iter()
            .map(|distortion| compile_distortion(distortion, request))
            .collect::<Result<Vec<_>>>()?;
        knots.sort_unstable_by(|left, right| left.focal_mm().total_cmp(&right.focal_mm()));

        LensProfile::new(
            ProfileId::new(request.profile_id.clone())
                .map_err(|error| profile_error(request, error))?,
            camera_geometry,
            calibration_geometry,
            projection,
            knots,
        )
        .map_err(|error| profile_error(request, error))
    }

    fn compile_compatible(
        &self,
        request: &CompatibleProfileRequest,
        profiles: &mut Vec<LensProfile>,
        report: &mut CompatibleCompileReport,
    ) -> Result<()> {
        let camera = self.select_camera(&request.camera)?;
        let mut lenses_by_id = BTreeMap::<LensId, Vec<&ParsedLens>>::new();
        for lens in &self.lenses {
            let compatible = lens
                .mounts
                .iter()
                .any(|mount| self.mount_is_compatible(&camera.mount, mount));
            let mount_selected = request
                .lens_mount
                .as_ref()
                .is_none_or(|required| lens.mounts.iter().any(|mount| mount == required));
            if compatible && mount_selected {
                lenses_by_id.entry(lens.id.clone()).or_default().push(lens);
            }
        }

        report.compatible_lenses = report
            .compatible_lenses
            .checked_add(lenses_by_id.len())
            .expect("number of parsed Lensfun entries fits usize");
        for (lens_id, compatible_lenses) in lenses_by_id {
            let profile_id = compatible_profile_id(&request.profile_id_prefix, &lens_id);
            let profile_request = ProfileRequest {
                profile_id: profile_id.clone(),
                camera: request.camera.clone(),
                lens: lens_id.clone(),
                raster: request.raster,
                calibration: request.calibration,
            };

            // Preflight every compatible distortion-bearing record, including
            // calibrations that a selector would later reject. Bulk output
            // must never appear complete after silently hiding an ACM or a
            // future model this runtime cannot interpret.
            for lens in &compatible_lenses {
                if lens
                    .calibrations
                    .iter()
                    .any(|calibration| !calibration.distortions.is_empty())
                {
                    parse_projection(&lens.projection, &profile_request)?;
                }
                for calibration in &lens.calibrations {
                    for distortion in &calibration.distortions {
                        parse_distortion_model(distortion, &profile_request)?;
                    }
                }
            }

            if !compatible_lenses.iter().any(|lens| {
                lens.calibrations
                    .iter()
                    .any(|calibration| !calibration.distortions.is_empty())
            }) {
                report.no_distortion.push(lens_id);
                continue;
            }

            match self.select_calibration(&profile_request, camera, &compatible_lenses) {
                Ok((lens, calibration)) => {
                    // Unsupported projections, ACM, unknown models, invalid
                    // coefficients, and overlong IDs fail the whole build.
                    // Bulk mode never silently substitutes a different model.
                    profiles.push(self.compile_selected_profile(
                        &profile_request,
                        camera,
                        lens,
                        calibration,
                    )?);
                    report.included.push(IncludedProfile {
                        profile_id,
                        lens: lens_id,
                    });
                }
                Err(CompileError::CalibrationNotFound { .. }) => {
                    report.skipped.push(SkippedProfile {
                        lens: lens_id,
                        reason: SkipReason::NoMatchingCalibration,
                    });
                }
                Err(CompileError::AmbiguousCalibration { matches, .. }) => {
                    report.skipped.push(SkippedProfile {
                        lens: lens_id,
                        reason: SkipReason::AmbiguousCalibration { matches },
                    });
                }
                Err(error) => return Err(error),
            }
        }
        Ok(())
    }

    fn select_camera(&self, requested: &CameraId) -> Result<&ParsedCamera> {
        let matches = self
            .cameras
            .iter()
            .filter(|camera| &camera.id == requested)
            .collect::<Vec<_>>();
        match matches.as_slice() {
            [] => Err(CompileError::CameraNotFound {
                maker: requested.maker.clone(),
                model: requested.model.clone(),
                variant: requested.variant.clone(),
            }),
            [camera] => Ok(*camera),
            _ => Err(CompileError::AmbiguousCamera {
                maker: requested.maker.clone(),
                model: requested.model.clone(),
                variant: requested.variant.clone(),
                matches: matches.len(),
            }),
        }
    }

    fn select_lens_calibration<'a>(
        &'a self,
        request: &ProfileRequest,
        camera: &ParsedCamera,
    ) -> Result<(&'a ParsedLens, &'a ParsedCalibration)> {
        let exact_lenses = self
            .lenses
            .iter()
            .filter(|lens| lens.id == request.lens)
            .collect::<Vec<_>>();
        if exact_lenses.is_empty() {
            return Err(CompileError::LensNotFound {
                maker: request.lens.maker.clone(),
                model: request.lens.model.clone(),
            });
        }
        let compatible_lenses = exact_lenses
            .into_iter()
            .filter(|lens| {
                lens.mounts
                    .iter()
                    .any(|mount| self.mount_is_compatible(&camera.mount, mount))
            })
            .collect::<Vec<_>>();
        if compatible_lenses.is_empty() {
            return Err(CompileError::IncompatibleMount {
                lens_maker: request.lens.maker.clone(),
                lens_model: request.lens.model.clone(),
                camera_mount: camera.mount.clone(),
            });
        }

        self.select_calibration(request, camera, &compatible_lenses)
    }

    fn select_calibration<'a>(
        &'a self,
        request: &ProfileRequest,
        camera: &ParsedCamera,
        compatible_lenses: &[&'a ParsedLens],
    ) -> Result<(&'a ParsedLens, &'a ParsedCalibration)> {
        let mut candidates = compatible_lenses
            .iter()
            .flat_map(|lens| {
                lens.calibrations
                    .iter()
                    .filter(|calibration| !calibration.distortions.is_empty())
                    .map(move |calibration| (*lens, calibration))
            })
            .collect::<Vec<_>>();
        if request.calibration.crop_factor.is_some() || request.calibration.aspect_ratio.is_some() {
            candidates.retain(|(_, calibration)| {
                request.calibration.crop_factor.is_none_or(|expected| {
                    calibration
                        .crop_factor
                        .is_some_and(|actual| attributes_match(actual, expected))
                }) && request
                    .calibration
                    .aspect_ratio
                    .is_none_or(|expected| attributes_match(calibration.aspect_ratio, expected))
            });
        } else if candidates.len() > 1 {
            let raster_aspect = normalized_aspect(
                request.raster.calibration_width,
                request.raster.calibration_height,
            );
            let exact = candidates
                .iter()
                .copied()
                .filter(|(_, calibration)| {
                    calibration
                        .crop_factor
                        .is_some_and(|crop| attributes_match(crop, camera.crop_factor))
                        && attributes_match(calibration.aspect_ratio, raster_aspect)
                })
                .collect::<Vec<_>>();
            if exact.len() == 1 {
                candidates = exact;
            }
        }

        match candidates.as_slice() {
            [] => Err(CompileError::CalibrationNotFound {
                maker: request.lens.maker.clone(),
                model: request.lens.model.clone(),
                crop_factor: request.calibration.crop_factor,
                aspect_ratio: request.calibration.aspect_ratio,
            }),
            [candidate] => Ok(*candidate),
            _ => Err(CompileError::AmbiguousCalibration {
                maker: request.lens.maker.clone(),
                model: request.lens.model.clone(),
                matches: candidates.len(),
            }),
        }
    }

    fn mount_is_compatible(&self, camera_mount: &str, lens_mount: &str) -> bool {
        if camera_mount == lens_mount {
            return true;
        }
        let mut visited = BTreeSet::new();
        let mut pending = VecDeque::from([camera_mount.to_owned()]);
        while let Some(mount) = pending.pop_front() {
            if !visited.insert(mount.clone()) {
                continue;
            }
            if let Some(compatible) = self.mount_compatibility.get(&mount) {
                if compatible.contains(lens_mount) {
                    return true;
                }
                pending.extend(compatible.iter().cloned());
            }
        }
        false
    }
}

fn parse_camera(node: Node<'_, '_>, source_name: &str) -> Result<ParsedCamera> {
    Ok(ParsedCamera {
        id: CameraId {
            maker: required_canonical_text(node, "camera", "maker", source_name)?,
            model: required_canonical_text(node, "camera", "model", source_name)?,
            variant: canonical_child_text(node, "variant"),
        },
        mount: required_canonical_text(node, "camera", "mount", source_name)?,
        crop_factor: required_child_number(node, "camera", "cropfactor", source_name)?,
    })
}

fn parse_lens(node: Node<'_, '_>, source_name: &str) -> Result<ParsedLens> {
    let crop_factor = optional_child_number(node, "lens", "cropfactor", source_name)?;
    let aspect_ratio = optional_aspect_child(node, "lens", "aspect-ratio", source_name)?
        .unwrap_or(DEFAULT_CALIBRATION_ASPECT_RATIO);
    let center = direct_child(node, "center");
    let center_x = optional_attribute_number(center, "center", "x", source_name)?.unwrap_or(0.0);
    let center_y = optional_attribute_number(center, "center", "y", source_name)?.unwrap_or(0.0);
    let projection = canonical_child_text(node, "type").unwrap_or_else(|| "rectilinear".into());
    let mounts = element_children(node)
        .filter(|child| child.has_tag_name("mount"))
        .filter_map(trimmed_text)
        .map(str::to_owned)
        .collect::<Vec<_>>();
    if mounts.is_empty() {
        return Err(CompileError::MissingField {
            source_name: source_name.to_owned(),
            element: "lens",
            field: "mount",
        });
    }

    let calibrations = element_children(node)
        .filter(|child| child.has_tag_name("calibration"))
        .map(|calibration| parse_calibration(calibration, crop_factor, aspect_ratio, source_name))
        .collect::<Result<Vec<_>>>()?;
    Ok(ParsedLens {
        id: LensId {
            maker: required_canonical_text(node, "lens", "maker", source_name)?,
            model: required_canonical_text(node, "lens", "model", source_name)?,
        },
        mounts,
        projection,
        center_x,
        center_y,
        calibrations,
    })
}

fn parse_calibration(
    node: Node<'_, '_>,
    inherited_crop_factor: Option<f32>,
    inherited_aspect_ratio: f32,
    source_name: &str,
) -> Result<ParsedCalibration> {
    let crop_factor =
        optional_attribute_number(Some(node), "calibration", "cropfactor", source_name)?
            .or(inherited_crop_factor);
    let aspect_ratio =
        optional_aspect_attribute(Some(node), "calibration", "aspect-ratio", source_name)?
            .unwrap_or(inherited_aspect_ratio);
    let distortions = element_children(node)
        .filter(|child| child.has_tag_name("distortion"))
        .map(|distortion| parse_distortion(distortion, source_name))
        .collect::<Result<Vec<_>>>()?;
    Ok(ParsedCalibration {
        crop_factor,
        aspect_ratio,
        distortions,
    })
}

fn parse_distortion(node: Node<'_, '_>, source_name: &str) -> Result<ParsedDistortion> {
    Ok(ParsedDistortion {
        model: required_attribute(node, "distortion", "model", source_name)?.to_owned(),
        focal_mm: required_attribute_number(node, "distortion", "focal", source_name)?,
        real_focal_mm: optional_attribute_number(
            Some(node),
            "distortion",
            "real-focal",
            source_name,
        )?,
        k1: optional_attribute_number(Some(node), "distortion", "k1", source_name)?,
        k2: optional_attribute_number(Some(node), "distortion", "k2", source_name)?,
        a: optional_attribute_number(Some(node), "distortion", "a", source_name)?,
        b: optional_attribute_number(Some(node), "distortion", "b", source_name)?,
        c: optional_attribute_number(Some(node), "distortion", "c", source_name)?,
    })
}

fn compile_distortion(raw: &ParsedDistortion, request: &ProfileRequest) -> Result<DistortionKnot> {
    let model = parse_distortion_model(raw, request)?;
    let real_focal_mm = raw
        .real_focal_mm
        .unwrap_or_else(|| inferred_real_focal(raw.focal_mm, model));
    DistortionKnot::new(raw.focal_mm, real_focal_mm, model)
        .map_err(|error| profile_error(request, error))
}

fn parse_distortion_model(
    raw: &ParsedDistortion,
    request: &ProfileRequest,
) -> Result<DistortionModel> {
    let model = match raw.model.as_str() {
        "none" => DistortionModel::None,
        "poly3" => DistortionModel::Poly3 {
            k1: raw.k1.unwrap_or(0.0),
        },
        "poly5" => DistortionModel::Poly5 {
            k1: raw.k1.unwrap_or(0.0),
            k2: raw.k2.unwrap_or(0.0),
        },
        "ptlens" => DistortionModel::Ptlens {
            a: raw.a.unwrap_or(0.0),
            b: raw.b.unwrap_or(0.0),
            c: raw.c.unwrap_or(0.0),
        },
        "acm" => {
            return Err(CompileError::UnsupportedAcm {
                profile_id: request.profile_id.clone(),
            });
        }
        model => {
            return Err(CompileError::UnsupportedDistortionModel {
                profile_id: request.profile_id.clone(),
                model: model.to_owned(),
            });
        }
    };
    Ok(model)
}

fn inferred_real_focal(focal_mm: f32, model: DistortionModel) -> f32 {
    match model {
        DistortionModel::None | DistortionModel::Poly5 { .. } => focal_mm,
        DistortionModel::Poly3 { k1 } => focal_mm * (1.0 - k1),
        DistortionModel::Ptlens { a, b, c } => focal_mm * (1.0 - a - b - c),
    }
}

fn parse_projection(value: &str, request: &ProfileRequest) -> Result<Projection> {
    match value {
        "rectilinear" => Ok(Projection::Rectilinear),
        "fisheye" => Ok(Projection::FisheyeEquidistant),
        "panoramic" => Ok(Projection::Panoramic),
        "equirectangular" => Ok(Projection::Equirectangular),
        "orthographic" => Ok(Projection::FisheyeOrthographic),
        "stereographic" => Ok(Projection::FisheyeStereographic),
        "equisolid" => Ok(Projection::FisheyeEquisolid),
        "fisheye_thoby" => Ok(Projection::FisheyeThoby),
        projection => Err(CompileError::UnsupportedProjection {
            profile_id: request.profile_id.clone(),
            projection: projection.to_owned(),
        }),
    }
}

fn profile_error(request: &ProfileRequest, error: tetra3_lens::Error) -> CompileError {
    CompileError::Profile {
        profile_id: request.profile_id.clone(),
        error,
    }
}

fn element_children<'a, 'input>(node: Node<'a, 'input>) -> impl Iterator<Item = Node<'a, 'input>> {
    node.children().filter(Node::is_element)
}

fn direct_child<'a, 'input>(node: Node<'a, 'input>, name: &str) -> Option<Node<'a, 'input>> {
    element_children(node).find(|child| child.has_tag_name(name))
}

fn trimmed_text<'a, 'input>(node: Node<'a, 'input>) -> Option<&'a str> {
    node.text().map(str::trim).filter(|text| !text.is_empty())
}

fn canonical_child_text(node: Node<'_, '_>, name: &str) -> Option<String> {
    element_children(node)
        .find(|child| child.has_tag_name(name) && child.attribute("lang").is_none())
        .and_then(trimmed_text)
        .map(str::to_owned)
}

fn required_canonical_text(
    node: Node<'_, '_>,
    element: &'static str,
    field: &'static str,
    source_name: &str,
) -> Result<String> {
    canonical_child_text(node, field).ok_or_else(|| CompileError::MissingField {
        source_name: source_name.to_owned(),
        element,
        field,
    })
}

fn required_attribute<'a>(
    node: Node<'a, '_>,
    element: &'static str,
    attribute: &'static str,
    source_name: &str,
) -> Result<&'a str> {
    node.attribute(attribute)
        .ok_or_else(|| CompileError::MissingField {
            source_name: source_name.to_owned(),
            element,
            field: attribute,
        })
}

fn required_child_number(
    node: Node<'_, '_>,
    element: &'static str,
    field: &'static str,
    source_name: &str,
) -> Result<f32> {
    let value = required_canonical_text(node, element, field, source_name)?;
    parse_number(&value, element, field, source_name)
}

fn optional_child_number(
    node: Node<'_, '_>,
    element: &'static str,
    field: &'static str,
    source_name: &str,
) -> Result<Option<f32>> {
    canonical_child_text(node, field)
        .map(|value| parse_number(&value, element, field, source_name))
        .transpose()
}

fn optional_aspect_child(
    node: Node<'_, '_>,
    element: &'static str,
    field: &'static str,
    source_name: &str,
) -> Result<Option<f32>> {
    canonical_child_text(node, field)
        .map(|value| parse_aspect(&value, element, field, source_name))
        .transpose()
}

fn required_attribute_number(
    node: Node<'_, '_>,
    element: &'static str,
    attribute: &'static str,
    source_name: &str,
) -> Result<f32> {
    parse_number(
        required_attribute(node, element, attribute, source_name)?,
        element,
        attribute,
        source_name,
    )
}

fn optional_attribute_number(
    node: Option<Node<'_, '_>>,
    element: &'static str,
    attribute: &'static str,
    source_name: &str,
) -> Result<Option<f32>> {
    node.and_then(|node| node.attribute(attribute))
        .map(|value| parse_number(value, element, attribute, source_name))
        .transpose()
}

fn optional_aspect_attribute(
    node: Option<Node<'_, '_>>,
    element: &'static str,
    attribute: &'static str,
    source_name: &str,
) -> Result<Option<f32>> {
    node.and_then(|node| node.attribute(attribute))
        .map(|value| parse_aspect(value, element, attribute, source_name))
        .transpose()
}

fn parse_number(
    value: &str,
    element: &'static str,
    attribute: &'static str,
    source_name: &str,
) -> Result<f32> {
    value
        .parse::<f32>()
        .ok()
        .filter(|number| number.is_finite())
        .ok_or_else(|| CompileError::InvalidNumber {
            source_name: source_name.to_owned(),
            element,
            attribute,
            value: value.to_owned(),
        })
}

fn parse_aspect(
    value: &str,
    element: &'static str,
    attribute: &'static str,
    source_name: &str,
) -> Result<f32> {
    if let Some((numerator, denominator)) = value.split_once(':') {
        let numerator = parse_number(numerator.trim(), element, attribute, source_name)?;
        let denominator = parse_number(denominator.trim(), element, attribute, source_name)?;
        if denominator != 0.0 {
            return Ok(numerator / denominator);
        }
    } else {
        return parse_number(value, element, attribute, source_name);
    }
    Err(CompileError::InvalidNumber {
        source_name: source_name.to_owned(),
        element,
        attribute,
        value: value.to_owned(),
    })
}

fn attributes_match(left: f32, right: f32) -> bool {
    (left - right).abs() <= ATTRIBUTE_MATCH_EPSILON * left.abs().max(right.abs()).max(1.0)
}

fn normalized_aspect(width: u32, height: u32) -> f32 {
    let long = width.max(height) as f32;
    let short = width.min(height) as f32;
    if short == 0.0 {
        f32::INFINITY
    } else {
        long / short
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn compile_database(
        documents: &[LensfunXml<'_>],
        requests: &[ProfileRequest],
    ) -> Result<Database> {
        Ok(compile_database_with_compatible(documents, requests, &[])?.database)
    }

    fn compile_t3lf(documents: &[LensfunXml<'_>], requests: &[ProfileRequest]) -> Result<Vec<u8>> {
        Ok(compile_t3lf_with_compatible(documents, requests, &[])?.0)
    }

    const XML: &str = r#"
        <!DOCTYPE lensdatabase SYSTEM "lensfun-database.dtd">
        <lensdatabase version="2">
          <mount>
            <name>Phone Mount</name>
            <compat>Adapted Mount</compat>
          </mount>
          <camera>
            <maker>Acme &amp; Co</maker><maker lang="de">Akme</maker>
            <model>Night Phone</model><variant>wide</variant>
            <mount>Phone Mount</mount><cropfactor>2</cropfactor>
          </camera>
          <lens>
            <maker>Glass</maker><model>Poly Three</model>
            <mount>Phone Mount</mount><center x="0.01" y="-0.02"/>
            <cropfactor>2</cropfactor><aspect-ratio>4:3</aspect-ratio>
            <calibration cropfactor="2.1" aspect-ratio="16:9">
              <distortion model="poly3" focal="14" k1="-0.1"/>
              <distortion model="poly3" focal="20" k1="-0.05" real-focal="19.5"/>
            </calibration>
          </lens>
          <lens>
            <maker>Glass</maker><model>Poly Five</model><mount>Adapted Mount</mount>
            <type>stereographic</type><cropfactor>2</cropfactor>
            <calibration>
              <distortion model="poly5" focal="8" k1="0.1" k2="-0.02"/>
            </calibration>
          </lens>
          <lens>
            <maker>Glass</maker><model>PT Lens</model><mount>Phone Mount</mount>
            <cropfactor>2</cropfactor><aspect-ratio>4:3</aspect-ratio>
            <calibration>
              <distortion model="ptlens" focal="12" a="0.01" b="-0.02" c="0.03"/>
            </calibration>
          </lens>
          <lens>
            <maker>Unused</maker><model>Unsupported</model><mount>Phone Mount</mount>
            <cropfactor>2</cropfactor><calibration>
              <distortion model="acm" focal="4" k1="1"/>
            </calibration>
          </lens>
        </lensdatabase>
    "#;

    fn raster() -> RasterGeometry {
        RasterGeometry {
            calibration_width: 4000,
            calibration_height: 3000,
            crop: CropRect::new(0, 0, 4000, 3000),
            encoded_width: 2000,
            encoded_height: 1500,
            orientation: Orientation::Normal,
        }
    }

    fn request(lens_model: &str, profile_id: &str) -> ProfileRequest {
        ProfileRequest {
            profile_id: profile_id.to_owned(),
            camera: CameraId {
                maker: "Acme & Co".into(),
                model: "Night Phone".into(),
                variant: Some("wide".into()),
            },
            lens: LensId {
                maker: "Glass".into(),
                model: lens_model.into(),
            },
            raster: raster(),
            calibration: CalibrationSelector::default(),
        }
    }

    fn documents() -> [LensfunXml<'static>; 1] {
        [LensfunXml {
            source_name: "fixture.xml",
            contents: XML,
        }]
    }

    #[test]
    fn compiles_all_classic_models_and_preserves_geometry() {
        let mut poly3 = request("Poly Three", "profiles.poly3");
        poly3.calibration = CalibrationSelector {
            crop_factor: Some(2.1),
            aspect_ratio: Some(16.0 / 9.0),
        };
        let requests = [
            poly3,
            request("Poly Five", "profiles.poly5"),
            request("PT Lens", "profiles.ptlens"),
        ];
        let database = compile_database(&documents(), &requests).unwrap();
        let profile = database.require("profiles.poly3").unwrap();
        assert_eq!(profile.camera().crop_factor(), 2.0);
        assert_eq!(profile.calibration().crop_factor(), 2.1);
        assert!((profile.calibration().aspect_ratio() - 16.0 / 9.0).abs() < 1e-6);
        assert_eq!(profile.calibration().center_x(), 0.01);
        assert_eq!(profile.calibration().center_y(), -0.02);
        assert_eq!(profile.projection(), Projection::Rectilinear);
        assert_eq!(
            profile.knots()[0].model(),
            DistortionModel::Poly3 { k1: -0.1 }
        );
        assert!((profile.knots()[0].real_focal_mm() - 15.4).abs() < 1e-5);
        assert_eq!(profile.knots()[1].real_focal_mm(), 19.5);

        let poly5 = database.require("profiles.poly5").unwrap();
        assert_eq!(poly5.projection(), Projection::FisheyeStereographic);
        assert_eq!(poly5.knots()[0].real_focal_mm(), 8.0);
        assert_eq!(
            poly5.knots()[0].model(),
            DistortionModel::Poly5 { k1: 0.1, k2: -0.02 }
        );

        let ptlens = database.require("profiles.ptlens").unwrap();
        assert!((ptlens.knots()[0].real_focal_mm() - 11.76).abs() < 1e-5);
        assert_eq!(
            ptlens.knots()[0].model(),
            DistortionModel::Ptlens {
                a: 0.01,
                b: -0.02,
                c: 0.03
            }
        );
    }

    #[test]
    fn output_round_trips_through_runtime_parser() {
        let bytes = compile_t3lf(&documents(), &[request("PT Lens", "phone.main")]).unwrap();
        let parsed = Database::from_t3lf(&bytes).unwrap();
        assert!(parsed.get("phone.main").is_some());
        assert_eq!(parsed.to_t3lf().unwrap(), bytes);
    }

    #[test]
    fn selectors_are_exact_and_case_sensitive() {
        let mut wrong_case = request("PT Lens", "phone.main");
        wrong_case.camera.maker = "acme & co".into();
        assert!(matches!(
            compile_database(&documents(), &[wrong_case]),
            Err(CompileError::CameraNotFound { .. })
        ));
    }

    #[test]
    fn compatible_mount_edges_are_followed() {
        let database =
            compile_database(&documents(), &[request("Poly Five", "phone.adapted")]).unwrap();
        assert!(database.get("phone.adapted").is_some());
    }

    #[test]
    fn selected_acm_and_unknown_models_fail_closed() {
        let mut acm = request("Unsupported", "phone.acm");
        acm.lens.maker = "Unused".into();
        assert!(matches!(
            compile_database(&documents(), &[acm]),
            Err(CompileError::UnsupportedAcm { .. })
        ));

        let xml = XML.replace("model=\"acm\"", "model=\"future-model\"");
        let docs = [LensfunXml {
            source_name: "unknown.xml",
            contents: &xml,
        }];
        let mut unknown = request("Unsupported", "phone.unknown");
        unknown.lens.maker = "Unused".into();
        assert!(matches!(
            compile_database(&docs, &[unknown]),
            Err(CompileError::UnsupportedDistortionModel { .. })
        ));
    }

    #[test]
    fn explicit_calibration_attributes_disambiguate_sets() {
        let xml = r#"
          <lensdatabase version="2">
            <camera><maker>C</maker><model>M</model><mount>X</mount><cropfactor>2</cropfactor></camera>
            <lens><maker>L</maker><model>Z</model><mount>X</mount><cropfactor>2</cropfactor>
              <calibration cropfactor="1" aspect-ratio="3:2"><distortion model="poly3" focal="10" k1="0"/></calibration>
              <calibration cropfactor="2" aspect-ratio="16:9"><distortion model="poly3" focal="10" k1="0.1"/></calibration>
            </lens>
          </lensdatabase>
        "#;
        let docs = [LensfunXml {
            source_name: "multi.xml",
            contents: xml,
        }];
        let mut requested = ProfileRequest {
            profile_id: "selected".into(),
            camera: CameraId {
                maker: "C".into(),
                model: "M".into(),
                variant: None,
            },
            lens: LensId {
                maker: "L".into(),
                model: "Z".into(),
            },
            raster: raster(),
            calibration: CalibrationSelector::default(),
        };
        assert!(matches!(
            compile_database(&docs, &[requested.clone()]),
            Err(CompileError::AmbiguousCalibration { .. })
        ));
        requested.calibration = CalibrationSelector {
            crop_factor: Some(2.0),
            aspect_ratio: Some(16.0 / 9.0),
        };
        let database = compile_database(&docs, &[requested]).unwrap();
        assert_eq!(
            database.require("selected").unwrap().knots()[0].model(),
            DistortionModel::Poly3 { k1: 0.1 }
        );
    }

    #[test]
    fn rejects_version_one_documents() {
        let docs = [LensfunXml {
            source_name: "legacy.xml",
            contents: r#"<lensdatabase version="1"/>"#,
        }];
        assert!(matches!(
            compile_database(&docs, &[]),
            Err(CompileError::UnsupportedDatabaseVersion {
                version: Some(version),
                ..
            }) if version == "1"
        ));
    }

    fn compatible_request(lens_mount: Option<&str>) -> CompatibleProfileRequest {
        CompatibleProfileRequest {
            profile_id_prefix: "phone.night.raw-quarter".into(),
            camera: CameraId {
                maker: "Acme".into(),
                model: "Night Phone".into(),
                variant: None,
            },
            raster: raster(),
            calibration: CalibrationSelector::default(),
            lens_mount: lens_mount.map(str::to_owned),
        }
    }

    const BULK_CAMERA_XML: &str = r#"
      <lensdatabase version="2">
        <mount><name>Phone Mount</name><compat>Adapted Mount</compat></mount>
        <camera>
          <maker>Acme</maker><model>Night Phone</model>
          <mount>Phone Mount</mount><cropfactor>2</cropfactor>
        </camera>
        <lens>
          <maker>Glass</maker><model>No Distortion</model><mount>Phone Mount</mount>
          <cropfactor>2</cropfactor><calibration><tca model="poly3" focal="20"/></calibration>
        </lens>
      </lensdatabase>
    "#;

    const BULK_LENSES_XML: &str = r#"
      <lensdatabase version="2">
        <lens>
          <maker>Glass</maker><model>Direct | Prime</model><mount>Phone Mount</mount>
          <cropfactor>2</cropfactor><aspect-ratio>4:3</aspect-ratio><calibration>
            <distortion model="ptlens" focal="12" a="0.01" b="-0.02" c="0.03"/>
          </calibration>
        </lens>
        <lens>
          <maker>Adapted</maker><model>Classic</model><mount>Adapted Mount</mount>
          <cropfactor>1</cropfactor><calibration>
            <distortion model="poly3" focal="50" k1="-0.02"/>
          </calibration>
        </lens>
        <lens>
          <maker>Duplicate</maker><model>Ambiguous</model><mount>Phone Mount</mount>
          <cropfactor>1</cropfactor><calibration>
            <distortion model="poly3" focal="35" k1="-0.01"/>
          </calibration>
        </lens>
        <lens>
          <maker>Duplicate</maker><model>Ambiguous</model><mount>Phone Mount</mount>
          <cropfactor>1.5</cropfactor><calibration>
            <distortion model="poly3" focal="35" k1="-0.02"/>
          </calibration>
        </lens>
      </lensdatabase>
    "#;

    #[test]
    fn bulk_selection_is_cross_file_exact_and_byte_deterministic() {
        let forward = [
            LensfunXml {
                source_name: "camera.xml",
                contents: BULK_CAMERA_XML,
            },
            LensfunXml {
                source_name: "lenses.xml",
                contents: BULK_LENSES_XML,
            },
        ];
        let reverse = [forward[1], forward[0]];
        let request = compatible_request(None);
        let (forward_bytes, report) =
            compile_t3lf_with_compatible(&forward, &[], core::slice::from_ref(&request)).unwrap();
        let (reverse_bytes, reverse_report) =
            compile_t3lf_with_compatible(&reverse, &[], &[request]).unwrap();

        assert_eq!(forward_bytes, reverse_bytes);
        assert_eq!(report, reverse_report);
        assert_eq!(report.compatible_lenses, 4);
        assert_eq!(report.included.len(), 2);
        assert_eq!(report.skipped.len(), 1);
        assert_eq!(report.no_distortion.len(), 1);
        assert_eq!(
            report.skipped[0].reason,
            SkipReason::AmbiguousCalibration { matches: 2 }
        );

        let direct = LensId {
            maker: "Glass".into(),
            model: "Direct | Prime".into(),
        };
        let id = compatible_profile_id("phone.night.raw-quarter", &direct);
        assert_eq!(id, "phone.night.raw-quarter|lens=5:Glass14:Direct | Prime");
        let database = Database::from_t3lf(&forward_bytes).unwrap();
        assert!(database.get(&id).is_some());
        assert!(
            database
                .get(&compatible_profile_id(
                    "phone.night.raw-quarter",
                    &LensId {
                        maker: "glass".into(),
                        model: "Direct | Prime".into(),
                    }
                ))
                .is_none()
        );
    }

    #[test]
    fn exact_native_mount_filter_excludes_compatible_adapters() {
        let docs = [
            LensfunXml {
                source_name: "camera.xml",
                contents: BULK_CAMERA_XML,
            },
            LensfunXml {
                source_name: "lenses.xml",
                contents: BULK_LENSES_XML,
            },
        ];
        let output = compile_database_with_compatible(
            &docs,
            &[],
            &[compatible_request(Some("Phone Mount"))],
        )
        .unwrap();
        assert_eq!(output.report.compatible_lenses, 3);
        assert_eq!(output.report.included.len(), 1);
        assert!(
            output
                .report
                .included
                .iter()
                .all(|entry| entry.lens.maker != "Adapted")
        );
    }

    #[test]
    fn bulk_acm_and_unknown_models_abort_instead_of_being_skipped() {
        for model in ["acm", "future-model"] {
            let lenses = format!(
                r#"<lensdatabase version="2"><lens>
                  <maker>Glass</maker><model>Unsupported</model><mount>Phone Mount</mount>
                  <cropfactor>2</cropfactor><calibration>
                    <distortion model="{model}" focal="10"/>
                  </calibration>
                </lens></lensdatabase>"#
            );
            let docs = [
                LensfunXml {
                    source_name: "camera.xml",
                    contents: BULK_CAMERA_XML,
                },
                LensfunXml {
                    source_name: "unsupported.xml",
                    contents: &lenses,
                },
            ];
            let error = compile_database_with_compatible(
                &docs,
                &[],
                &[compatible_request(Some("Phone Mount"))],
            )
            .unwrap_err();
            if model == "acm" {
                assert!(matches!(error, CompileError::UnsupportedAcm { .. }));
            } else {
                assert!(matches!(
                    error,
                    CompileError::UnsupportedDistortionModel { .. }
                ));
            }
        }
    }

    #[test]
    fn bulk_preflight_rejects_unsupported_unselected_duplicate() {
        let lenses = r#"
          <lensdatabase version="2">
            <lens><maker>Glass</maker><model>Duplicate</model><mount>Phone Mount</mount>
              <cropfactor>2</cropfactor><aspect-ratio>4:3</aspect-ratio><calibration>
                <distortion model="poly3" focal="10" k1="0"/>
              </calibration>
            </lens>
            <lens><maker>Glass</maker><model>Duplicate</model><mount>Phone Mount</mount>
              <cropfactor>1</cropfactor><calibration>
                <distortion model="acm" focal="10"/>
              </calibration>
            </lens>
          </lensdatabase>
        "#;
        let docs = [
            LensfunXml {
                source_name: "camera.xml",
                contents: BULK_CAMERA_XML,
            },
            LensfunXml {
                source_name: "duplicates.xml",
                contents: lenses,
            },
        ];
        assert!(matches!(
            compile_database_with_compatible(
                &docs,
                &[],
                &[compatible_request(Some("Phone Mount"))],
            ),
            Err(CompileError::UnsupportedAcm { .. })
        ));
    }
}

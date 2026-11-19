//! Shared production camera contract for the solve and false-positive validators.

use std::f64::consts::PI;
use std::fs::File;
use std::path::Path;

use tetra3::core::{PixelCentroid, UnitVec3};
use tetra3::production::{
    OM1_NATIVE_HEIGHT, OM1_NATIVE_WIDTH, OM1_PIXEL_PITCH_UM, STAR_ENCODED_HEIGHT,
    STAR_ENCODED_WIDTH, STAR_WIRE_PIXEL_PHASE, om1_star_camera_geometry,
};
use tetra3_index::MappedIndex;
use tetra3_lens::{CorrectedCoord, Database, LensProfile, Orientation, PreparedLens, Projection};

use crate::{Error, Result};

/// Exact OM-1 -> STAR rectilinear camera and integer transport contract.
#[derive(Clone)]
pub(crate) struct StarCamera {
    pub lens: PreparedLens,
    pub focal_length_mm: f64,
    radians_per_native_pixel: f64,
}

impl StarCamera {
    pub(crate) fn for_horizontal_fov(fov_rad: f64) -> Result<Self> {
        let native_width_mm = f64::from(OM1_NATIVE_WIDTH) * OM1_PIXEL_PITCH_UM / 1_000.0;
        let focal_length_mm = native_width_mm / (2.0 * (0.5 * fov_rad).tan());
        if !focal_length_mm.is_finite() || focal_length_mm <= 0.0 {
            return Err(Error::Validation(
                "STAR camera produced an invalid focal length".into(),
            ));
        }
        let geometry = om1_star_camera_geometry();
        let lens =
            PreparedLens::rectilinear_no_distortion(geometry, focal_length_mm, OM1_PIXEL_PITCH_UM)
                .map_err(|error| Error::Validation(error.to_string()))?;
        Ok(Self {
            lens,
            focal_length_mm,
            radians_per_native_pixel: OM1_PIXEL_PITCH_UM / 1_000.0 / focal_length_mm,
        })
    }

    pub(crate) fn encode_solver_centroid(&self, ray: UnitVec3) -> Result<PixelCentroid> {
        let direction = ray.as_vec();
        if direction.z <= 0.0 {
            return Err(Error::Validation(
                "cannot encode a camera ray behind the image plane".into(),
            ));
        }
        let native_x = f64::from(OM1_NATIVE_WIDTH - 1) * 0.5
            + (direction.x / direction.z) / self.radians_per_native_pixel;
        let native_y = f64::from(OM1_NATIVE_HEIGHT - 1) * 0.5
            + (direction.y / direction.z) / self.radians_per_native_pixel;
        let wire_x = encode_star_wire_axis(native_x, OM1_NATIVE_WIDTH, STAR_ENCODED_WIDTH)?;
        let wire_y = encode_star_wire_axis(native_y, OM1_NATIVE_HEIGHT, STAR_ENCODED_HEIGHT)?;
        Ok(PixelCentroid {
            x: f64::from(wire_x) - STAR_WIRE_PIXEL_PHASE,
            y: f64::from(wire_y) - STAR_WIRE_PIXEL_PHASE,
        })
    }

    #[cfg(test)]
    pub(crate) fn encode_wire_centroid(&self, ray: UnitVec3) -> Result<(u32, u32)> {
        let centroid = self.encode_solver_centroid(ray)?;
        Ok((
            (centroid.x + STAR_WIRE_PIXEL_PHASE).round() as u32,
            (centroid.y + STAR_WIRE_PIXEL_PHASE).round() as u32,
        ))
    }

    pub(crate) fn wire_pixel_scale_arcsec(&self) -> f64 {
        radians_to_arcseconds((4.0 * self.radians_per_native_pixel).atan())
    }
}

/// Host-only forward model. It intentionally does not call `pixel_to_ray`, so
/// acceptance tests retain an independent projection/distortion/raster path.
#[derive(Clone)]
pub(crate) struct ProfileRenderer {
    pub profile: LensProfile,
    pub lens: PreparedLens,
}

impl ProfileRenderer {
    #[cfg(test)]
    pub(crate) fn new(profile: LensProfile, lens: PreparedLens) -> Self {
        Self { profile, lens }
    }

    pub(crate) fn load(path: &Path, profile_id: &str, focal_length_mm: f64) -> Result<Self> {
        let bytes = std::fs::read(path).map_err(|error| Error::io(path, error))?;
        let database = Database::from_t3lf(&bytes)
            .map_err(|error| Error::Validation(format!("invalid lens database: {error}")))?;
        let profile = database
            .require(profile_id)
            .map_err(|error| Error::Validation(error.to_string()))?
            .clone();
        let lens = profile
            .prepare(focal_length_mm)
            .map_err(|error| Error::Validation(error.to_string()))?;
        Ok(Self { profile, lens })
    }

    pub(crate) fn render_centroid(&self, ray: UnitVec3) -> Result<Option<PixelCentroid>> {
        let Some(corrected) = project_camera_ray(ray, self.profile.projection()) else {
            return Ok(None);
        };
        let distorted = match self.lens.distort_corrected(corrected) {
            Ok(value) => value,
            Err(tetra3_lens::Error::ProjectionOutOfDomain) => return Ok(None),
            Err(error) => return Err(Error::Validation(error.to_string())),
        };
        let calibration = self.profile.calibration();
        let camera = self.profile.camera();
        let calibration_width = f64::from(camera.calibration_width());
        let calibration_height = f64::from(camera.calibration_height());
        let width = (calibration_width - 1.0).max(1.0);
        let height = (calibration_height - 1.0).max(1.0);
        let center_size = width.min(height);
        let center_x = width * 0.5 + center_size * 0.5 * f64::from(calibration.center_x());
        let center_y = height * 0.5 + center_size * 0.5 * f64::from(calibration.center_y());
        let norm_scale = 36.0_f64.hypot(24.0)
            / f64::from(camera.crop_factor())
            / calibration_width.hypot(calibration_height)
            / self.lens.real_focal_mm();
        if !norm_scale.is_finite() || norm_scale <= 0.0 {
            return Err(Error::Validation(
                "lens produced an invalid raster scale".into(),
            ));
        }
        let calibration_x = center_x + distorted.x / norm_scale;
        let calibration_y = center_y + distorted.y / norm_scale;
        let crop = camera.crop();
        let u = (calibration_x + 0.5 - f64::from(crop.x)) / f64::from(crop.width);
        let v = (calibration_y + 0.5 - f64::from(crop.y)) / f64::from(crop.height);
        let (s, t) = crop_to_output(camera.orientation(), u, v);
        let exact_x = s * f64::from(camera.encoded_width()) - 0.5;
        let exact_y = t * f64::from(camera.encoded_height()) - 0.5;
        if exact_x < -0.5
            || exact_x > f64::from(camera.encoded_width()) - 0.5
            || exact_y < -0.5
            || exact_y > f64::from(camera.encoded_height()) - 0.5
        {
            return Ok(None);
        }
        let wire_x = (exact_x + STAR_WIRE_PIXEL_PHASE)
            .round()
            .clamp(0.0, f64::from(camera.encoded_width() - 1));
        let wire_y = (exact_y + STAR_WIRE_PIXEL_PHASE)
            .round()
            .clamp(0.0, f64::from(camera.encoded_height() - 1));
        Ok(Some(PixelCentroid {
            x: wire_x - STAR_WIRE_PIXEL_PHASE,
            y: wire_y - STAR_WIRE_PIXEL_PHASE,
        }))
    }
}

pub(crate) fn encode_star_wire_axis(native: f64, native_size: u32, wire_size: u32) -> Result<u32> {
    if !native.is_finite() {
        return Err(Error::Validation(
            "non-finite native centroid cannot be encoded".into(),
        ));
    }
    let bounded = native.clamp(0.0, f64::from(native_size - 1));
    let native_q8 = (bounded * 256.0).round() as u32;
    Ok(((native_q8 + 512) / 1_024).min(wire_size - 1))
}

pub(crate) fn fov_to_mdeg(fov_deg: f64) -> Result<u32> {
    let value = (fov_deg * 1_000.0).round();
    if !value.is_finite() || value <= 0.0 || value > f64::from(u32::MAX) {
        return Err(Error::InvalidArgument(
            "FOV must map to a positive millidegree value".into(),
        ));
    }
    Ok(value as u32)
}

pub(crate) fn projection_name(projection: Projection) -> &'static str {
    match projection {
        Projection::Rectilinear => "rectilinear",
        Projection::FisheyeEquidistant => "fisheye_equidistant",
        Projection::Panoramic => "panoramic",
        Projection::Equirectangular => "equirectangular",
        Projection::FisheyeOrthographic => "fisheye_orthographic",
        Projection::FisheyeStereographic => "fisheye_stereographic",
        Projection::FisheyeEquisolid => "fisheye_equisolid",
        Projection::FisheyeThoby => "fisheye_thoby",
    }
}

fn project_camera_ray(ray: UnitVec3, projection: Projection) -> Option<CorrectedCoord> {
    let direction = ray.as_vec();
    let radial = direction.x.hypot(direction.y);
    let theta = radial.atan2(direction.z);
    let coordinate = match projection {
        Projection::Rectilinear => {
            if direction.z <= 0.0 {
                return None;
            }
            CorrectedCoord {
                x: direction.x / direction.z,
                y: direction.y / direction.z,
            }
        }
        Projection::Panoramic => {
            let radius = direction.x.hypot(direction.z);
            if radius == 0.0 {
                return None;
            }
            CorrectedCoord {
                x: direction.x.atan2(direction.z),
                y: direction.y / radius,
            }
        }
        Projection::Equirectangular => CorrectedCoord {
            x: direction.x.atan2(direction.z),
            y: direction.y.asin(),
        },
        radial_projection => {
            if radial == 0.0 {
                return Some(CorrectedCoord { x: 0.0, y: 0.0 });
            }
            let radius = match radial_projection {
                Projection::FisheyeEquidistant => theta,
                Projection::FisheyeOrthographic if direction.z >= 0.0 => theta.sin(),
                Projection::FisheyeOrthographic => return None,
                Projection::FisheyeStereographic => 2.0 * (theta * 0.5).tan(),
                Projection::FisheyeEquisolid => 2.0 * (theta * 0.5).sin(),
                Projection::FisheyeThoby if theta <= (0.5 * PI) / 0.713 => {
                    1.47 * (0.713 * theta).sin()
                }
                Projection::FisheyeThoby => return None,
                _ => unreachable!(),
            };
            CorrectedCoord {
                x: direction.x * radius / radial,
                y: direction.y * radius / radial,
            }
        }
    };
    (coordinate.x.is_finite() && coordinate.y.is_finite()).then_some(coordinate)
}

fn crop_to_output(orientation: Orientation, u: f64, v: f64) -> (f64, f64) {
    match orientation {
        Orientation::Normal => (u, v),
        Orientation::MirrorHorizontal => (1.0 - u, v),
        Orientation::Rotate180 => (1.0 - u, 1.0 - v),
        Orientation::MirrorVertical => (u, 1.0 - v),
        Orientation::Transpose => (v, u),
        Orientation::Rotate90 => (1.0 - v, u),
        Orientation::Transverse => (1.0 - v, 1.0 - u),
        Orientation::Rotate270 => (v, 1.0 - u),
    }
}

pub(crate) fn open_mapped(path: &Path) -> Result<MappedIndex> {
    let file = File::open(path).map_err(|error| Error::io(path, error))?;
    // SAFETY: the mapping is read-only and MappedIndex owns it for every view.
    let mapping = unsafe { memmap2::MmapOptions::new().map(&file) }
        .map_err(|error| Error::io(path, error))?;
    MappedIndex::from_mmap(mapping).map_err(|error| Error::InvalidIndex(error.to_string()))
}

pub(crate) fn radians_to_arcseconds(value: f64) -> f64 {
    value.to_degrees() * 3_600.0
}

/// Computes quantiles from an already sorted nanosecond sample.
pub(crate) fn timing_quantiles_ns(values: &[u64]) -> Option<(f64, f64, f64)> {
    if values.is_empty() {
        return None;
    }
    let p95 = (values.len() * 95).div_ceil(100).saturating_sub(1);
    Some((
        values[values.len() / 2] as f64 / 1_000_000.0,
        values[p95] as f64 / 1_000_000.0,
        values[values.len() - 1] as f64 / 1_000_000.0,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn star_wire_phase_and_integer_path_are_golden() {
        assert_eq!(STAR_WIRE_PIXEL_PHASE, 0.375);
        assert_eq!(encode_star_wire_axis(0.5, 5_184, 1_296).unwrap(), 0);
        assert_eq!(encode_star_wire_axis(3.5, 5_184, 1_296).unwrap(), 1);
        assert_eq!(encode_star_wire_axis(4.5, 5_184, 1_296).unwrap(), 1);
        assert_eq!(encode_star_wire_axis(5_183.0, 5_184, 1_296).unwrap(), 1_295);
        assert!(encode_star_wire_axis(f64::NAN, 5_184, 1_296).is_err());
    }
}

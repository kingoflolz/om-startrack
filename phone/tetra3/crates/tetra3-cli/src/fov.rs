//! Rectilinear detector field-of-view calculations for index sizing.

use serde::Serialize;

use crate::{Error, Result};

/// Detector geometry and the resulting rectilinear fields of view.
#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
pub struct RectilinearFov {
    pub active_width_px: u32,
    pub active_height_px: u32,
    pub pixel_pitch_um: f64,
    pub focal_length_mm: f64,
    pub sensor_width_mm: f64,
    pub sensor_height_mm: f64,
    pub sensor_diagonal_mm: f64,
    pub horizontal_fov_deg: f64,
    pub vertical_fov_deg: f64,
    pub diagonal_fov_deg: f64,
}

/// Calculate the horizontal, vertical, and diagonal rectilinear FOV.
///
/// The active detector size is calculated from the pixel count and pitch. Each
/// angular extent is then `2 * atan(detector_extent / (2 * focal_length))`.
pub fn calculate_rectilinear_fov(
    active_width_px: u32,
    active_height_px: u32,
    pixel_pitch_um: f64,
    focal_length_mm: f64,
) -> Result<RectilinearFov> {
    if active_width_px == 0 || active_height_px == 0 {
        return Err(Error::InvalidArgument(
            "active detector dimensions must be non-zero".into(),
        ));
    }
    validate_positive_finite("pixel pitch", pixel_pitch_um)?;
    validate_positive_finite("focal length", focal_length_mm)?;

    let pixel_pitch_mm = pixel_pitch_um / 1_000.0;
    let sensor_width_mm = f64::from(active_width_px) * pixel_pitch_mm;
    let sensor_height_mm = f64::from(active_height_px) * pixel_pitch_mm;
    let sensor_diagonal_mm = sensor_width_mm.hypot(sensor_height_mm);
    for (name, value) in [
        ("sensor width", sensor_width_mm),
        ("sensor height", sensor_height_mm),
        ("sensor diagonal", sensor_diagonal_mm),
    ] {
        validate_positive_finite(name, value)?;
    }

    let fov_deg = |extent_mm: f64| 2.0 * (extent_mm / (2.0 * focal_length_mm)).atan().to_degrees();
    let horizontal_fov_deg = fov_deg(sensor_width_mm);
    let vertical_fov_deg = fov_deg(sensor_height_mm);
    let diagonal_fov_deg = fov_deg(sensor_diagonal_mm);
    for (name, value) in [
        ("horizontal FOV", horizontal_fov_deg),
        ("vertical FOV", vertical_fov_deg),
        ("diagonal FOV", diagonal_fov_deg),
    ] {
        validate_positive_finite(name, value)?;
    }

    Ok(RectilinearFov {
        active_width_px,
        active_height_px,
        pixel_pitch_um,
        focal_length_mm,
        sensor_width_mm,
        sensor_height_mm,
        sensor_diagonal_mm,
        horizontal_fov_deg,
        vertical_fov_deg,
        diagonal_fov_deg,
    })
}

fn validate_positive_finite(name: &str, value: f64) -> Result<()> {
    if !value.is_finite() || value <= 0.0 {
        return Err(Error::InvalidArgument(format!(
            "{name} must be finite and greater than zero"
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_close(actual: f64, expected: f64) {
        assert!(
            (actual - expected).abs() < 1.0e-12,
            "{actual} != {expected}"
        );
    }

    #[test]
    fn uses_rectilinear_detector_geometry() {
        let result = calculate_rectilinear_fov(1_000, 500, 10.0, 10.0).unwrap();

        assert_close(result.sensor_width_mm, 10.0);
        assert_close(result.sensor_height_mm, 5.0);
        assert_close(result.sensor_diagonal_mm, 125.0_f64.sqrt());
        assert_close(result.horizontal_fov_deg, 53.130_102_354_155_98);
        assert_close(result.vertical_fov_deg, 28.072_486_935_852_957);
        assert_close(result.diagonal_fov_deg, 58.411_864_494_798_834);
    }

    #[test]
    fn om1_300mm_nominal_geometry_is_reproducible() {
        let result = calculate_rectilinear_fov(5_184, 3_888, 3.3, 300.0).unwrap();

        assert_close(result.sensor_width_mm, 17.107_2);
        assert_close(result.sensor_height_mm, 12.830_4);
        assert_close(result.sensor_diagonal_mm, 21.384);
        assert_close(result.horizontal_fov_deg, 3.266_349_613_706_033_5);
        assert_close(result.vertical_fov_deg, 2.450_052_494_119_269);
        assert_close(result.diagonal_fov_deg, 4.082_315_283_419_089);
    }

    #[test]
    fn rejects_invalid_inputs() {
        assert!(calculate_rectilinear_fov(0, 3_888, 3.3, 300.0).is_err());
        assert!(calculate_rectilinear_fov(5_184, 0, 3.3, 300.0).is_err());
        for pitch in [0.0, -1.0, f64::NAN, f64::INFINITY] {
            assert!(calculate_rectilinear_fov(5_184, 3_888, pitch, 300.0).is_err());
        }
        for focal_length in [0.0, -1.0, f64::NAN, f64::INFINITY] {
            assert!(calculate_rectilinear_fov(5_184, 3_888, 3.3, focal_length).is_err());
        }
    }
}

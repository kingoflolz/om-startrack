use crate::distortion::PreparedDistortion;
use crate::{CameraGeometry, DistortionModel, Error, Pixel, Projection, Result, UnitRay};

const MAX_PROFILE_ID_BYTES: usize = 255;
pub(crate) const MAX_KNOTS_PER_PROFILE: usize = 256;
// Outward padding for the fixed number of IEEE-754 operations used by the
// analytic bounds. This is far above their accumulated relative rounding
// error and immaterial beside the physical sub-pixel uncertainty.
const ANGULAR_BOUND_ROUNDING_PAD: f64 = 1.0 + 1.0e-10;

/// An exact, case-sensitive profile identifier. No Unicode normalization or
/// fuzzy matching is performed at runtime.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ProfileId(String);

impl ProfileId {
    pub fn new(value: impl Into<String>) -> Result<Self> {
        let value = value.into();
        if value.len() > MAX_PROFILE_ID_BYTES {
            return Err(Error::ProfileIdTooLong(value.len()));
        }
        if value.is_empty()
            || value.trim() != value
            || value.chars().any(|character| character.is_control())
        {
            return Err(Error::InvalidProfileId);
        }
        Ok(Self(value))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for ProfileId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(f)
    }
}

/// Lensfun calibration-set attributes. These may differ from the camera mode
/// on which the image was captured, so they are stored separately from
/// [`CameraGeometry`].
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CalibrationGeometry {
    crop_factor: f32,
    aspect_ratio: f32,
    center_x: f32,
    center_y: f32,
}

impl CalibrationGeometry {
    pub fn new(crop_factor: f32, aspect_ratio: f32, center_x: f32, center_y: f32) -> Result<Self> {
        if !crop_factor.is_finite() || crop_factor <= 0.0 {
            return Err(Error::InvalidCropFactor);
        }
        if !aspect_ratio.is_finite() || aspect_ratio < 1.0 {
            return Err(Error::InvalidAspectRatio);
        }
        if !center_x.is_finite()
            || !center_y.is_finite()
            || !(-0.5..=0.5).contains(&center_x)
            || !(-0.5..=0.5).contains(&center_y)
        {
            return Err(Error::InvalidCenter);
        }
        Ok(Self {
            crop_factor,
            aspect_ratio,
            center_x,
            center_y,
        })
    }

    #[must_use]
    pub const fn crop_factor(&self) -> f32 {
        self.crop_factor
    }

    #[must_use]
    pub const fn aspect_ratio(&self) -> f32 {
        self.aspect_ratio
    }

    #[must_use]
    pub const fn center_x(&self) -> f32 {
        self.center_x
    }

    #[must_use]
    pub const fn center_y(&self) -> f32 {
        self.center_y
    }
}

/// One raw database calibration sample. `real_focal_mm` is always materialized
/// by the host builder; the runtime never guesses from a missing sentinel.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DistortionKnot {
    focal_mm: f32,
    real_focal_mm: f32,
    model: DistortionModel,
}

impl DistortionKnot {
    pub fn new(focal_mm: f32, real_focal_mm: f32, model: DistortionModel) -> Result<Self> {
        if !focal_mm.is_finite()
            || focal_mm <= 0.0
            || !real_focal_mm.is_finite()
            || real_focal_mm <= 0.0
        {
            return Err(Error::InvalidFocal);
        }
        model.validate()?;
        Ok(Self {
            focal_mm,
            real_focal_mm,
            model,
        })
    }

    #[must_use]
    pub const fn focal_mm(&self) -> f32 {
        self.focal_mm
    }

    #[must_use]
    pub const fn real_focal_mm(&self) -> f32 {
        self.real_focal_mm
    }

    #[must_use]
    pub const fn model(&self) -> DistortionModel {
        self.model
    }
}

/// A camera-mode-specific, validated lens profile.
#[derive(Debug, Clone, PartialEq)]
pub struct LensProfile {
    id: ProfileId,
    camera: CameraGeometry,
    calibration: CalibrationGeometry,
    projection: Projection,
    knots: Vec<DistortionKnot>,
}

impl LensProfile {
    pub fn new(
        id: ProfileId,
        camera: CameraGeometry,
        calibration: CalibrationGeometry,
        projection: Projection,
        knots: Vec<DistortionKnot>,
    ) -> Result<Self> {
        if knots.is_empty() {
            return Err(Error::EmptyCalibration);
        }
        if knots.len() > MAX_KNOTS_PER_PROFILE {
            return Err(Error::TooManyKnots(knots.len()));
        }
        let kind = knots[0].model;
        for pair in knots.windows(2) {
            if pair[0].focal_mm >= pair[1].focal_mm {
                return Err(Error::NonIncreasingFocals);
            }
        }
        if knots.iter().any(|knot| !kind.same_kind(knot.model)) {
            return Err(Error::MixedDistortionModels);
        }

        Ok(Self {
            id,
            camera,
            calibration,
            projection,
            knots,
        })
    }

    #[must_use]
    pub const fn id(&self) -> &ProfileId {
        &self.id
    }

    #[must_use]
    pub const fn camera(&self) -> CameraGeometry {
        self.camera
    }

    #[must_use]
    pub const fn calibration(&self) -> CalibrationGeometry {
        self.calibration
    }

    #[must_use]
    pub const fn projection(&self) -> Projection {
        self.projection
    }

    #[must_use]
    pub fn knots(&self) -> &[DistortionKnot] {
        &self.knots
    }

    pub fn prepare(&self, focal_mm: f64) -> Result<PreparedLens> {
        if !focal_mm.is_finite() || focal_mm <= 0.0 {
            return Err(Error::InvalidFocal);
        }
        let (real_focal_mm, model) = self.interpolate(focal_mm)?;
        let distortion = PreparedDistortion::from_raw(
            model,
            real_focal_mm,
            f64::from(self.calibration.crop_factor),
            f64::from(self.calibration.aspect_ratio),
        )?;

        let width = if self.camera.calibration_width() >= 2 {
            f64::from(self.camera.calibration_width() - 1)
        } else {
            1.0
        };
        let height = if self.camera.calibration_height() >= 2 {
            f64::from(self.camera.calibration_height() - 1)
        } else {
            1.0
        };
        let full_frame_diagonal_mm = 36.0_f64.hypot(24.0);
        let norm_scale = full_frame_diagonal_mm
            / f64::from(self.camera.crop_factor())
            / f64::from(self.camera.calibration_width())
                .hypot(f64::from(self.camera.calibration_height()))
            / real_focal_mm;
        let center_size = width.min(height);
        let center_x = width / 2.0 + center_size / 2.0 * f64::from(self.calibration.center_x);
        let center_y = height / 2.0 + center_size / 2.0 * f64::from(self.calibration.center_y);
        if !norm_scale.is_finite() || norm_scale <= 0.0 {
            return Err(Error::InvalidFocal);
        }

        Ok(PreparedLens {
            camera: self.camera,
            projection: self.projection,
            real_focal_mm,
            norm_scale,
            center_x,
            center_y,
            distortion,
        })
    }

    fn interpolate(&self, focal_mm: f64) -> Result<(f64, DistortionModel)> {
        let upper = self
            .knots
            .partition_point(|knot| f64::from(knot.focal_mm) < focal_mm);
        if upper == 0 {
            let knot = self.knots[0];
            return Ok((f64::from(knot.real_focal_mm), knot.model));
        }
        if upper == self.knots.len() {
            let knot = self.knots[self.knots.len() - 1];
            return Ok((f64::from(knot.real_focal_mm), knot.model));
        }
        if f64::from(self.knots[upper].focal_mm) == focal_mm {
            let knot = self.knots[upper];
            return Ok((f64::from(knot.real_focal_mm), knot.model));
        }

        let left_index = upper - 1;
        let left = self.knots[left_index];
        let right = self.knots[upper];
        let t = (focal_mm - f64::from(left.focal_mm))
            / (f64::from(right.focal_mm) - f64::from(left.focal_mm));
        let outer_left = left_index.checked_sub(1).map(|index| self.knots[index]);
        let outer_right = self.knots.get(upper + 1).copied();

        let real_focal_mm = cubic(
            outer_left.map(|knot| f64::from(knot.real_focal_mm)),
            f64::from(left.real_focal_mm),
            f64::from(right.real_focal_mm),
            outer_right.map(|knot| f64::from(knot.real_focal_mm)),
            t,
        );

        let mut terms = [0.0_f32; 3];
        for (index, output) in terms.iter_mut().enumerate() {
            let scaled_term = |knot: DistortionKnot| {
                f64::from(knot.model.terms()[index]) * f64::from(knot.focal_mm)
            };
            let value = cubic(
                outer_left.map(scaled_term),
                scaled_term(left),
                scaled_term(right),
                outer_right.map(scaled_term),
                t,
            ) / focal_mm;
            if !value.is_finite() || value.abs() > f64::from(f32::MAX) {
                return Err(Error::InvalidCoefficient);
            }
            *output = value as f32;
        }
        if !real_focal_mm.is_finite() || real_focal_mm <= 0.0 {
            return Err(Error::InvalidFocal);
        }
        let model = DistortionModel::from_tag_and_terms(left.model.tag(), terms)?;
        Ok((real_focal_mm, model))
    }
}

fn cubic(left: Option<f64>, start: f64, end: f64, right: Option<f64>, t: f64) -> f64 {
    let t2 = t * t;
    let t3 = t2 * t;
    let start_tangent = left.map_or(end - start, |left| (end - left) * 0.5);
    let end_tangent = right.map_or(end - start, |right| (right - start) * 0.5);
    (2.0 * t3 - 3.0 * t2 + 1.0) * start
        + (t3 - 2.0 * t2 + t) * start_tangent
        + (-2.0 * t3 + 3.0 * t2) * end
        + (t3 - t2) * end_tangent
}

/// A focal-specific image-plane coordinate after inverse radial distortion, in
/// units of real focal length.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct CorrectedCoord {
    pub x: f64,
    pub y: f64,
}

/// Precomputed per-shot state. All expensive interpolation and coefficient
/// conversion happens in [`LensProfile::prepare`]; point methods allocate
/// nothing.
#[derive(Debug, Clone, Copy)]
pub struct PreparedLens {
    camera: CameraGeometry,
    projection: Projection,
    real_focal_mm: f64,
    norm_scale: f64,
    center_x: f64,
    center_y: f64,
    distortion: PreparedDistortion,
}

impl PreparedLens {
    /// Prepare an ideal rectilinear camera when no lens profile exists.
    ///
    /// `pixel_pitch_um` is the physical pitch of the native calibration
    /// raster, not the delivered/encoded image. [`CameraGeometry`] already
    /// maps crops, resize, and EXIF orientation back into that raster. The
    /// optical axis is assumed to pass through the geometric center of the
    /// complete calibration raster.
    ///
    /// This constructor is intentionally separate from [`LensProfile`]: an
    /// identity profile knot would clamp its stored `real_focal_mm` outside
    /// that knot, whereas this path always uses the supplied focal length.
    pub fn rectilinear_no_distortion(
        camera: CameraGeometry,
        focal_mm: f64,
        pixel_pitch_um: f64,
    ) -> Result<Self> {
        if !focal_mm.is_finite() || focal_mm <= 0.0 {
            return Err(Error::InvalidFocal);
        }
        if !pixel_pitch_um.is_finite() || pixel_pitch_um <= 0.0 {
            return Err(Error::InvalidPixelPitch);
        }
        let norm_scale = pixel_pitch_um / 1_000.0 / focal_mm;
        if !norm_scale.is_finite() || norm_scale <= 0.0 {
            return Err(Error::InvalidPixelPitch);
        }

        Ok(Self {
            camera,
            projection: Projection::Rectilinear,
            real_focal_mm: focal_mm,
            norm_scale,
            center_x: f64::from(camera.calibration_width() - 1) / 2.0,
            center_y: f64::from(camera.calibration_height() - 1) / 2.0,
            distortion: PreparedDistortion::None,
        })
    }

    #[must_use]
    pub const fn camera(&self) -> CameraGeometry {
        self.camera
    }

    #[must_use]
    pub const fn projection(&self) -> Projection {
        self.projection
    }

    #[must_use]
    pub const fn real_focal_mm(&self) -> f64 {
        self.real_focal_mm
    }

    /// Angular derivative at the optical axis, in radians per native
    /// calibration pixel, before any fitted coordinate scale is applied.
    ///
    /// Prepared distortion models have unit derivative at the optical axis.
    /// Crop, output resize, and orientation therefore do not change this
    /// scale. Profile geometry supplies its own pixel pitch; this need not
    /// equal a separately assumed physical sensor pitch.
    #[must_use]
    pub const fn central_angular_scale_per_calibration_pixel(&self) -> f64 {
        self.norm_scale * self.projection.central_angular_gain()
    }

    pub fn pixel_to_corrected(&self, pixel: Pixel) -> Result<CorrectedCoord> {
        let distorted = self.pixel_to_distorted(pixel)?;
        let (x, y) = self.distortion.undistort(distorted.x, distorted.y)?;
        Ok(CorrectedCoord { x, y })
    }

    fn pixel_to_distorted(&self, pixel: Pixel) -> Result<CorrectedCoord> {
        let calibration_pixel = self.camera.map_to_calibration_pixel(pixel)?;
        let distorted_x = (calibration_pixel.x - self.center_x) * self.norm_scale;
        let distorted_y = (calibration_pixel.y - self.center_y) * self.norm_scale;
        Ok(CorrectedCoord {
            x: distorted_x,
            y: distorted_y,
        })
    }

    pub fn corrected_to_ray(&self, coordinate: CorrectedCoord) -> Result<UnitRay> {
        self.projection.unproject(coordinate.x, coordinate.y)
    }

    pub fn pixel_to_ray(&self, pixel: Pixel) -> Result<UnitRay> {
        self.corrected_to_ray(self.pixel_to_corrected(pixel)?)
    }

    /// Convert a pixel to a ray after scaling its corrected image-plane
    /// coordinate. This is equivalent to [`Self::pixel_to_corrected`],
    /// multiplying `x` and then `y` by `scale`, and
    /// [`Self::corrected_to_ray`].
    pub fn pixel_to_ray_scaled(&self, pixel: Pixel, scale: f64) -> Result<UnitRay> {
        let mut corrected = self.pixel_to_corrected(pixel)?;
        corrected.x *= scale;
        corrected.y *= scale;
        self.corrected_to_ray(corrected)
    }

    /// Conservative angular error for any encoded-pixel displacement inside
    /// a fixed rectangular image region.
    ///
    /// The delivered-pixel map is affine. Radial distortion has Jacobian
    /// singular values `F'(r)` and `F(r)/r`; their positive minimum over the
    /// region bounds the inverse map. The selected projection's spherical
    /// metric then bounds the image of the straight pixel path, and geodesic
    /// separation cannot exceed that path length. This therefore covers the
    /// complete continuous region, including edge midpoints; it does not
    /// assume that a nonlinear pixel cell is maximized at one of its corners.
    ///
    /// For a rectilinear lens, the bound is uniform over
    /// `minimum_coordinate_scale..=maximum_coordinate_scale` and over the
    /// normalized residual model
    /// `p' = s*(1 + k*|p|^2/reference_radius^2)*p` for
    /// `|k| <= rectilinear_radial_max_abs`. The radial bound must be below
    /// one third, keeping both radial and tangential gains positive through
    /// the reference radius. Other projection families require one exact
    /// scale and a zero residual.
    #[allow(clippy::too_many_arguments)]
    pub fn pixel_region_angular_error_bound(
        &self,
        minimum: Pixel,
        maximum: Pixel,
        maximum_delta_x: f64,
        maximum_delta_y: f64,
        minimum_coordinate_scale: f64,
        maximum_coordinate_scale: f64,
        rectilinear_radial_max_abs: f64,
        rectilinear_radial_reference_radius: f64,
    ) -> Result<f64> {
        if !minimum_coordinate_scale.is_finite()
            || !maximum_coordinate_scale.is_finite()
            || minimum_coordinate_scale <= 0.0
            || minimum_coordinate_scale > maximum_coordinate_scale
            || !rectilinear_radial_max_abs.is_finite()
            || !(0.0..(1.0 / 3.0)).contains(&rectilinear_radial_max_abs)
            || !rectilinear_radial_reference_radius.is_finite()
            || rectilinear_radial_reference_radius <= 0.0
        {
            return Err(Error::InvalidProjectionScale);
        }
        if self.projection != Projection::Rectilinear
            && (minimum_coordinate_scale != maximum_coordinate_scale
                || rectilinear_radial_max_abs != 0.0)
        {
            return Err(Error::InvalidProjectionScale);
        }
        if !minimum.x.is_finite()
            || !minimum.y.is_finite()
            || !maximum.x.is_finite()
            || !maximum.y.is_finite()
            || minimum.x > maximum.x
            || minimum.y > maximum.y
            || !maximum_delta_x.is_finite()
            || !maximum_delta_y.is_finite()
            || maximum_delta_x < 0.0
            || maximum_delta_y < 0.0
        {
            return Err(Error::InvalidPixelRegion);
        }

        let pixels = [
            minimum,
            Pixel::new(maximum.x, minimum.y),
            Pixel::new(minimum.x, maximum.y),
            maximum,
        ];
        let mut corners = [CorrectedCoord::default(); 4];
        for (output, pixel) in corners.iter_mut().zip(pixels) {
            *output = self.pixel_to_distorted(pixel)?;
        }
        let minimum_x = corners
            .iter()
            .map(|point| point.x)
            .fold(f64::INFINITY, f64::min);
        let maximum_x = corners
            .iter()
            .map(|point| point.x)
            .fold(f64::NEG_INFINITY, f64::max);
        let minimum_y = corners
            .iter()
            .map(|point| point.y)
            .fold(f64::INFINITY, f64::min);
        let maximum_y = corners
            .iter()
            .map(|point| point.y)
            .fold(f64::NEG_INFINITY, f64::max);
        let minimum_absolute = |low: f64, high: f64| {
            if low <= 0.0 && high >= 0.0 {
                0.0
            } else {
                low.abs().min(high.abs())
            }
        };
        let minimum_distorted_radius =
            minimum_absolute(minimum_x, maximum_x).hypot(minimum_absolute(minimum_y, maximum_y));
        let maximum_distorted_radius = corners
            .iter()
            .map(|point| point.x.hypot(point.y))
            .fold(0.0_f64, f64::max);
        let minimum_corrected_radius = self
            .distortion
            .undistort(minimum_distorted_radius, 0.0)?
            .0
            .abs();
        let maximum_corrected_radius = self
            .distortion
            .undistort(maximum_distorted_radius, 0.0)?
            .0
            .abs();
        let minimum_distortion_gain = self
            .distortion
            .minimum_jacobian_scale_through(maximum_corrected_radius);
        if !minimum_distortion_gain.is_finite() || minimum_distortion_gain <= 0.0 {
            return Err(Error::NonInvertibleDistortion);
        }

        let crop = self.camera.crop();
        let (oriented_width, oriented_height) = if self.camera.orientation().swaps_axes() {
            (crop.height, crop.width)
        } else {
            (crop.width, crop.height)
        };
        let natural_delta = (self.norm_scale * f64::from(oriented_width)
            / f64::from(self.camera.encoded_width())
            * maximum_delta_x)
            .hypot(
                self.norm_scale * f64::from(oriented_height)
                    / f64::from(self.camera.encoded_height())
                    * maximum_delta_y,
            );
        let angular_gain = if self.projection == Projection::Rectilinear
            && rectilinear_radial_max_abs != 0.0
        {
            if maximum_corrected_radius > rectilinear_radial_reference_radius * (1.0 + 1.0e-12) {
                return Err(Error::InvalidPixelRegion);
            }
            rectilinear_residual_angular_gain_bound(
                minimum_corrected_radius,
                maximum_corrected_radius,
                minimum_coordinate_scale,
                maximum_coordinate_scale,
                rectilinear_radial_max_abs,
                rectilinear_radial_reference_radius,
            )
        } else {
            maximum_coordinate_scale
                * self.projection.angular_lipschitz_between(
                    minimum_corrected_radius * maximum_coordinate_scale,
                    maximum_corrected_radius * maximum_coordinate_scale,
                )?
        };
        let path_bound =
            natural_delta * angular_gain / minimum_distortion_gain * ANGULAR_BOUND_ROUNDING_PAD;
        if path_bound.is_finite() {
            Ok(path_bound.min(std::f64::consts::PI))
        } else {
            Ok(std::f64::consts::PI)
        }
    }

    /// Forward distortion is exposed for calibration validation and synthetic
    /// tests. Its output remains in natural-coordinate units, not pixels.
    pub fn distort_corrected(&self, coordinate: CorrectedCoord) -> Result<CorrectedCoord> {
        let (x, y) = self.distortion.distort(coordinate.x, coordinate.y)?;
        Ok(CorrectedCoord { x, y })
    }
}

fn rectilinear_residual_angular_gain_bound(
    minimum_radius: f64,
    maximum_radius: f64,
    minimum_scale: f64,
    maximum_scale: f64,
    maximum_radial: f64,
    reference_radius: f64,
) -> f64 {
    const RADIAL_INTERVALS: usize = 128;

    let reference2 = reference_radius * reference_radius;
    let minimum_t = (minimum_radius * minimum_radius / reference2).clamp(0.0, 1.0);
    let maximum_t = (maximum_radius * maximum_radius / reference2).clamp(0.0, 1.0);

    // For p' = s*(1+k*t)*p, the tangential spherical gain is
    // s*(1+k*t)/sqrt(1 + s^2*(1+k*t)^2*r^2). It increases with both s and k,
    // so only their positive bounds matter. Its derivative in t has at most
    // one zero, given by the closed-form candidate below.
    let tangential = |t: f64| {
        let radial_factor = 1.0 + maximum_radial * t;
        let scaled_radius = maximum_scale * radial_factor * reference_radius * t.sqrt();
        maximum_scale * radial_factor / (1.0 + scaled_radius * scaled_radius).sqrt()
    };
    let mut maximum_gain = tangential(minimum_t).max(tangential(maximum_t));
    if maximum_radial > 0.0 {
        let stationary_factor =
            (2.0 * maximum_radial / (maximum_scale * maximum_scale * reference2)).cbrt();
        let stationary_t = (stationary_factor - 1.0) / maximum_radial;
        if (minimum_t..=maximum_t).contains(&stationary_t) {
            maximum_gain = maximum_gain.max(tangential(stationary_t));
        }
    }

    // The radial gain is
    // s*(1+3*k*t)/(1 + s^2*(1+k*t)^2*r^2). Its derivative with respect to k
    // has the sign of
    // 3 + s^2*r^2*(1+k*t)*(1-3*k*t), which is positive for |k|<1/3.
    // Thus k=+maximum_radial is worst. On each fixed t interval the numerator
    // is bounded at the upper endpoint, while max_s s/(1+s^2*c(t)^2) is
    // bounded at the lower endpoint because c(t)=r*(1+k*t) is monotone.
    let optimized_scale_gain = |t: f64| {
        let c = reference_radius * t.sqrt() * (1.0 + maximum_radial * t);
        if c == 0.0 {
            return maximum_scale;
        }
        let scale = (1.0 / c).clamp(minimum_scale, maximum_scale);
        scale / (1.0 + (scale * c).powi(2))
    };
    if minimum_t == maximum_t {
        maximum_gain = maximum_gain
            .max((1.0 + 3.0 * maximum_radial * minimum_t) * optimized_scale_gain(minimum_t));
    } else {
        for interval in 0..RADIAL_INTERVALS {
            let low =
                minimum_t + (maximum_t - minimum_t) * interval as f64 / RADIAL_INTERVALS as f64;
            let high = minimum_t
                + (maximum_t - minimum_t) * (interval + 1) as f64 / RADIAL_INTERVALS as f64;
            let upper = (1.0 + 3.0 * maximum_radial * high) * optimized_scale_gain(low);
            maximum_gain = maximum_gain.max(upper);
        }
    }
    maximum_gain * ANGULAR_BOUND_ROUNDING_PAD
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{CropRect, Orientation};

    fn profile(knots: Vec<DistortionKnot>) -> LensProfile {
        LensProfile::new(
            ProfileId::new("test.profile").unwrap(),
            CameraGeometry::new(
                4000,
                3000,
                CropRect::new(0, 0, 4000, 3000),
                400,
                300,
                Orientation::Normal,
                2.0,
            )
            .unwrap(),
            CalibrationGeometry::new(2.0, 4.0 / 3.0, 0.0, 0.0).unwrap(),
            Projection::Rectilinear,
            knots,
        )
        .unwrap()
    }

    #[test]
    fn interpolation_matches_lensfun_focal_scaled_hermite() {
        let profile = profile(vec![
            DistortionKnot::new(10.0, 10.0, DistortionModel::Poly3 { k1: 0.01 }).unwrap(),
            DistortionKnot::new(20.0, 22.0, DistortionModel::Poly3 { k1: 0.03 }).unwrap(),
        ]);
        let (real, model) = profile.interpolate(15.0).unwrap();
        assert!((real - 16.0).abs() < 1e-12);
        let DistortionModel::Poly3 { k1 } = model else {
            panic!("wrong model")
        };
        // Endpoint tangents reduce to a linear interpolation of k*focal:
        // ((.01*10)+(.03*20))/2 / 15 = .7/2/15.
        assert!((f64::from(k1) - 0.023_333_333_333).abs() < 2e-9);
    }

    #[test]
    fn region_bound_covers_a_distortion_maximum_between_cell_corners() {
        let camera = CameraGeometry::new(
            100,
            100,
            CropRect::new(0, 0, 100, 100),
            100,
            100,
            Orientation::Normal,
            2.0,
        )
        .unwrap();
        let half_cell_natural = 0.004_790_556_0;
        let norm_scale = 2.0 * half_cell_natural;
        let center = Pixel::new(40.0, 50.0);
        let distorted_center_x = 0.255_424_094_9;
        let lens = PreparedLens {
            camera,
            projection: Projection::Rectilinear,
            real_focal_mm: 1.0,
            norm_scale,
            center_x: center.x - distorted_center_x / norm_scale,
            center_y: center.y,
            distortion: PreparedDistortion::Poly5 {
                k1: -3.019_204_993_3,
                k2: 4.103_288_139_3,
            },
        };
        let angle = |left: UnitRay, right: UnitRay| {
            (left.x * right.x + left.y * right.y + left.z * right.z)
                .clamp(-1.0, 1.0)
                .acos()
        };
        let center_ray = lens.pixel_to_ray(center).unwrap();
        let inward_midpoint = angle(
            center_ray,
            lens.pixel_to_ray(Pixel::new(center.x - 0.5, center.y))
                .unwrap(),
        );
        let corner_maximum = [(-0.5, -0.5), (-0.5, 0.5), (0.5, -0.5), (0.5, 0.5)]
            .into_iter()
            .map(|(dx, dy)| {
                angle(
                    center_ray,
                    lens.pixel_to_ray(Pixel::new(center.x + dx, center.y + dy))
                        .unwrap(),
                )
            })
            .fold(0.0_f64, f64::max);
        assert!(inward_midpoint > corner_maximum * 1.3);

        let bound = lens
            .pixel_region_angular_error_bound(
                Pixel::new(center.x - 0.5, center.y - 0.5),
                Pixel::new(center.x + 0.5, center.y + 0.5),
                0.5,
                0.5,
                1.0,
                1.0,
                0.0,
                1.0,
            )
            .unwrap();
        assert!(bound >= inward_midpoint);
    }

    #[test]
    fn residual_gain_bound_dominates_the_complete_parameter_box_sample() {
        let reference_radius = 0.7;
        let minimum_scale = 0.92;
        let maximum_scale = 1.08;
        let maximum_radial = 0.25;
        for (minimum_radius, maximum_radius) in [(0.0, 0.7), (0.31, 0.52), (0.66, 0.7)] {
            let bound = rectilinear_residual_angular_gain_bound(
                minimum_radius,
                maximum_radius,
                minimum_scale,
                maximum_scale,
                maximum_radial,
                reference_radius,
            );
            for radius_step in 0..=128 {
                let radius = minimum_radius
                    + (maximum_radius - minimum_radius) * f64::from(radius_step) / 128.0;
                let t = radius * radius / (reference_radius * reference_radius);
                for scale_step in 0..=32 {
                    let scale = minimum_scale
                        + (maximum_scale - minimum_scale) * f64::from(scale_step) / 32.0;
                    for radial_step in 0..=32 {
                        let radial =
                            -maximum_radial + 2.0 * maximum_radial * f64::from(radial_step) / 32.0;
                        let plane_gain = 1.0 + radial * t;
                        let projected_radius = scale * plane_gain * radius;
                        let tangential =
                            scale * plane_gain / (1.0 + projected_radius * projected_radius).sqrt();
                        let radial = scale * (1.0 + 3.0 * radial * t)
                            / (1.0 + projected_radius * projected_radius);
                        assert!(bound >= tangential);
                        assert!(bound >= radial);
                    }
                }
            }
        }
    }

    #[test]
    fn interpolation_clamps_outside_focal_range() {
        let profile = profile(vec![
            DistortionKnot::new(10.0, 9.5, DistortionModel::None).unwrap(),
            DistortionKnot::new(20.0, 19.0, DistortionModel::None).unwrap(),
        ]);
        assert_eq!(profile.interpolate(5.0).unwrap().0, 9.5);
        assert_eq!(profile.interpolate(25.0).unwrap().0, 19.0);
    }

    #[test]
    fn identity_center_maps_forward_and_y_points_down() {
        let profile = profile(vec![
            DistortionKnot::new(25.0, 25.0, DistortionModel::None).unwrap(),
        ]);
        let lens = profile.prepare(25.0).unwrap();
        let center = lens.pixel_to_ray(Pixel::new(199.5, 149.5)).unwrap();
        assert!((center.x).abs() < 1e-14);
        assert!((center.y).abs() < 1e-14);
        assert!((center.z - 1.0).abs() < 1e-14);

        let below = lens.pixel_to_ray(Pixel::new(199.5, 200.0)).unwrap();
        assert!(below.y > 0.0);
    }

    #[test]
    fn scaled_pixel_projection_preserves_the_explicit_operation_order() {
        let lens = profile(vec![
            DistortionKnot::new(25.0, 25.0, DistortionModel::Poly3 { k1: 0.01 }).unwrap(),
        ])
        .prepare(25.0)
        .unwrap();
        let pixel = Pixel::new(123.25, 201.75);
        let scale = 1.125;

        let mut corrected = lens.pixel_to_corrected(pixel).unwrap();
        corrected.x *= scale;
        corrected.y *= scale;
        let expected = lens.corrected_to_ray(corrected).unwrap();
        let actual = lens.pixel_to_ray_scaled(pixel, scale).unwrap();

        assert_eq!(actual.x.to_bits(), expected.x.to_bits());
        assert_eq!(actual.y.to_bits(), expected.y.to_bits());
        assert_eq!(actual.z.to_bits(), expected.z.to_bits());
    }

    #[test]
    fn no_profile_camera_uses_supplied_pitch_and_focal() {
        let camera = CameraGeometry::new(
            5184,
            3888,
            CropRect::new(0, 0, 5184, 3888),
            1296,
            972,
            Orientation::Normal,
            2.0,
        )
        .unwrap();
        let lens = PreparedLens::rectilinear_no_distortion(camera, 300.0, 3.3).unwrap();

        assert_eq!(lens.real_focal_mm(), 300.0);
        assert!((lens.norm_scale - 3.3 / 1_000.0 / 300.0).abs() < 1.0e-18);

        // Active-raster edge geometry reproduces the documented OM-1 target:
        // 2*atan((5184 * 3.3um) / (2 * 300mm)).
        let half_width = f64::from(camera.calibration_width()) * lens.norm_scale / 2.0;
        let edge = lens
            .corrected_to_ray(CorrectedCoord {
                x: half_width,
                y: 0.0,
            })
            .unwrap();
        let horizontal_fov_deg = 2.0 * edge.x.atan2(edge.z) * 180.0 / core::f64::consts::PI;
        assert!((horizontal_fov_deg - 3.266_349_613_706_033).abs() < 1.0e-12);

        // Resize does not alter the native pitch. Pixel-rim alignment maps
        // this delivered center to x=5181.5 in the calibration raster.
        let right = lens.pixel_to_ray(Pixel::new(1295.0, 485.5)).unwrap();
        let expected_tangent = 2_590.0 * 3.3 / 1_000.0 / 300.0;
        assert!((right.x / right.z - expected_tangent).abs() < 1.0e-14);
    }

    #[test]
    fn no_profile_camera_rejects_invalid_physical_inputs() {
        let camera = CameraGeometry::new(
            2,
            2,
            CropRect::new(0, 0, 2, 2),
            2,
            2,
            Orientation::Normal,
            2.0,
        )
        .unwrap();
        assert_eq!(
            PreparedLens::rectilinear_no_distortion(camera, 0.0, 3.3).unwrap_err(),
            Error::InvalidFocal
        );
        assert_eq!(
            PreparedLens::rectilinear_no_distortion(camera, 300.0, f64::NAN).unwrap_err(),
            Error::InvalidPixelPitch
        );
        assert_eq!(
            PreparedLens::rectilinear_no_distortion(camera, 300.0, 0.0).unwrap_err(),
            Error::InvalidPixelPitch
        );
    }

    #[test]
    fn central_scale_matches_ray_derivatives_for_every_projection_and_distortion() {
        let projections = [
            Projection::Rectilinear,
            Projection::FisheyeEquidistant,
            Projection::Panoramic,
            Projection::Equirectangular,
            Projection::FisheyeOrthographic,
            Projection::FisheyeStereographic,
            Projection::FisheyeEquisolid,
            Projection::FisheyeThoby,
        ];
        let models = [
            DistortionModel::None,
            DistortionModel::Poly3 { k1: 0.2 },
            DistortionModel::Poly5 { k1: 0.15, k2: 0.02 },
            DistortionModel::Ptlens {
                a: 0.01,
                b: 0.02,
                c: 0.03,
            },
        ];
        for projection in projections {
            for model in models {
                let mut raw = profile(vec![DistortionKnot::new(25.0, 25.0, model).unwrap()]);
                raw.projection = projection;
                let lens = raw.prepare(25.0).unwrap();
                let reported = lens.central_angular_scale_per_calibration_pixel();
                // This fixture delivers one pixel per ten calibration pixels.
                // atan2 retains precision where acos(ray.z) loses small angles.
                for (dx, dy) in [(0.001, 0.0), (-0.001, 0.0), (0.0, 0.001), (0.0, -0.001)] {
                    let ray = lens
                        .pixel_to_ray(Pixel::new(199.5 + dx, 149.5 + dy))
                        .unwrap();
                    let measured = ray.x.hypot(ray.y).atan2(ray.z) / 0.01;
                    assert!(
                        (measured / reported - 1.0).abs() < 1.0e-5,
                        "{projection:?} {model:?}: measured {measured}, reported {reported}"
                    );
                }
            }
        }
    }

    #[test]
    fn central_scale_is_invariant_to_delivered_geometry_and_principal_point() {
        let raw = profile(vec![
            DistortionKnot::new(25.0, 25.0, DistortionModel::Poly3 { k1: 0.2 }).unwrap(),
        ]);
        let baseline = raw.prepare(25.0).unwrap();
        let expected = baseline.central_angular_scale_per_calibration_pixel();
        let orientations = [
            Orientation::Normal,
            Orientation::MirrorHorizontal,
            Orientation::Rotate180,
            Orientation::MirrorVertical,
            Orientation::Transpose,
            Orientation::Rotate90,
            Orientation::Transverse,
            Orientation::Rotate270,
        ];
        for crop in [
            CropRect::new(0, 0, 4000, 3000),
            CropRect::new(900, 600, 2000, 1500),
        ] {
            for orientation in orientations {
                for divisor in [1, 4] {
                    let (width, height) = if orientation.swaps_axes() {
                        (crop.height, crop.width)
                    } else {
                        (crop.width, crop.height)
                    };
                    let mut modified = raw.clone();
                    modified.camera = CameraGeometry::new(
                        4000,
                        3000,
                        crop,
                        width / divisor,
                        height / divisor,
                        orientation,
                        2.0,
                    )
                    .unwrap();
                    modified.calibration =
                        CalibrationGeometry::new(2.0, 4.0 / 3.0, 0.2, -0.1).unwrap();
                    let lens = modified.prepare(25.0).unwrap();
                    assert_eq!(lens.central_angular_scale_per_calibration_pixel(), expected);
                    let fallback =
                        PreparedLens::rectilinear_no_distortion(lens.camera(), 300.0, 3.3).unwrap();
                    assert_eq!(
                        fallback.central_angular_scale_per_calibration_pixel(),
                        3.3 / 1_000.0 / 300.0
                    );
                }
            }
        }
    }
}

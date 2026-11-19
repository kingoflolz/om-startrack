use crate::{Error, Result};

/// A centroid in the delivered/encoded image, in pixel-center coordinates.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Pixel {
    pub x: f64,
    pub y: f64,
}

impl Pixel {
    #[must_use]
    pub const fn new(x: f64, y: f64) -> Self {
        Self { x, y }
    }
}

/// An axis-aligned crop in the un-oriented calibration raster.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CropRect {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

impl CropRect {
    #[must_use]
    pub const fn new(x: u32, y: u32, width: u32, height: u32) -> Self {
        Self {
            x,
            y,
            width,
            height,
        }
    }
}

/// The eight EXIF orientation transforms.
///
/// The transform describes how the un-oriented crop was turned into the
/// delivered image. Runtime mapping applies its inverse.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
#[repr(u8)]
pub enum Orientation {
    #[default]
    Normal = 1,
    MirrorHorizontal = 2,
    Rotate180 = 3,
    MirrorVertical = 4,
    Transpose = 5,
    Rotate90 = 6,
    Transverse = 7,
    Rotate270 = 8,
}

impl Orientation {
    pub fn from_exif(value: u8) -> Result<Self> {
        match value {
            1 => Ok(Self::Normal),
            2 => Ok(Self::MirrorHorizontal),
            3 => Ok(Self::Rotate180),
            4 => Ok(Self::MirrorVertical),
            5 => Ok(Self::Transpose),
            6 => Ok(Self::Rotate90),
            7 => Ok(Self::Transverse),
            8 => Ok(Self::Rotate270),
            _ => Err(Error::InvalidOrientation(value)),
        }
    }

    #[must_use]
    pub const fn swaps_axes(self) -> bool {
        matches!(
            self,
            Self::Transpose | Self::Rotate90 | Self::Transverse | Self::Rotate270
        )
    }

    /// Convert normalized output coordinates to normalized coordinates in the
    /// un-oriented crop. Coordinates refer to pixel rims and normally lie in
    /// `[0, 1]`, making this independent of resize resolution.
    pub(crate) fn output_to_crop(self, s: f64, t: f64) -> (f64, f64) {
        match self {
            Self::Normal => (s, t),
            Self::MirrorHorizontal => (1.0 - s, t),
            Self::Rotate180 => (1.0 - s, 1.0 - t),
            Self::MirrorVertical => (s, 1.0 - t),
            Self::Transpose => (t, s),
            Self::Rotate90 => (t, 1.0 - s),
            Self::Transverse => (1.0 - t, 1.0 - s),
            Self::Rotate270 => (1.0 - t, s),
        }
    }
}

/// Complete mapping from encoded centroid pixels back to the raster coordinate
/// system for which focal length and lens-center metadata apply.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CameraGeometry {
    calibration_width: u32,
    calibration_height: u32,
    crop: CropRect,
    encoded_width: u32,
    encoded_height: u32,
    orientation: Orientation,
    crop_factor: f32,
}

impl CameraGeometry {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        calibration_width: u32,
        calibration_height: u32,
        crop: CropRect,
        encoded_width: u32,
        encoded_height: u32,
        orientation: Orientation,
        crop_factor: f32,
    ) -> Result<Self> {
        if calibration_width == 0
            || calibration_height == 0
            || encoded_width == 0
            || encoded_height == 0
        {
            return Err(Error::InvalidDimensions);
        }
        let right = u64::from(crop.x) + u64::from(crop.width);
        let bottom = u64::from(crop.y) + u64::from(crop.height);
        if crop.width == 0
            || crop.height == 0
            || right > u64::from(calibration_width)
            || bottom > u64::from(calibration_height)
        {
            return Err(Error::InvalidCrop);
        }
        if !crop_factor.is_finite() || crop_factor <= 0.0 {
            return Err(Error::InvalidCropFactor);
        }

        let (oriented_width, oriented_height) = if orientation.swaps_axes() {
            (crop.height, crop.width)
        } else {
            (crop.width, crop.height)
        };
        // Permit the normal one-pixel integer-resize rounding error, but reject
        // an unstated crop or non-uniform stretch. Both otherwise look like a
        // valid transform and bias every ray.
        let lhs = u64::from(encoded_width) * u64::from(oriented_height);
        let rhs = u64::from(encoded_height) * u64::from(oriented_width);
        let rounding_tolerance = u64::from(oriented_width) + u64::from(oriented_height);
        if lhs.abs_diff(rhs) > rounding_tolerance {
            return Err(Error::InvalidResizeAspect);
        }

        Ok(Self {
            calibration_width,
            calibration_height,
            crop,
            encoded_width,
            encoded_height,
            orientation,
            crop_factor,
        })
    }

    #[must_use]
    pub const fn calibration_width(&self) -> u32 {
        self.calibration_width
    }

    #[must_use]
    pub const fn calibration_height(&self) -> u32 {
        self.calibration_height
    }

    #[must_use]
    pub const fn crop(&self) -> CropRect {
        self.crop
    }

    #[must_use]
    pub const fn encoded_width(&self) -> u32 {
        self.encoded_width
    }

    #[must_use]
    pub const fn encoded_height(&self) -> u32 {
        self.encoded_height
    }

    #[must_use]
    pub const fn orientation(&self) -> Orientation {
        self.orientation
    }

    #[must_use]
    pub const fn crop_factor(&self) -> f32 {
        self.crop_factor
    }

    /// Map an encoded pixel coordinate into the full, un-oriented calibration
    /// raster. Sub-pixel centroids may extend to the outer pixel rims
    /// (`-0.5` and `size - 0.5`); values beyond the delivered image remain an
    /// error. The half-pixel terms preserve pixel-rim alignment when resized.
    pub(crate) fn map_to_calibration_pixel(&self, pixel: Pixel) -> Result<Pixel> {
        if !pixel.x.is_finite() || !pixel.y.is_finite() {
            return Err(Error::NonFinitePixel);
        }
        let width = f64::from(self.encoded_width);
        let height = f64::from(self.encoded_height);
        if pixel.x < -0.5 || pixel.x > width - 0.5 || pixel.y < -0.5 || pixel.y > height - 0.5 {
            return Err(Error::PixelOutOfBounds);
        }

        let s = (pixel.x + 0.5) / width;
        let t = (pixel.y + 0.5) / height;
        let (u, v) = self.orientation.output_to_crop(s, t);
        Ok(Pixel {
            x: f64::from(self.crop.x) + u * f64::from(self.crop.width) - 0.5,
            y: f64::from(self.crop.y) + v * f64::from(self.crop.height) - 0.5,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn geometry(orientation: Orientation) -> CameraGeometry {
        CameraGeometry::new(
            400,
            300,
            CropRect::new(100, 50, 200, 100),
            if orientation.swaps_axes() { 50 } else { 100 },
            if orientation.swaps_axes() { 100 } else { 50 },
            orientation,
            2.0,
        )
        .unwrap()
    }

    #[test]
    fn every_orientation_maps_output_center_to_crop_center() {
        for tag in 1..=8 {
            let geometry = geometry(Orientation::from_exif(tag).unwrap());
            let center = Pixel::new(
                (f64::from(geometry.encoded_width()) - 1.0) / 2.0,
                (f64::from(geometry.encoded_height()) - 1.0) / 2.0,
            );
            let mapped = geometry.map_to_calibration_pixel(center).unwrap();
            assert!((mapped.x - 199.5).abs() < 1e-12, "orientation {tag}");
            assert!((mapped.y - 99.5).abs() < 1e-12, "orientation {tag}");
        }
    }

    #[test]
    fn rotation_90_maps_top_left_pixel_without_off_by_one() {
        let geometry = geometry(Orientation::Rotate90);
        let mapped = geometry
            .map_to_calibration_pixel(Pixel::new(0.0, 0.0))
            .unwrap();
        assert_eq!(mapped, Pixel::new(100.5, 148.5));
    }

    #[test]
    fn rejects_crop_overflow_without_u32_wrap() {
        let result = CameraGeometry::new(
            u32::MAX,
            100,
            CropRect::new(u32::MAX, 0, 1, 1),
            1,
            1,
            Orientation::Normal,
            1.0,
        );
        assert_eq!(result.unwrap_err(), Error::InvalidCrop);
    }

    #[test]
    fn quarter_size_wire_phase_maps_back_to_native_centroids() {
        let geometry = CameraGeometry::new(
            5_184,
            3_888,
            CropRect::new(0, 0, 5_184, 3_888),
            1_296,
            972,
            Orientation::Normal,
            2.0,
        )
        .unwrap();
        for wire_x in [0_u32, 648, 1_295] {
            let encoded_x = f64::from(wire_x) - 0.375;
            let mapped = geometry
                .map_to_calibration_pixel(Pixel::new(encoded_x, 485.625))
                .unwrap();
            assert!((mapped.x - f64::from(4 * wire_x)).abs() < 1.0e-12);
            assert!((mapped.y - 1_944.0).abs() < 1.0e-12);
        }
        assert!(
            geometry
                .map_to_calibration_pixel(Pixel::new(-0.500_001, 0.0))
                .is_err()
        );
        assert!(
            geometry
                .map_to_calibration_pixel(Pixel::new(1_295.500_001, 0.0))
                .is_err()
        );
    }
}

use std::fmt;

/// Errors are fail-closed: no unsupported model or failed inverse is treated
/// as an identity transform.
#[derive(Debug, Clone, PartialEq)]
#[non_exhaustive]
pub enum Error {
    InvalidDimensions,
    InvalidCrop,
    InvalidResizeAspect,
    InvalidOrientation(u8),
    InvalidCropFactor,
    InvalidPixelPitch,
    InvalidAspectRatio,
    InvalidCenter,
    InvalidProfileId,
    ProfileIdTooLong(usize),
    EmptyCalibration,
    TooManyKnots(usize),
    InvalidFocal,
    NonIncreasingFocals,
    MixedDistortionModels,
    InvalidCoefficient,
    DegenerateDistortion,
    NonFinitePixel,
    PixelOutOfBounds,
    InvalidPixelRegion,
    InvalidProjectionScale,
    InverseDidNotConverge { residual: f64 },
    NonInvertibleDistortion,
    ProjectionOutOfDomain,
    InvalidRay,
    DuplicateProfileId(String),
    ProfileNotFound(String),
    FileTooLarge(usize),
    Truncated,
    BadMagic,
    UnsupportedVersion(u16),
    InvalidHeader,
    ChecksumMismatch { expected: u32, actual: u32 },
    TooManyProfiles(u32),
    InvalidDirectory,
    InvalidRecord,
    InvalidUtf8,
    NonCanonicalProfileOrder,
    UnsupportedProjection(u8),
    UnsupportedDistortionModel(u8),
    UnsupportedAcm,
    SizeOverflow,
}

pub type Result<T> = core::result::Result<T, Error>;

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidDimensions => f.write_str("image dimensions must be non-zero"),
            Self::InvalidCrop => f.write_str("crop is empty or outside the calibration frame"),
            Self::InvalidResizeAspect => {
                f.write_str("encoded dimensions do not preserve the oriented crop aspect ratio")
            }
            Self::InvalidOrientation(value) => write!(f, "invalid orientation tag {value}"),
            Self::InvalidCropFactor => f.write_str("crop factor must be finite and positive"),
            Self::InvalidPixelPitch => {
                f.write_str("pixel pitch must be finite and greater than zero")
            }
            Self::InvalidAspectRatio => {
                f.write_str("calibration aspect ratio must be finite and at least one")
            }
            Self::InvalidCenter => f.write_str("lens center offset is invalid"),
            Self::InvalidProfileId => f.write_str("profile ID is empty or contains invalid text"),
            Self::ProfileIdTooLong(length) => {
                write!(f, "profile ID is {length} bytes; maximum is 255")
            }
            Self::EmptyCalibration => f.write_str("profile has no distortion focal knots"),
            Self::TooManyKnots(count) => write!(f, "profile has too many focal knots: {count}"),
            Self::InvalidFocal => f.write_str("focal lengths must be finite and positive"),
            Self::NonIncreasingFocals => {
                f.write_str("distortion knots must have strictly increasing focal lengths")
            }
            Self::MixedDistortionModels => {
                f.write_str("all focal knots in a profile must use the same distortion model")
            }
            Self::InvalidCoefficient => f.write_str("distortion coefficients must all be finite"),
            Self::DegenerateDistortion => {
                f.write_str("distortion coefficients have a zero central scale")
            }
            Self::NonFinitePixel => f.write_str("pixel coordinate is not finite"),
            Self::PixelOutOfBounds => f.write_str("pixel coordinate is outside the encoded image"),
            Self::InvalidPixelRegion => {
                f.write_str("pixel region or displacement bound is invalid")
            }
            Self::InvalidProjectionScale => {
                f.write_str("projection scale or radial parameter bound is invalid")
            }
            Self::InverseDidNotConverge { residual } => {
                write!(
                    f,
                    "inverse distortion did not converge (forward residual {residual:e})"
                )
            }
            Self::NonInvertibleDistortion => {
                f.write_str("distortion is not invertible at this coordinate")
            }
            Self::ProjectionOutOfDomain => {
                f.write_str("corrected coordinate is outside the projection domain")
            }
            Self::InvalidRay => f.write_str("ray is non-finite or has zero length"),
            Self::DuplicateProfileId(id) => write!(f, "duplicate profile ID {id:?}"),
            Self::ProfileNotFound(id) => write!(f, "profile ID {id:?} was not found"),
            Self::FileTooLarge(length) => write!(f, ".t3lf file is too large: {length} bytes"),
            Self::Truncated => f.write_str("truncated .t3lf data"),
            Self::BadMagic => f.write_str("invalid .t3lf magic"),
            Self::UnsupportedVersion(version) => {
                write!(f, "unsupported .t3lf version {version}")
            }
            Self::InvalidHeader => f.write_str("invalid .t3lf header"),
            Self::ChecksumMismatch { expected, actual } => write!(
                f,
                ".t3lf checksum mismatch (stored {expected:#010x}, calculated {actual:#010x})"
            ),
            Self::TooManyProfiles(count) => write!(f, "too many .t3lf profiles: {count}"),
            Self::InvalidDirectory => f.write_str("invalid or non-canonical .t3lf directory"),
            Self::InvalidRecord => f.write_str("invalid or non-canonical .t3lf profile record"),
            Self::InvalidUtf8 => f.write_str("profile ID is not valid UTF-8"),
            Self::NonCanonicalProfileOrder => {
                f.write_str(".t3lf profile records are not in strict ID order")
            }
            Self::UnsupportedProjection(tag) => write!(f, "unsupported projection tag {tag}"),
            Self::UnsupportedDistortionModel(tag) => {
                write!(f, "unsupported distortion model tag {tag}")
            }
            Self::UnsupportedAcm => f.write_str("Adobe Camera Model profiles are unsupported"),
            Self::SizeOverflow => f.write_str("serialized .t3lf size exceeds format limits"),
        }
    }
}

impl std::error::Error for Error {}

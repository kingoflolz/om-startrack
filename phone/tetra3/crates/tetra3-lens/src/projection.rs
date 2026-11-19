use std::f64::consts::{FRAC_PI_2, PI};

use crate::{Error, Result};

const THOBY_K1: f64 = 1.47;
const THOBY_K2: f64 = 0.713;
const DOMAIN_EPSILON: f64 = 1.0e-12;

/// Lensfun projection families needed to turn a corrected image-plane
/// coordinate into a camera ray.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
#[repr(u8)]
pub enum Projection {
    #[default]
    Rectilinear = 0,
    FisheyeEquidistant = 1,
    Panoramic = 2,
    Equirectangular = 3,
    FisheyeOrthographic = 4,
    FisheyeStereographic = 5,
    FisheyeEquisolid = 6,
    FisheyeThoby = 7,
}

impl Projection {
    pub(crate) const fn central_angular_gain(self) -> f64 {
        match self {
            Self::FisheyeThoby => 1.0 / (THOBY_K1 * THOBY_K2),
            Self::Rectilinear
            | Self::FisheyeEquidistant
            | Self::Panoramic
            | Self::Equirectangular
            | Self::FisheyeOrthographic
            | Self::FisheyeStereographic
            | Self::FisheyeEquisolid => 1.0,
        }
    }

    pub(crate) fn from_tag(tag: u8) -> Result<Self> {
        match tag {
            0 => Ok(Self::Rectilinear),
            1 => Ok(Self::FisheyeEquidistant),
            2 => Ok(Self::Panoramic),
            3 => Ok(Self::Equirectangular),
            4 => Ok(Self::FisheyeOrthographic),
            5 => Ok(Self::FisheyeStereographic),
            6 => Ok(Self::FisheyeEquisolid),
            7 => Ok(Self::FisheyeThoby),
            _ => Err(Error::UnsupportedProjection(tag)),
        }
    }

    pub(crate) fn unproject(self, x: f64, y: f64) -> Result<UnitRay> {
        if !x.is_finite() || !y.is_finite() {
            return Err(Error::ProjectionOutOfDomain);
        }

        match self {
            Self::Rectilinear => UnitRay::from_xyz(x, y, 1.0),
            Self::FisheyeEquidistant => radial_unproject(x, y, (x * x + y * y).sqrt()),
            Self::FisheyeOrthographic => {
                let r = (x * x + y * y).sqrt();
                if r > 1.0 + DOMAIN_EPSILON {
                    return Err(Error::ProjectionOutOfDomain);
                }
                radial_unproject(x, y, r.min(1.0).asin())
            }
            Self::FisheyeStereographic => {
                let r = (x * x + y * y).sqrt();
                radial_unproject(x, y, 2.0 * (r / 2.0).atan())
            }
            Self::FisheyeEquisolid => {
                let r = (x * x + y * y).sqrt();
                if r > 2.0 + DOMAIN_EPSILON {
                    return Err(Error::ProjectionOutOfDomain);
                }
                radial_unproject(x, y, 2.0 * (r.min(2.0) / 2.0).asin())
            }
            Self::FisheyeThoby => {
                let r = (x * x + y * y).sqrt();
                if r > THOBY_K1 + DOMAIN_EPSILON {
                    return Err(Error::ProjectionOutOfDomain);
                }
                radial_unproject(x, y, (r.min(THOBY_K1) / THOBY_K1).asin() / THOBY_K2)
            }
            Self::Panoramic => {
                // Cylindrical projection: x is longitude and y is height on
                // the unit cylinder.
                if x.abs() > PI {
                    return Err(Error::ProjectionOutOfDomain);
                }
                UnitRay::from_xyz(x.sin(), y, x.cos())
            }
            Self::Equirectangular => {
                if x.abs() > PI || y.abs() > FRAC_PI_2 + DOMAIN_EPSILON {
                    return Err(Error::ProjectionOutOfDomain);
                }
                let latitude = y.clamp(-FRAC_PI_2, FRAC_PI_2);
                let cos_latitude = latitude.cos();
                UnitRay::from_xyz(
                    cos_latitude * x.sin(),
                    latitude.sin(),
                    cos_latitude * x.cos(),
                )
            }
        }
    }

    /// Maximum differential gain from projection-plane distance to spherical
    /// path length on the annulus `minimum_radius..=maximum_radius`.
    ///
    /// Radial projections have radial gain `theta'(r)` and tangential gain
    /// `sin(theta(r))/r`. The expressions below bound both. Cylindrical and
    /// equirectangular coordinates have metric coefficients no greater than
    /// one. Returning infinity at a singular domain boundary lets callers
    /// conservatively cap a finite angular uncertainty at pi.
    pub(crate) fn angular_lipschitz_between(
        self,
        minimum_radius: f64,
        maximum_radius: f64,
    ) -> Result<f64> {
        if !minimum_radius.is_finite()
            || !maximum_radius.is_finite()
            || minimum_radius < 0.0
            || minimum_radius > maximum_radius
        {
            return Err(Error::ProjectionOutOfDomain);
        }

        let gain = match self {
            Self::Rectilinear => 1.0 / (1.0 + minimum_radius * minimum_radius).sqrt(),
            Self::FisheyeEquidistant | Self::Panoramic | Self::Equirectangular => 1.0,
            Self::FisheyeOrthographic => {
                if maximum_radius > 1.0 + DOMAIN_EPSILON {
                    return Err(Error::ProjectionOutOfDomain);
                }
                if maximum_radius >= 1.0 {
                    f64::INFINITY
                } else {
                    1.0 / (1.0 - maximum_radius * maximum_radius).sqrt()
                }
            }
            Self::FisheyeStereographic => 1.0 / (1.0 + minimum_radius * minimum_radius / 4.0),
            Self::FisheyeEquisolid => {
                if maximum_radius >= 2.0 {
                    return Err(Error::ProjectionOutOfDomain);
                }
                1.0 / (1.0 - maximum_radius * maximum_radius / 4.0).sqrt()
            }
            Self::FisheyeThoby => {
                if maximum_radius > THOBY_K1 + DOMAIN_EPSILON {
                    return Err(Error::ProjectionOutOfDomain);
                }
                if maximum_radius >= THOBY_K1 {
                    f64::INFINITY
                } else {
                    1.0 / (THOBY_K1 * THOBY_K2 * (1.0 - (maximum_radius / THOBY_K1).powi(2)).sqrt())
                }
            }
        };
        Ok(gain)
    }
}

fn radial_unproject(x: f64, y: f64, theta: f64) -> Result<UnitRay> {
    if !theta.is_finite() || !(0.0..PI).contains(&theta) {
        if theta == 0.0 {
            return Ok(UnitRay::FORWARD);
        }
        return Err(Error::ProjectionOutOfDomain);
    }
    let r = x.hypot(y);
    if r == 0.0 {
        return Ok(UnitRay::FORWARD);
    }
    let scale = theta.sin() / r;
    UnitRay::from_xyz(x * scale, y * scale, theta.cos())
}

/// A finite, normalized ray in the right-handed camera coordinate system:
/// `+x` right, `+y` down, and `+z` forward.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct UnitRay {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

impl UnitRay {
    const FORWARD: Self = Self {
        x: 0.0,
        y: 0.0,
        z: 1.0,
    };

    fn from_xyz(x: f64, y: f64, z: f64) -> Result<Self> {
        let norm = x.hypot(y).hypot(z);
        if !norm.is_finite() || norm == 0.0 {
            return Err(Error::InvalidRay);
        }
        Ok(Self {
            x: x / norm,
            y: y / norm,
            z: z / norm,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_ray(actual: UnitRay, expected: [f64; 3]) {
        assert!((actual.x - expected[0]).abs() < 1e-12, "{actual:?}");
        assert!((actual.y - expected[1]).abs() < 1e-12, "{actual:?}");
        assert!((actual.z - expected[2]).abs() < 1e-12, "{actual:?}");
    }

    #[test]
    fn projection_scales_reach_the_horizon() {
        let root_half = 0.5_f64.sqrt();
        assert_ray(
            Projection::Rectilinear.unproject(1.0, 0.0).unwrap(),
            [root_half, 0.0, root_half],
        );
        assert_ray(
            Projection::FisheyeEquidistant
                .unproject(FRAC_PI_2, 0.0)
                .unwrap(),
            [1.0, 0.0, 0.0],
        );
        assert_ray(
            Projection::FisheyeOrthographic.unproject(1.0, 0.0).unwrap(),
            [1.0, 0.0, 0.0],
        );
        assert_ray(
            Projection::FisheyeStereographic
                .unproject(2.0, 0.0)
                .unwrap(),
            [1.0, 0.0, 0.0],
        );
        assert_ray(
            Projection::FisheyeEquisolid
                .unproject(2.0_f64.sqrt(), 0.0)
                .unwrap(),
            [1.0, 0.0, 0.0],
        );
        let thoby_horizon = THOBY_K1 * (THOBY_K2 * FRAC_PI_2).sin();
        assert_ray(
            Projection::FisheyeThoby
                .unproject(thoby_horizon, 0.0)
                .unwrap(),
            [1.0, 0.0, 0.0],
        );
    }

    #[test]
    fn map_projections_have_expected_axes() {
        assert_ray(
            Projection::Panoramic.unproject(FRAC_PI_2, 0.0).unwrap(),
            [1.0, 0.0, 0.0],
        );
        assert_ray(
            Projection::Equirectangular
                .unproject(0.0, FRAC_PI_2)
                .unwrap(),
            [0.0, 1.0, 0.0],
        );
    }

    #[test]
    fn projections_fail_outside_their_domain() {
        assert_eq!(
            Projection::FisheyeOrthographic
                .unproject(1.01, 0.0)
                .unwrap_err(),
            Error::ProjectionOutOfDomain
        );
        assert_eq!(
            Projection::Equirectangular
                .unproject(0.0, FRAC_PI_2 + 0.1)
                .unwrap_err(),
            Error::ProjectionOutOfDomain
        );
    }
}

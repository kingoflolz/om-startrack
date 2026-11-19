use crate::{Error, Result};

const MIN_CENTRAL_SCALE: f64 = 1.0e-9;
const MIN_DERIVATIVE: f64 = 1.0e-12;
const MAX_BRACKET_STEPS: usize = 32;
const MAX_INVERSE_STEPS: usize = 48;

/// Raw coefficients as stored by the classic Lensfun database models.
///
/// Poly3 and PTLens include a constant term chosen so that the Hugin radius
/// `r=1` remains fixed. [`PreparedLens`](crate::PreparedLens) converts these
/// coefficients to Lensfun's focal-preserving natural coordinate system before
/// applying them.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub enum DistortionModel {
    #[default]
    None,
    /// `rd = ru * (1 - k1 + k1 * ru^2)` in Hugin coordinates.
    Poly3 { k1: f32 },
    /// `rd = ru * (1 + k1 * ru^2 + k2 * ru^4)`.
    Poly5 { k1: f32, k2: f32 },
    /// `rd = ru * (a*ru^3 + b*ru^2 + c*ru + 1-a-b-c)`.
    Ptlens { a: f32, b: f32, c: f32 },
}

impl DistortionModel {
    pub(crate) const fn tag(self) -> u8 {
        match self {
            Self::None => 0,
            Self::Poly3 { .. } => 1,
            Self::Poly5 { .. } => 2,
            Self::Ptlens { .. } => 3,
        }
    }

    pub(crate) fn from_tag_and_terms(tag: u8, terms: [f32; 3]) -> Result<Self> {
        let model = match tag {
            0 => Self::None,
            1 => Self::Poly3 { k1: terms[0] },
            2 => Self::Poly5 {
                k1: terms[0],
                k2: terms[1],
            },
            3 => Self::Ptlens {
                a: terms[0],
                b: terms[1],
                c: terms[2],
            },
            // Reserve tag 4 specifically for ACM so it can never degrade to
            // an accidental identity profile.
            4 => return Err(Error::UnsupportedAcm),
            _ => return Err(Error::UnsupportedDistortionModel(tag)),
        };
        model.validate()?;
        Ok(model)
    }

    pub(crate) const fn terms(self) -> [f32; 3] {
        match self {
            Self::None => [0.0, 0.0, 0.0],
            Self::Poly3 { k1 } => [k1, 0.0, 0.0],
            Self::Poly5 { k1, k2 } => [k1, k2, 0.0],
            Self::Ptlens { a, b, c } => [a, b, c],
        }
    }

    pub(crate) fn validate(self) -> Result<()> {
        let terms = self.terms();
        if terms.iter().any(|term| !term.is_finite()) {
            return Err(Error::InvalidCoefficient);
        }
        let central_scale = match self {
            Self::Poly3 { k1 } => 1.0 - f64::from(k1),
            Self::Ptlens { a, b, c } => 1.0 - f64::from(a) - f64::from(b) - f64::from(c),
            Self::None | Self::Poly5 { .. } => 1.0,
        };
        if central_scale <= MIN_CENTRAL_SCALE {
            return Err(Error::DegenerateDistortion);
        }
        Ok(())
    }

    pub(crate) const fn same_kind(self, other: Self) -> bool {
        self.tag() == other.tag()
    }
}

/// Coefficients in Lensfun's focal-preserving natural coordinate system.
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) enum PreparedDistortion {
    None,
    Poly3 { k1: f64 },
    Poly5 { k1: f64, k2: f64 },
    Ptlens { a: f64, b: f64, c: f64 },
}

impl PreparedDistortion {
    pub(crate) fn from_raw(
        model: DistortionModel,
        real_focal_mm: f64,
        calibration_crop_factor: f64,
        calibration_aspect_ratio: f64,
    ) -> Result<Self> {
        let full_frame_diagonal_mm = 36.0_f64.hypot(24.0);
        let hugin_half_short_edge_mm = full_frame_diagonal_mm
            / calibration_crop_factor
            / calibration_aspect_ratio.hypot(1.0)
            / 2.0;
        let scale = real_focal_mm / hugin_half_short_edge_mm;
        let scale2 = scale * scale;
        let scale3 = scale2 * scale;
        let scale4 = scale2 * scale2;

        let prepared = match model {
            DistortionModel::None => Self::None,
            DistortionModel::Poly3 { k1 } => {
                let k1 = f64::from(k1);
                let d = 1.0 - k1;
                let d2 = d * d;
                Self::Poly3 {
                    k1: k1 * scale2 / (d2 * d),
                }
            }
            DistortionModel::Poly5 { k1, k2 } => Self::Poly5 {
                k1: f64::from(k1) * scale2,
                k2: f64::from(k2) * scale4,
            },
            DistortionModel::Ptlens { a, b, c } => {
                let a = f64::from(a);
                let b = f64::from(b);
                let c = f64::from(c);
                let d = 1.0 - a - b - c;
                let d2 = d * d;
                let d3 = d2 * d;
                let d4 = d2 * d2;
                Self::Ptlens {
                    a: a * scale3 / d4,
                    b: b * scale2 / d3,
                    c: c * scale / d2,
                }
            }
        };

        if prepared.coefficients_are_finite() {
            Ok(prepared)
        } else {
            Err(Error::InvalidCoefficient)
        }
    }

    fn coefficients_are_finite(self) -> bool {
        match self {
            Self::None => true,
            Self::Poly3 { k1 } => k1.is_finite(),
            Self::Poly5 { k1, k2 } => k1.is_finite() && k2.is_finite(),
            Self::Ptlens { a, b, c } => a.is_finite() && b.is_finite() && c.is_finite(),
        }
    }

    pub(crate) fn forward_radius(self, radius: f64) -> f64 {
        let r2 = radius * radius;
        match self {
            Self::None => radius,
            Self::Poly3 { k1 } => radius * (1.0 + k1 * r2),
            Self::Poly5 { k1, k2 } => radius * (1.0 + k1 * r2 + k2 * r2 * r2),
            Self::Ptlens { a, b, c } => radius * (1.0 + c * radius + b * r2 + a * r2 * radius),
        }
    }

    fn derivative(self, radius: f64) -> f64 {
        let r2 = radius * radius;
        match self {
            Self::None => 1.0,
            Self::Poly3 { k1 } => 1.0 + 3.0 * k1 * r2,
            Self::Poly5 { k1, k2 } => 1.0 + 3.0 * k1 * r2 + 5.0 * k2 * r2 * r2,
            Self::Ptlens { a, b, c } => {
                1.0 + 2.0 * c * radius + 3.0 * b * r2 + 4.0 * a * r2 * radius
            }
        }
    }

    /// Minimum radial derivative on `[0, radius]`. Checking only the Newton
    /// iterate is insufficient for Poly5/PTLens because their derivative can
    /// dip below zero and recover, creating multiple plausible roots.
    fn minimum_derivative_through(self, radius: f64) -> f64 {
        let mut minimum = 1.0_f64.min(self.derivative(radius));
        match self {
            Self::None | Self::Poly3 { .. } => {}
            Self::Poly5 { k1, k2 } => {
                // derivative(u) = 1 + 3*k1*u + 5*k2*u^2, u=r^2.
                if k2 > 0.0 {
                    let vertex = -3.0 * k1 / (10.0 * k2);
                    if vertex > 0.0 && vertex < radius * radius {
                        minimum = minimum.min(1.0 + 3.0 * k1 * vertex + 5.0 * k2 * vertex * vertex);
                    }
                }
            }
            Self::Ptlens { a, b, c } => {
                // Extrema of 1 + 2*c*r + 3*b*r^2 + 4*a*r^3 are roots of
                // 2*c + 6*b*r + 12*a*r^2.
                if a.abs() <= f64::EPSILON {
                    if b.abs() > f64::EPSILON {
                        let root = -c / (3.0 * b);
                        if root > 0.0 && root < radius {
                            minimum = minimum.min(self.derivative(root));
                        }
                    }
                } else {
                    let discriminant = 36.0 * b * b - 96.0 * a * c;
                    if discriminant >= 0.0 {
                        let root_scale = discriminant.sqrt();
                        for root in [
                            (-6.0 * b - root_scale) / (24.0 * a),
                            (-6.0 * b + root_scale) / (24.0 * a),
                        ] {
                            if root > 0.0 && root < radius {
                                minimum = minimum.min(self.derivative(root));
                            }
                        }
                    }
                }
            }
        }
        minimum
    }

    /// Minimum singular value of the radial distortion Jacobian on the
    /// corrected-coordinate disk `|p| <= radius`.
    ///
    /// For `F(p) = g(|p|) p`, the radial and tangential singular values are
    /// `d(r g(r))/dr` and `g(r)`, respectively. Their positive minimum makes
    /// the inverse map globally Lipschitz on the corresponding distorted
    /// region, not merely at a sampled set of image points.
    pub(crate) fn minimum_jacobian_scale_through(self, radius: f64) -> f64 {
        self.minimum_derivative_through(radius)
            .min(self.minimum_tangential_scale_through(radius))
    }

    fn minimum_tangential_scale_through(self, radius: f64) -> f64 {
        let scale = |r: f64| {
            if r == 0.0 {
                1.0
            } else {
                self.forward_radius(r) / r
            }
        };
        let mut minimum = 1.0_f64.min(scale(radius));
        match self {
            Self::None | Self::Poly3 { .. } => {}
            Self::Poly5 { k1, k2 } => {
                // g(r) = 1 + k1*u + k2*u^2, u=r^2.
                if k2 > 0.0 {
                    let vertex = -k1 / (2.0 * k2);
                    if vertex > 0.0 && vertex < radius * radius {
                        minimum = minimum.min(1.0 + k1 * vertex + k2 * vertex * vertex);
                    }
                }
            }
            Self::Ptlens { a, b, c } => {
                // Extrema of g(r) = 1 + c*r + b*r^2 + a*r^3 are roots of
                // c + 2*b*r + 3*a*r^2.
                if a.abs() <= f64::EPSILON {
                    if b.abs() > f64::EPSILON {
                        let root = -c / (2.0 * b);
                        if root > 0.0 && root < radius {
                            minimum = minimum.min(scale(root));
                        }
                    }
                } else {
                    let discriminant = 4.0 * b * b - 12.0 * a * c;
                    if discriminant >= 0.0 {
                        let root_scale = discriminant.sqrt();
                        for root in [
                            (-2.0 * b - root_scale) / (6.0 * a),
                            (-2.0 * b + root_scale) / (6.0 * a),
                        ] {
                            if root > 0.0 && root < radius {
                                minimum = minimum.min(scale(root));
                            }
                        }
                    }
                }
            }
        }
        minimum
    }

    pub(crate) fn distort(self, x: f64, y: f64) -> Result<(f64, f64)> {
        let radius = x.hypot(y);
        if !radius.is_finite() {
            return Err(Error::InvalidCoefficient);
        }
        if radius == 0.0 || matches!(self, Self::None) {
            return Ok((x, y));
        }
        if self.minimum_derivative_through(radius) <= MIN_DERIVATIVE {
            return Err(Error::NonInvertibleDistortion);
        }
        let distorted_radius = self.forward_radius(radius);
        if !distorted_radius.is_finite() || distorted_radius < 0.0 {
            return Err(Error::NonInvertibleDistortion);
        }
        let scale = distorted_radius / radius;
        Ok((x * scale, y * scale))
    }

    pub(crate) fn undistort(self, x: f64, y: f64) -> Result<(f64, f64)> {
        let distorted_radius = x.hypot(y);
        if !distorted_radius.is_finite() {
            return Err(Error::NonFinitePixel);
        }
        if distorted_radius == 0.0 || matches!(self, Self::None) {
            return Ok((x, y));
        }

        let corrected_radius = self.inverse_radius(distorted_radius)?;
        let scale = corrected_radius / distorted_radius;
        Ok((x * scale, y * scale))
    }

    fn inverse_radius(self, target: f64) -> Result<f64> {
        let tolerance = 1.0e-12 * (1.0 + target);
        let mut low = 0.0;
        let mut high = target.max(1.0e-9);
        let mut bracketed = false;

        for _ in 0..MAX_BRACKET_STEPS {
            let value = self.forward_radius(high);
            let minimum_derivative = self.minimum_derivative_through(high);
            if !value.is_finite()
                || !minimum_derivative.is_finite()
                || minimum_derivative <= MIN_DERIVATIVE
            {
                return Err(Error::NonInvertibleDistortion);
            }
            if value >= target {
                bracketed = true;
                break;
            }
            high *= 2.0;
            if !high.is_finite() {
                return Err(Error::NonInvertibleDistortion);
            }
        }
        if !bracketed {
            return Err(Error::NonInvertibleDistortion);
        }

        let mut radius = target.clamp(low, high);
        let mut last_residual = f64::INFINITY;
        for _ in 0..MAX_INVERSE_STEPS {
            let residual = self.forward_radius(radius) - target;
            last_residual = residual.abs();
            if !residual.is_finite() {
                return Err(Error::NonInvertibleDistortion);
            }
            if last_residual <= tolerance {
                return Ok(radius);
            }

            if residual > 0.0 {
                high = radius;
            } else {
                low = radius;
            }

            let derivative = self.derivative(radius);
            if !derivative.is_finite() || derivative <= MIN_DERIVATIVE {
                return Err(Error::NonInvertibleDistortion);
            }
            let newton = radius - residual / derivative;
            radius = if newton.is_finite() && newton > low && newton < high {
                newton
            } else {
                (low + high) * 0.5
            };
        }

        Err(Error::InverseDidNotConverge {
            residual: last_residual,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn documented_raw_model_golden_values() {
        let poly3 = DistortionModel::Poly3 { k1: -0.02 };
        let p = poly3.terms();
        let r = 0.5_f64;
        let expected = r * (1.0 - f64::from(p[0]) + f64::from(p[0]) * r * r);
        assert!((expected - 0.5075).abs() < 1e-8);

        let poly5 = DistortionModel::Poly5 { k1: 0.1, k2: -0.02 };
        let p = poly5.terms();
        let r2 = r * r;
        let expected = r * (1.0 + f64::from(p[0]) * r2 + f64::from(p[1]) * r2 * r2);
        assert!((expected - 0.511875).abs() < 1e-8);

        let ptlens = DistortionModel::Ptlens {
            a: 0.01,
            b: -0.02,
            c: 0.03,
        };
        let p = ptlens.terms();
        let expected = r
            * (f64::from(p[0]) * r * r * r + f64::from(p[1]) * r * r + f64::from(p[2]) * r + 1.0
                - f64::from(p[0])
                - f64::from(p[1])
                - f64::from(p[2]));
        assert!((expected - 0.495625).abs() < 1e-8);
    }

    #[test]
    fn every_prepared_model_round_trips() {
        let models = [
            DistortionModel::None,
            DistortionModel::Poly3 { k1: -0.03 },
            DistortionModel::Poly5 {
                k1: -0.025,
                k2: 0.003,
            },
            DistortionModel::Ptlens {
                a: 0.002,
                b: -0.015,
                c: 0.005,
            },
        ];
        for raw in models {
            let model = PreparedDistortion::from_raw(raw, 12.0, 2.0, 4.0 / 3.0).unwrap();
            for (x, y) in [(0.0, 0.0), (0.1, -0.2), (0.35, 0.25)] {
                let distorted = model.distort(x, y).unwrap();
                let corrected = model.undistort(distorted.0, distorted.1).unwrap();
                assert!((corrected.0 - x).abs() < 2e-12, "{raw:?} {corrected:?}");
                assert!((corrected.1 - y).abs() < 2e-12, "{raw:?} {corrected:?}");
            }
        }
    }

    #[test]
    fn inverse_rejects_non_monotonic_branch() {
        let model = PreparedDistortion::Poly3 { k1: -10.0 };
        assert_eq!(
            model.undistort(1.0, 0.0).unwrap_err(),
            Error::NonInvertibleDistortion
        );
    }

    #[test]
    fn inverse_rejects_derivative_dip_between_positive_endpoints() {
        let model = PreparedDistortion::Poly5 {
            k1: -10.0,
            k2: 10.0,
        };
        assert!(model.derivative(0.0) > 0.0);
        assert!(model.derivative(1.0) > 0.0);
        assert_eq!(
            model.undistort(1.0, 0.0).unwrap_err(),
            Error::NonInvertibleDistortion
        );
    }
}

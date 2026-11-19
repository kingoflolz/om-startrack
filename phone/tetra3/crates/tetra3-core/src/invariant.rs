//! Canonical four-star tetra3 pattern invariants.

use core::fmt;

use crate::math::UnitVec3;

const MIN_EDGE_RAD: f64 = 1.0e-9;

/// Invalid invariant or degenerate four-ray pattern.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum InvariantError {
    NonFinite,
    CoincidentRays,
    UnsortedRatios,
    RatioOutOfRange,
    InvalidLargestEdge,
}

impl fmt::Display for InvariantError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NonFinite => f.write_str("non-finite tetra3 invariant"),
            Self::CoincidentRays => f.write_str("tetra3 pattern contains coincident rays"),
            Self::UnsortedRatios => f.write_str("tetra3 invariant ratios are not sorted"),
            Self::RatioOutOfRange => f.write_str("tetra3 invariant ratio is outside [0, 1]"),
            Self::InvalidLargestEdge => f.write_str("invalid tetra3 largest edge"),
        }
    }
}

impl std::error::Error for InvariantError {}

/// The five smaller angular separations divided by the largest of all six.
///
/// Sorting makes the key stable under all 24 vertex permutations. Vertex
/// correspondence is intentionally *not* encoded; solvers must test those 24
/// correspondences when checking a candidate.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Invariant {
    ratios: [f64; 5],
    largest_edge_rad: f64,
}

impl Invariant {
    pub fn from_rays(rays: [UnitVec3; 4]) -> Result<Self, InvariantError> {
        let mut edges = [0.0_f64; 6];
        let mut edge_index = 0;
        for left in 0..4 {
            for right in (left + 1)..4 {
                let edge = rays[left].angle_to(rays[right]);
                if !edge.is_finite() {
                    return Err(InvariantError::NonFinite);
                }
                if edge <= MIN_EDGE_RAD {
                    return Err(InvariantError::CoincidentRays);
                }
                edges[edge_index] = edge;
                edge_index += 1;
            }
        }
        edges.sort_unstable_by(f64::total_cmp);
        let largest = edges[5];
        if !largest.is_finite() || largest <= MIN_EDGE_RAD || largest > core::f64::consts::PI {
            return Err(InvariantError::InvalidLargestEdge);
        }
        let mut ratios = [0.0; 5];
        for index in 0..5 {
            ratios[index] = edges[index] / largest;
        }
        Ok(Self {
            ratios,
            largest_edge_rad: largest,
        })
    }

    pub fn from_parts(ratios: [f64; 5], largest_edge_rad: f64) -> Result<Self, InvariantError> {
        if !largest_edge_rad.is_finite() {
            return Err(InvariantError::NonFinite);
        }
        if largest_edge_rad <= MIN_EDGE_RAD || largest_edge_rad > core::f64::consts::PI {
            return Err(InvariantError::InvalidLargestEdge);
        }
        let mut previous = 0.0;
        for (index, ratio) in ratios.iter().copied().enumerate() {
            if !ratio.is_finite() {
                return Err(InvariantError::NonFinite);
            }
            if !(0.0..=1.0).contains(&ratio) {
                return Err(InvariantError::RatioOutOfRange);
            }
            if index > 0 && ratio < previous {
                return Err(InvariantError::UnsortedRatios);
            }
            previous = ratio;
        }
        Ok(Self {
            ratios,
            largest_edge_rad,
        })
    }

    #[must_use]
    pub const fn ratios(self) -> [f64; 5] {
        self.ratios
    }

    #[must_use]
    pub const fn largest_edge_rad(self) -> f64 {
        self.largest_edge_rad
    }

    /// Largest per-component ratio difference. This exact f64 check should be
    /// applied after any approximate/quantized flash lookup.
    #[must_use]
    pub fn max_ratio_delta(self, other: Self) -> f64 {
        let mut maximum = 0.0_f64;
        for index in 0..5 {
            maximum = maximum.max((self.ratios[index] - other.ratios[index]).abs());
        }
        maximum
    }

    /// Quantize ratios using the shared `.t3ix` contract:
    /// `min(floor(ratio * 256), 255)`.
    #[must_use]
    pub fn quantized_q8(self) -> [u8; 5] {
        self.ratios.map(|ratio| {
            let scaled = (ratio.clamp(0.0, 1.0) * 256.0).floor();
            scaled.min(255.0) as u8
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::math::UnitVec3;

    const PERMUTATIONS: [[usize; 4]; 24] = [
        [0, 1, 2, 3],
        [0, 1, 3, 2],
        [0, 2, 1, 3],
        [0, 2, 3, 1],
        [0, 3, 1, 2],
        [0, 3, 2, 1],
        [1, 0, 2, 3],
        [1, 0, 3, 2],
        [1, 2, 0, 3],
        [1, 2, 3, 0],
        [1, 3, 0, 2],
        [1, 3, 2, 0],
        [2, 0, 1, 3],
        [2, 0, 3, 1],
        [2, 1, 0, 3],
        [2, 1, 3, 0],
        [2, 3, 0, 1],
        [2, 3, 1, 0],
        [3, 0, 1, 2],
        [3, 0, 2, 1],
        [3, 1, 0, 2],
        [3, 1, 2, 0],
        [3, 2, 0, 1],
        [3, 2, 1, 0],
    ];

    #[test]
    fn invariant_is_stable_under_all_vertex_permutations() {
        let rays = [
            UnitVec3::new(-0.25, -0.18, 1.0).expect("valid ray"),
            UnitVec3::new(0.31, -0.11, 1.0).expect("valid ray"),
            UnitVec3::new(-0.08, 0.37, 1.0).expect("valid ray"),
            UnitVec3::new(0.29, 0.22, 1.0).expect("valid ray"),
        ];
        let expected = Invariant::from_rays(rays).expect("valid invariant");
        for permutation in PERMUTATIONS {
            let permuted = permutation.map(|index| rays[index]);
            let actual = Invariant::from_rays(permuted).expect("valid invariant");
            assert_eq!(actual, expected);
        }
    }

    #[test]
    fn q8_values_are_bounded_and_monotonic() {
        let invariant =
            Invariant::from_parts([0.0, 0.1, 0.5, 0.9, 1.0], 0.2).expect("valid invariant");
        assert_eq!(invariant.quantized_q8(), [0, 25, 128, 230, 255]);
    }

    #[test]
    fn matches_shared_golden_quad_contract() {
        let rays = [
            UnitVec3::new(-0.08, -0.04, 1.0).expect("valid ray"),
            UnitVec3::new(0.03, -0.06, 1.0).expect("valid ray"),
            UnitVec3::new(0.07, 0.05, 1.0).expect("valid ray"),
            UnitVec3::new(-0.02, 0.09, 1.0).expect("valid ray"),
        ];
        let invariant = Invariant::from_rays(rays).expect("valid golden invariant");
        let expected = [
            0.562_455_916_022_761,
            0.639_041_429_295_630,
            0.669_176_077_598_340,
            0.817_928_399_580_266,
            0.904_136_407_141_932,
        ];
        for (actual, expected) in invariant.ratios().iter().zip(expected) {
            assert!((actual - expected).abs() < 1.0e-12);
        }
        assert!((invariant.largest_edge_rad() - 0.174_479_934_103_946).abs() < 1.0e-12);
        assert_eq!(invariant.quantized_q8(), [143, 163, 171, 209, 231]);
    }

    #[test]
    fn rejects_coincident_rays_and_bad_parts() {
        assert_eq!(
            Invariant::from_rays([UnitVec3::X, UnitVec3::X, UnitVec3::Y, UnitVec3::Z]),
            Err(InvariantError::CoincidentRays)
        );
        assert_eq!(
            Invariant::from_parts([0.1, 0.3, 0.2, 0.8, 0.9], 0.4),
            Err(InvariantError::UnsortedRatios)
        );
    }
}

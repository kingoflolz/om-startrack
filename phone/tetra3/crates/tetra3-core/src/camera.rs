//! Bounded conversion from pixel detections to solver observations.

use std::{fmt, vec::Vec};

use crate::{Observation, UnitVec3, solver::brightest_indices};

/// Sub-pixel centroid in the usual image convention: X right, Y down.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PixelCentroid {
    pub x: f64,
    pub y: f64,
}

/// A measured centroid plus solver weight.
#[derive(Clone, Copy, Debug)]
pub struct PixelObservation {
    pub centroid: PixelCentroid,
    pub flux: f32,
}

/// Error raised before or during bounded pixel projection.
#[derive(Debug)]
pub enum BoundedProjectionError<E> {
    /// Flux must be finite and strictly positive, matching the solver input
    /// contract. The index refers to the original caller slice.
    InvalidObservation {
        index: usize,
    },
    Projection(E),
}

impl<E: fmt::Display> fmt::Display for BoundedProjectionError<E> {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidObservation { index } => {
                write!(formatter, "invalid observation at index {index}")
            }
            Self::Projection(error) => error.fmt(formatter),
        }
    }
}

impl<E: std::error::Error + 'static> std::error::Error for BoundedProjectionError<E> {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::InvalidObservation { .. } => None,
            Self::Projection(error) => Some(error),
        }
    }
}

/// Select and project only the brightest `maximum` measurements.
///
/// The function scans all fluxes to enforce the solver's input contract and
/// find the deterministic top-K, but allocates and invokes `pixel_to_ray` only
/// O(`maximum`) times. Equal fluxes retain their original slice order. Pixel
/// coordinates outside the selected top-K are intentionally not projected or
/// lens-validated.
pub fn project_observations_bounded<E>(
    pixels: &[PixelObservation],
    maximum: usize,
    mut pixel_to_ray: impl FnMut(PixelCentroid) -> Result<UnitVec3, E>,
) -> Result<(Vec<Observation>, Vec<usize>), BoundedProjectionError<E>> {
    let source_indices = brightest_indices(pixels.len(), maximum, |index| pixels[index].flux)
        .map_err(|index| BoundedProjectionError::InvalidObservation { index })?;
    let mut observations = Vec::with_capacity(source_indices.len());
    for source_index in source_indices.iter().copied() {
        let pixel = pixels[source_index];
        observations.push(Observation {
            ray: pixel_to_ray(pixel.centroid).map_err(BoundedProjectionError::Projection)?,
            flux: pixel.flux,
        });
    }
    Ok((observations, source_indices))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn oversized_projection_is_hard_bounded_and_ties_are_deterministic() {
        const INPUTS: usize = 100_000;
        const MAXIMUM: usize = 8;
        let pixels: Vec<_> = (0..INPUTS)
            .map(|index| PixelObservation {
                centroid: PixelCentroid {
                    x: index as f64,
                    y: 0.0,
                },
                // Every input ties, so the earliest eight must be retained.
                flux: 1.0,
            })
            .collect();
        let mut calls = 0;
        let (observations, source_indices) = project_observations_bounded(&pixels, MAXIMUM, |_| {
            calls += 1;
            Ok::<_, std::convert::Infallible>(UnitVec3::Z)
        })
        .expect("bounded projection");

        assert_eq!(calls, MAXIMUM);
        assert_eq!(observations.len(), MAXIMUM);
        assert_eq!(source_indices, (0..MAXIMUM).collect::<Vec<_>>());
        assert!(
            observations
                .iter()
                .all(|observation| observation.flux == 1.0)
        );
    }
}

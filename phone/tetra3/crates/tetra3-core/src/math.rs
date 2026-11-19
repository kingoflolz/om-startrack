//! Small f64 geometry types used by the solver.

use core::fmt;
use core::ops::{Add, Div, Mul, Sub};

const NORMAL_EPSILON: f64 = 1.0e-15;

/// Failure from checked vector, matrix, or attitude operations.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MathError {
    /// An input contained NaN or infinity.
    NonFinite,
    /// A vector or quaternion was too small to normalize.
    ZeroNorm,
    /// The attitude inputs did not constrain a usable rotation.
    DegenerateAttitude,
}

impl fmt::Display for MathError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NonFinite => f.write_str("non-finite geometry value"),
            Self::ZeroNorm => f.write_str("cannot normalize a zero-length value"),
            Self::DegenerateAttitude => f.write_str("attitude observations are degenerate"),
        }
    }
}

impl std::error::Error for MathError {}

/// An ordinary three-dimensional vector.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Vec3 {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

impl Vec3 {
    #[must_use]
    pub const fn new(x: f64, y: f64, z: f64) -> Self {
        Self { x, y, z }
    }

    #[must_use]
    pub(crate) fn is_finite(self) -> bool {
        self.x.is_finite() && self.y.is_finite() && self.z.is_finite()
    }

    #[must_use]
    pub fn dot(self, other: Self) -> f64 {
        self.x * other.x + self.y * other.y + self.z * other.z
    }

    #[must_use]
    pub fn cross(self, other: Self) -> Self {
        Self::new(
            self.y * other.z - self.z * other.y,
            self.z * other.x - self.x * other.z,
            self.x * other.y - self.y * other.x,
        )
    }

    #[must_use]
    pub fn norm(self) -> f64 {
        self.dot(self).sqrt()
    }

    pub fn normalized(self) -> Result<UnitVec3, MathError> {
        UnitVec3::try_from_vec(self)
    }
}

impl Add for Vec3 {
    type Output = Self;

    fn add(self, rhs: Self) -> Self::Output {
        Self::new(self.x + rhs.x, self.y + rhs.y, self.z + rhs.z)
    }
}

impl Sub for Vec3 {
    type Output = Self;

    fn sub(self, rhs: Self) -> Self::Output {
        Self::new(self.x - rhs.x, self.y - rhs.y, self.z - rhs.z)
    }
}

impl Mul<f64> for Vec3 {
    type Output = Self;

    fn mul(self, rhs: f64) -> Self::Output {
        Self::new(self.x * rhs, self.y * rhs, self.z * rhs)
    }
}

impl Div<f64> for Vec3 {
    type Output = Self;

    fn div(self, rhs: f64) -> Self::Output {
        Self::new(self.x / rhs, self.y / rhs, self.z / rhs)
    }
}

/// A finite vector normalized to unit length at construction.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct UnitVec3(Vec3);

impl UnitVec3 {
    pub const X: Self = Self(Vec3::new(1.0, 0.0, 0.0));
    pub const Y: Self = Self(Vec3::new(0.0, 1.0, 0.0));
    pub const Z: Self = Self(Vec3::new(0.0, 0.0, 1.0));

    pub fn new(x: f64, y: f64, z: f64) -> Result<Self, MathError> {
        Self::try_from_vec(Vec3::new(x, y, z))
    }

    fn try_from_vec(value: Vec3) -> Result<Self, MathError> {
        if !value.is_finite() {
            return Err(MathError::NonFinite);
        }
        let norm = value.norm();
        if !norm.is_finite() || norm <= NORMAL_EPSILON {
            return Err(MathError::ZeroNorm);
        }
        Ok(Self(value / norm))
    }

    #[must_use]
    pub const fn as_vec(self) -> Vec3 {
        self.0
    }

    #[must_use]
    pub fn dot(self, other: Self) -> f64 {
        self.0.dot(other.0)
    }

    #[must_use]
    pub fn cross(self, other: Self) -> Vec3 {
        self.0.cross(other.0)
    }

    /// Stable great-circle distance in radians.
    #[must_use]
    pub fn angle_to(self, other: Self) -> f64 {
        let sine = self.cross(other).norm();
        let cosine = self.dot(other).clamp(-1.0, 1.0);
        sine.atan2(cosine)
    }
}

/// Unit quaternion using scalar-first `(w, x, y, z)` storage.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Quat {
    w: f64,
    x: f64,
    y: f64,
    z: f64,
}

impl Quat {
    #[must_use]
    pub const fn identity() -> Self {
        Self {
            w: 1.0,
            x: 0.0,
            y: 0.0,
            z: 0.0,
        }
    }

    pub fn new(w: f64, x: f64, y: f64, z: f64) -> Result<Self, MathError> {
        if !(w.is_finite() && x.is_finite() && y.is_finite() && z.is_finite()) {
            return Err(MathError::NonFinite);
        }
        let norm = (w * w + x * x + y * y + z * z).sqrt();
        if norm <= NORMAL_EPSILON || !norm.is_finite() {
            return Err(MathError::ZeroNorm);
        }
        let mut result = Self {
            w: w / norm,
            x: x / norm,
            y: y / norm,
            z: z / norm,
        };
        // q and -q encode the same rotation. A canonical sign makes results
        // deterministic across eigensolver and platform details.
        if result.w < 0.0
            || (result.w == 0.0
                && (result.x < 0.0
                    || (result.x == 0.0
                        && (result.y < 0.0 || (result.y == 0.0 && result.z < 0.0)))))
        {
            result.w = -result.w;
            result.x = -result.x;
            result.y = -result.y;
            result.z = -result.z;
        }
        Ok(result)
    }

    pub fn from_axis_angle(axis: UnitVec3, angle_rad: f64) -> Result<Self, MathError> {
        if !angle_rad.is_finite() {
            return Err(MathError::NonFinite);
        }
        let half = 0.5 * angle_rad;
        let sine = half.sin();
        let axis = axis.as_vec();
        Self::new(half.cos(), axis.x * sine, axis.y * sine, axis.z * sine)
    }

    #[must_use]
    pub const fn components(self) -> [f64; 4] {
        [self.w, self.x, self.y, self.z]
    }

    pub fn compose(self, rhs: Self) -> Result<Self, MathError> {
        let w = self.w * rhs.w - self.x * rhs.x - self.y * rhs.y - self.z * rhs.z;
        let x = self.w * rhs.x + self.x * rhs.w + self.y * rhs.z - self.z * rhs.y;
        let y = self.w * rhs.y - self.x * rhs.z + self.y * rhs.w + self.z * rhs.x;
        let z = self.w * rhs.z + self.x * rhs.y - self.y * rhs.x + self.z * rhs.w;
        Self::new(w, x, y, z)
    }

    #[must_use]
    fn rotate_vec(self, vector: Vec3) -> Vec3 {
        let (w, x, y, z) = (self.w, self.x, self.y, self.z);
        Vec3::new(
            (1.0 - 2.0 * (y * y + z * z)) * vector.x
                + 2.0 * (x * y - z * w) * vector.y
                + 2.0 * (x * z + y * w) * vector.z,
            2.0 * (x * y + z * w) * vector.x
                + (1.0 - 2.0 * (x * x + z * z)) * vector.y
                + 2.0 * (y * z - x * w) * vector.z,
            2.0 * (x * z - y * w) * vector.x
                + 2.0 * (y * z + x * w) * vector.y
                + (1.0 - 2.0 * (x * x + y * y)) * vector.z,
        )
    }

    /// Rotate a unit vector while preserving its unit-length invariant.
    ///
    /// Both operands are finite and normalized by construction, so the
    /// rotation cannot produce a non-finite or zero-length vector.
    #[must_use]
    pub fn rotate_unit(self, vector: UnitVec3) -> UnitVec3 {
        let rotated = self.rotate_vec(vector.as_vec());
        let norm = rotated.norm();
        debug_assert!(rotated.is_finite() && norm > NORMAL_EPSILON);
        UnitVec3(rotated / norm)
    }

    /// Smallest rotation angle between two orientations.
    #[must_use]
    pub fn angle_to(self, other: Self) -> f64 {
        let a = self.components();
        let b = other.components();
        let dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        let sign = if dot < 0.0 { -1.0 } else { 1.0 };
        let difference = (a
            .iter()
            .zip(b)
            .map(|(left, right)| (left - sign * right) * (left - sign * right))
            .sum::<f64>())
        .sqrt();
        let sum = (a
            .iter()
            .zip(b)
            .map(|(left, right)| (left + sign * right) * (left + sign * right))
            .sum::<f64>())
        .sqrt();
        4.0 * difference.atan2(sum)
    }
}

/// One weighted Wahba observation: `world ~= camera_to_world * camera`.
#[derive(Clone, Copy, Debug)]
pub struct AttitudePair {
    pub camera: UnitVec3,
    pub world: UnitVec3,
    pub weight: f64,
}

/// Estimate `camera_to_world` with Davenport's q-method and a deterministic
/// Jacobi eigensolver. All accumulation and eigen work is f64.
pub fn estimate_attitude(pairs: &[AttitudePair]) -> Result<Quat, MathError> {
    if pairs.len() < 2 {
        return Err(MathError::DegenerateAttitude);
    }

    let mut b = [[0.0_f64; 3]; 3];
    let mut total_weight = 0.0;
    for pair in pairs {
        if !pair.weight.is_finite() || pair.weight <= 0.0 {
            return Err(if pair.weight.is_finite() {
                MathError::DegenerateAttitude
            } else {
                MathError::NonFinite
            });
        }
        let r = pair.camera.as_vec();
        let w = pair.world.as_vec();
        let weight = pair.weight;
        // Cross covariance B = sum(world * camera^T).
        let camera = [r.x, r.y, r.z];
        let world = [w.x, w.y, w.z];
        for (row, world_component) in world.into_iter().enumerate() {
            for (column, camera_component) in camera.into_iter().enumerate() {
                b[row][column] += weight * world_component * camera_component;
            }
        }
        total_weight += weight;
    }
    if !total_weight.is_finite() {
        return Err(MathError::DegenerateAttitude);
    }
    // At least one correspondence pair must span two directions in both
    // frames. Identical and antipodal-only rays leave roll unconstrained and
    // produce a repeated largest Davenport eigenvalue.
    let mut constrained = false;
    for left in 0..pairs.len() {
        for right in (left + 1)..pairs.len() {
            if pairs[left].camera.cross(pairs[right].camera).norm() > 1.0e-10
                && pairs[left].world.cross(pairs[right].world).norm() > 1.0e-10
            {
                constrained = true;
                break;
            }
        }
        if constrained {
            break;
        }
    }
    if !constrained {
        return Err(MathError::DegenerateAttitude);
    }

    let sigma = b[0][0] + b[1][1] + b[2][2];
    let z = [b[1][2] - b[2][1], b[2][0] - b[0][2], b[0][1] - b[1][0]];
    // Vector-first quaternion convention used by the Davenport K matrix.
    let mut k = [[0.0_f64; 4]; 4];
    for row in 0..3 {
        for column in 0..3 {
            k[row][column] = b[row][column] + b[column][row];
            if row == column {
                k[row][column] -= sigma;
            }
        }
        k[row][3] = z[row];
        k[3][row] = z[row];
    }
    k[3][3] = sigma;

    let eigenvector = largest_symmetric_eigenvector(k)?;
    // With B = world * camera^T the vector-first Davenport eigenvector is the
    // passive world-to-camera quaternion. Conjugate it to the public active
    // camera-to-world convention.
    Quat::new(
        eigenvector[3],
        -eigenvector[0],
        -eigenvector[1],
        -eigenvector[2],
    )
}

#[allow(clippy::needless_range_loop)]
fn largest_symmetric_eigenvector(mut matrix: [[f64; 4]; 4]) -> Result<[f64; 4], MathError> {
    if !matrix.iter().flatten().all(|value| value.is_finite()) {
        return Err(MathError::NonFinite);
    }
    let mut vectors = [[0.0_f64; 4]; 4];
    for (index, row) in vectors.iter_mut().enumerate() {
        row[index] = 1.0;
    }

    // Fixed maximum bounds latency. Jacobi converges far sooner for a 4x4
    // Davenport matrix; the relative threshold prevents wasting small-FOV f64
    // precision.
    for _ in 0..64 {
        let mut p = 0;
        let mut q = 1;
        let mut largest = matrix[p][q].abs();
        for row in 0..4 {
            for column in (row + 1)..4 {
                let value = matrix[row][column].abs();
                if value > largest {
                    largest = value;
                    p = row;
                    q = column;
                }
            }
        }
        let diagonal_scale = matrix
            .iter()
            .enumerate()
            .fold(0.0_f64, |scale, (i, row)| scale.max(row[i].abs()));
        if largest <= 1.0e-14 * diagonal_scale {
            break;
        }

        let app = matrix[p][p];
        let aqq = matrix[q][q];
        let apq = matrix[p][q];
        let angle = 0.5 * (2.0 * apq).atan2(aqq - app);
        let cosine = angle.cos();
        let sine = angle.sin();

        for row in 0..4 {
            if row != p && row != q {
                let arp = matrix[row][p];
                let arq = matrix[row][q];
                matrix[row][p] = cosine * arp - sine * arq;
                matrix[p][row] = matrix[row][p];
                matrix[row][q] = sine * arp + cosine * arq;
                matrix[q][row] = matrix[row][q];
            }
        }
        matrix[p][p] = cosine * cosine * app - 2.0 * sine * cosine * apq + sine * sine * aqq;
        matrix[q][q] = sine * sine * app + 2.0 * sine * cosine * apq + cosine * cosine * aqq;
        matrix[p][q] = 0.0;
        matrix[q][p] = 0.0;

        for row in &mut vectors {
            let vrp = row[p];
            let vrq = row[q];
            row[p] = cosine * vrp - sine * vrq;
            row[q] = sine * vrp + cosine * vrq;
        }
    }

    let mut largest_index = 0;
    for index in 1..4 {
        if matrix[index][index] > matrix[largest_index][largest_index] {
            largest_index = index;
        }
    }
    let vector = vectors.map(|row| row[largest_index]);
    let norm = vector.iter().map(|value| value * value).sum::<f64>().sqrt();
    if !norm.is_finite() || norm <= NORMAL_EPSILON {
        return Err(MathError::DegenerateAttitude);
    }
    Ok(vector.map(|value| value / norm))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn close(left: UnitVec3, right: UnitVec3, tolerance: f64) {
        assert!(left.angle_to(right) < tolerance, "{left:?} != {right:?}");
    }

    #[test]
    fn quaternion_rotation_is_active() {
        let rotation = Quat::from_axis_angle(UnitVec3::Z, core::f64::consts::FRAC_PI_2)
            .expect("valid quaternion");
        close(rotation.rotate_unit(UnitVec3::X), UnitVec3::Y, 1.0e-12);
        assert!((rotation.rotate_vec(UnitVec3::X.as_vec()).norm() - 1.0).abs() < 1.0e-12);
    }

    #[test]
    fn davenport_recovers_known_camera_to_world_rotation() {
        let expected =
            Quat::from_axis_angle(UnitVec3::new(0.3, -0.7, 0.4).expect("unit axis"), 0.71)
                .expect("valid quaternion");
        let cameras = [
            UnitVec3::new(-0.3, -0.2, 1.0).expect("unit ray"),
            UnitVec3::new(0.25, -0.1, 1.0).expect("unit ray"),
            UnitVec3::new(-0.15, 0.31, 1.0).expect("unit ray"),
            UnitVec3::new(0.38, 0.22, 1.0).expect("unit ray"),
        ];
        let pairs: std::vec::Vec<_> = cameras
            .iter()
            .map(|camera| AttitudePair {
                camera: *camera,
                world: expected.rotate_unit(*camera),
                weight: 1.0,
            })
            .collect();
        let actual = estimate_attitude(&pairs).expect("well-conditioned attitude");
        assert!(actual.angle_to(expected) < 1.0e-10, "{actual:?}");
    }

    #[test]
    fn davenport_is_invariant_to_uniform_weight_scale() {
        let expected =
            Quat::from_axis_angle(UnitVec3::new(0.3, -0.7, 0.4).expect("unit axis"), 0.71)
                .expect("valid quaternion");
        let cameras = [
            UnitVec3::new(-0.3, -0.2, 1.0).expect("unit ray"),
            UnitVec3::new(0.25, -0.1, 1.0).expect("unit ray"),
            UnitVec3::new(-0.15, 0.31, 1.0).expect("unit ray"),
            UnitVec3::new(0.38, 0.22, 1.0).expect("unit ray"),
        ];
        let solve = |weight| {
            let pairs: std::vec::Vec<_> = cameras
                .iter()
                .map(|camera| AttitudePair {
                    camera: *camera,
                    world: expected.rotate_unit(*camera),
                    weight,
                })
                .collect();
            estimate_attitude(&pairs).expect("well-conditioned attitude")
        };

        let ordinary = solve(1.0);
        let tiny = solve(1.0e-20);
        assert!(ordinary.angle_to(expected) < 1.0e-10);
        assert!(tiny.angle_to(expected) < 1.0e-10);
        assert!(tiny.angle_to(ordinary) < 1.0e-10);
    }

    #[test]
    fn quaternion_angle_preserves_tiny_rotations() {
        for angle in [1.0e-8, 1.0e-10, 1.0e-14] {
            let rotation = Quat::from_axis_angle(UnitVec3::X, angle).expect("valid rotation");
            let measured = rotation.angle_to(Quat::identity());
            assert!(
                (measured - angle).abs() <= angle * 1.0e-12,
                "angle {angle:e} was measured as {measured:e}"
            );
        }
    }

    #[test]
    fn golden_camera_to_world_quaternion_uses_rz_ry_rx_order() {
        let rx = Quat::from_axis_angle(UnitVec3::X, 10_f64.to_radians()).expect("rx");
        let ry = Quat::from_axis_angle(UnitVec3::Y, (-20_f64).to_radians()).expect("ry");
        let rz = Quat::from_axis_angle(UnitVec3::Z, 30_f64.to_radians()).expect("rz");
        let actual = rz
            .compose(ry)
            .and_then(|value| value.compose(rx))
            .expect("finite composition");
        let expected = Quat::new(
            0.943_714_364_147_489,
            0.127_679_440_695_781,
            -0.144_878_125_417_369,
            0.268_535_822_751_569,
        )
        .expect("golden quaternion");
        assert!(actual.angle_to(expected) < 1.0e-7);
    }

    #[test]
    fn rejects_non_finite_and_degenerate_inputs() {
        assert_eq!(UnitVec3::new(f64::NAN, 0.0, 1.0), Err(MathError::NonFinite));
        let pair = AttitudePair {
            camera: UnitVec3::Z,
            world: UnitVec3::X,
            weight: 1.0,
        };
        assert_eq!(
            estimate_attitude(&[pair, pair]),
            Err(MathError::DegenerateAttitude)
        );
        let antipodal = AttitudePair {
            camera: UnitVec3::new(0.0, 0.0, -1.0).expect("unit"),
            world: UnitVec3::new(-1.0, 0.0, 0.0).expect("unit"),
            weight: 1.0,
        };
        assert_eq!(
            estimate_attitude(&[pair, antipodal]),
            Err(MathError::DegenerateAttitude)
        );
    }
}

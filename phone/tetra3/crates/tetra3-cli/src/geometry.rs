use std::f64::consts::{PI, TAU};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Vec3 {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

impl Vec3 {
    pub const fn new(x: f64, y: f64, z: f64) -> Self {
        Self { x, y, z }
    }

    #[cfg(test)]
    pub fn from_radec_deg(ra_deg: f64, dec_deg: f64) -> Self {
        let ra = ra_deg.to_radians();
        let dec = dec_deg.to_radians();
        let cos_dec = dec.cos();
        Self::new(cos_dec * ra.cos(), cos_dec * ra.sin(), dec.sin())
    }

    pub fn to_radec_deg(self) -> (f64, f64) {
        let value = self.normalized();
        let mut ra = value.y.atan2(value.x);
        if ra < 0.0 {
            ra += TAU;
        }
        (
            ra.to_degrees(),
            value.z.clamp(-1.0, 1.0).asin().to_degrees(),
        )
    }

    pub fn dot(self, rhs: Self) -> f64 {
        self.x * rhs.x + self.y * rhs.y + self.z * rhs.z
    }

    pub fn norm_squared(self) -> f64 {
        self.dot(self)
    }

    pub fn normalized(self) -> Self {
        let inv = self.norm_squared().sqrt().recip();
        Self::new(self.x * inv, self.y * inv, self.z * inv)
    }

    /// Numerically stable angular distance in radians.
    pub fn angle_to(self, rhs: Self) -> f64 {
        let a = self.normalized();
        let b = rhs.normalized();
        let cross_x = a.y * b.z - a.z * b.y;
        let cross_y = a.z * b.x - a.x * b.z;
        let cross_z = a.x * b.y - a.y * b.x;
        let sin = (cross_x * cross_x + cross_y * cross_y + cross_z * cross_z).sqrt();
        sin.atan2(a.dot(b))
    }
}

/// Propagate Gaia-style proper motion without requiring parallax or radial velocity.
///
/// Gaia's `pmra` is `d(alpha)/dt * cos(delta)`, in milliarcseconds per year.
/// The tangent displacement is applied in Cartesian space and renormalized. This
/// avoids the singularity of adding a divided RA delta close to either pole.
pub fn propagate_epoch(
    ra_deg: f64,
    dec_deg: f64,
    pmra_mas_per_year: f64,
    pmdec_mas_per_year: f64,
    years: f64,
) -> Vec3 {
    let ra = ra_deg.to_radians();
    let dec = dec_deg.to_radians();
    let cos_dec = dec.cos();
    let sin_dec = dec.sin();
    let p = Vec3::new(cos_dec * ra.cos(), cos_dec * ra.sin(), sin_dec);
    let east = Vec3::new(-ra.sin(), ra.cos(), 0.0);
    let north = Vec3::new(-sin_dec * ra.cos(), -sin_dec * ra.sin(), cos_dec);
    let mas_to_rad = PI / (180.0 * 3_600_000.0);
    let east_motion = pmra_mas_per_year * years * mas_to_rad;
    let north_motion = pmdec_mas_per_year * years * mas_to_rad;
    Vec3::new(
        p.x + east_motion * east.x + north_motion * north.x,
        p.y + east_motion * east.y + north_motion * north.y,
        p.z + east_motion * east.z + north_motion * north.z,
    )
    .normalized()
}

pub fn encode_octahedral(vector: Vec3) -> [u8; 6] {
    const MAX: f64 = 16_777_215.0;
    let vector = vector.normalized();
    let inv_l1 = (vector.x.abs() + vector.y.abs() + vector.z.abs()).recip();
    let mut x = vector.x * inv_l1;
    let mut y = vector.y * inv_l1;
    if vector.z < 0.0 {
        let old_x = x;
        x = (1.0 - y.abs()) * sign_not_zero(old_x);
        y = (1.0 - old_x.abs()) * sign_not_zero(y);
    }
    let x = (((x * 0.5 + 0.5) * MAX).round().clamp(0.0, MAX)) as u32;
    let y = (((y * 0.5 + 0.5) * MAX).round().clamp(0.0, MAX)) as u32;
    [
        x as u8,
        (x >> 8) as u8,
        (x >> 16) as u8,
        y as u8,
        (y >> 8) as u8,
        (y >> 16) as u8,
    ]
}

pub fn decode_octahedral(bytes: [u8; 6]) -> Vec3 {
    const MAX: f64 = 16_777_215.0;
    let x = u32::from(bytes[0]) | (u32::from(bytes[1]) << 8) | (u32::from(bytes[2]) << 16);
    let y = u32::from(bytes[3]) | (u32::from(bytes[4]) << 8) | (u32::from(bytes[5]) << 16);
    let mut x = (f64::from(x) / MAX) * 2.0 - 1.0;
    let mut y = (f64::from(y) / MAX) * 2.0 - 1.0;
    let z = 1.0 - x.abs() - y.abs();
    if z < 0.0 {
        let old_x = x;
        x = (1.0 - y.abs()) * sign_not_zero(old_x);
        y = (1.0 - old_x.abs()) * sign_not_zero(y);
    }
    Vec3::new(x, y, z).normalized()
}

fn sign_not_zero(value: f64) -> f64 {
    if value >= 0.0 { 1.0 } else { -1.0 }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn octahedral_round_trip_is_sub_arcsecond() {
        for (ra, dec) in [
            (0.0, 0.0),
            (12.3, -45.6),
            (359.99, 89.0),
            (181.0, -89.9),
            (0.0, -90.0),
        ] {
            let original = Vec3::from_radec_deg(ra, dec);
            let decoded = decode_octahedral(encode_octahedral(original));
            assert!(original.angle_to(decoded).to_degrees() * 3_600.0 < 0.2);
        }
    }

    #[test]
    fn propagation_uses_gaia_pmra_convention() {
        let moved = propagate_epoch(10.0, 60.0, 3_600_000.0, 0.0, 1.0);
        let (ra, dec) = moved.to_radec_deg();
        // The Cartesian tangent approximation is extremely close to a 2 degree
        // RA change at dec=60 for a one-degree great-circle displacement.
        assert!((ra - 11.998).abs() < 0.01, "{ra}");
        assert!((dec - 59.985).abs() < 0.02, "{dec}");
    }
}

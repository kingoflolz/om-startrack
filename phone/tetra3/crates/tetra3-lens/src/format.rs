use crate::profile::MAX_KNOTS_PER_PROFILE;
use crate::{
    CalibrationGeometry, CameraGeometry, CropRect, DistortionKnot, DistortionModel, Error,
    LensProfile, Orientation, ProfileId, Projection, Result,
};

pub const T3LF_MAGIC: [u8; 8] = *b"T3LF\r\n\x1a\n";
pub const T3LF_VERSION: u16 = 1;

const HEADER_SIZE: usize = 64;
const PROFILE_SIZE: usize = 72;
const KNOT_SIZE: usize = 20;
const MAX_FILE_SIZE: usize = 64 * 1024 * 1024;
const MAX_PROFILE_COUNT: u32 = u16::MAX as u32;
const CRC32C_TABLE: [u32; 256] = crc32c_table();

const HEADER_MAJOR: usize = 8;
const HEADER_MINOR: usize = 10;
const HEADER_LENGTH: usize = 12;
const HEADER_FLAGS: usize = 14;
const HEADER_FILE_LENGTH: usize = 16;
const HEADER_PROFILE_COUNT: usize = 20;
const HEADER_KNOT_COUNT: usize = 24;
const HEADER_ID_POOL_LENGTH: usize = 28;
const HEADER_PROFILE_OFFSET: usize = 32;
const HEADER_KNOT_OFFSET: usize = 36;
const HEADER_ID_POOL_OFFSET: usize = 40;
const HEADER_PAYLOAD_CRC: usize = 44;
const HEADER_CRC: usize = 48;
const HEADER_RESERVED: usize = 52;

/// A validated collection of exact-ID camera/lens profiles.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Database {
    profiles: Vec<LensProfile>,
}

impl Database {
    pub fn new(mut profiles: Vec<LensProfile>) -> Result<Self> {
        profiles.sort_unstable_by(|left, right| left.id().cmp(right.id()));
        for pair in profiles.windows(2) {
            if pair[0].id() == pair[1].id() {
                return Err(Error::DuplicateProfileId(pair[0].id().as_str().to_owned()));
            }
        }
        if profiles.len() > MAX_PROFILE_COUNT as usize {
            return Err(Error::TooManyProfiles(profiles.len() as u32));
        }
        Ok(Self { profiles })
    }

    #[must_use]
    pub fn profiles(&self) -> &[LensProfile] {
        &self.profiles
    }

    /// Exact, case-sensitive lookup. IDs are never normalized or fuzzy-matched.
    #[must_use]
    pub fn get(&self, id: &str) -> Option<&LensProfile> {
        self.profiles
            .binary_search_by(|profile| profile.id().as_str().cmp(id))
            .ok()
            .map(|index| &self.profiles[index])
    }

    pub fn require(&self, id: &str) -> Result<&LensProfile> {
        self.get(id)
            .ok_or_else(|| Error::ProfileNotFound(id.to_owned()))
    }

    /// Serialize canonical little-endian `.t3lf` v1 bytes.
    pub fn to_t3lf(&self) -> Result<Vec<u8>> {
        let profile_count = u32::try_from(self.profiles.len()).map_err(|_| Error::SizeOverflow)?;
        let knot_count_usize = self.profiles.iter().try_fold(0_usize, |sum, profile| {
            sum.checked_add(profile.knots().len())
                .ok_or(Error::SizeOverflow)
        })?;
        let knot_count = u32::try_from(knot_count_usize).map_err(|_| Error::SizeOverflow)?;
        let id_pool_length_usize = self.profiles.iter().try_fold(0_usize, |sum, profile| {
            sum.checked_add(profile.id().as_str().len())
                .ok_or(Error::SizeOverflow)
        })?;
        let id_pool_length =
            u32::try_from(id_pool_length_usize).map_err(|_| Error::SizeOverflow)?;

        let profile_offset = HEADER_SIZE;
        let profile_bytes = self
            .profiles
            .len()
            .checked_mul(PROFILE_SIZE)
            .ok_or(Error::SizeOverflow)?;
        let knot_offset = profile_offset
            .checked_add(profile_bytes)
            .ok_or(Error::SizeOverflow)?;
        let knot_bytes = knot_count_usize
            .checked_mul(KNOT_SIZE)
            .ok_or(Error::SizeOverflow)?;
        let id_pool_offset = knot_offset
            .checked_add(knot_bytes)
            .ok_or(Error::SizeOverflow)?;
        let file_length = id_pool_offset
            .checked_add(id_pool_length_usize)
            .ok_or(Error::SizeOverflow)?;
        if file_length > MAX_FILE_SIZE {
            return Err(Error::FileTooLarge(file_length));
        }

        let mut bytes = vec![0_u8; file_length];
        bytes[..8].copy_from_slice(&T3LF_MAGIC);
        write_u16(&mut bytes, HEADER_MAJOR, T3LF_VERSION);
        write_u16(&mut bytes, HEADER_MINOR, 0);
        write_u16(&mut bytes, HEADER_LENGTH, HEADER_SIZE as u16);
        write_u16(&mut bytes, HEADER_FLAGS, 0);
        write_u32(&mut bytes, HEADER_FILE_LENGTH, file_length as u32);
        write_u32(&mut bytes, HEADER_PROFILE_COUNT, profile_count);
        write_u32(&mut bytes, HEADER_KNOT_COUNT, knot_count);
        write_u32(&mut bytes, HEADER_ID_POOL_LENGTH, id_pool_length);
        write_u32(&mut bytes, HEADER_PROFILE_OFFSET, profile_offset as u32);
        write_u32(&mut bytes, HEADER_KNOT_OFFSET, knot_offset as u32);
        write_u32(&mut bytes, HEADER_ID_POOL_OFFSET, id_pool_offset as u32);

        let mut first_knot = 0_u32;
        let mut id_offset = 0_u32;
        for (profile_index, profile) in self.profiles.iter().enumerate() {
            let offset = profile_offset + profile_index * PROFILE_SIZE;
            let id = profile.id().as_str().as_bytes();
            let knot_len = u16::try_from(profile.knots().len()).map_err(|_| Error::SizeOverflow)?;
            let model_tag = profile.knots()[0].model().tag();
            let camera = profile.camera();
            let crop = camera.crop();
            let calibration = profile.calibration();

            write_u32(&mut bytes, offset, id_offset);
            write_u16(&mut bytes, offset + 4, id.len() as u16);
            bytes[offset + 6] = profile.projection() as u8;
            bytes[offset + 7] = model_tag;
            write_u32(&mut bytes, offset + 8, camera.calibration_width());
            write_u32(&mut bytes, offset + 12, camera.calibration_height());
            write_u32(&mut bytes, offset + 16, crop.x);
            write_u32(&mut bytes, offset + 20, crop.y);
            write_u32(&mut bytes, offset + 24, crop.width);
            write_u32(&mut bytes, offset + 28, crop.height);
            write_u32(&mut bytes, offset + 32, camera.encoded_width());
            write_u32(&mut bytes, offset + 36, camera.encoded_height());
            write_u32(&mut bytes, offset + 40, first_knot);
            write_u16(&mut bytes, offset + 44, knot_len);
            bytes[offset + 46] = camera.orientation() as u8;
            bytes[offset + 47] = 0;
            write_f32(&mut bytes, offset + 48, camera.crop_factor());
            write_f32(&mut bytes, offset + 52, calibration.crop_factor());
            write_f32(&mut bytes, offset + 56, calibration.aspect_ratio());
            write_f32(&mut bytes, offset + 60, calibration.center_x());
            write_f32(&mut bytes, offset + 64, calibration.center_y());
            write_u32(&mut bytes, offset + 68, 0);

            let id_start = id_pool_offset + id_offset as usize;
            bytes[id_start..id_start + id.len()].copy_from_slice(id);
            id_offset += id.len() as u32;

            for (local_index, knot) in profile.knots().iter().enumerate() {
                let knot_index = first_knot as usize + local_index;
                let knot_at = knot_offset + knot_index * KNOT_SIZE;
                let terms = knot.model().terms();
                write_f32(&mut bytes, knot_at, knot.focal_mm());
                write_f32(&mut bytes, knot_at + 4, knot.real_focal_mm());
                write_f32(&mut bytes, knot_at + 8, terms[0]);
                write_f32(&mut bytes, knot_at + 12, terms[1]);
                write_f32(&mut bytes, knot_at + 16, terms[2]);
            }
            first_knot += u32::from(knot_len);
        }

        let payload_crc = crc32c(&bytes[HEADER_SIZE..]);
        write_u32(&mut bytes, HEADER_PAYLOAD_CRC, payload_crc);
        let header_crc = header_crc(&bytes[..HEADER_SIZE]);
        write_u32(&mut bytes, HEADER_CRC, header_crc);
        Ok(bytes)
    }

    /// Parse and semantically validate canonical little-endian `.t3lf` v1 bytes.
    pub fn from_t3lf(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < HEADER_SIZE {
            return Err(Error::Truncated);
        }
        if bytes.len() > MAX_FILE_SIZE {
            return Err(Error::FileTooLarge(bytes.len()));
        }
        if bytes[..8] != T3LF_MAGIC {
            return Err(Error::BadMagic);
        }

        let stored_header_crc = read_u32(bytes, HEADER_CRC)?;
        let actual_header_crc = header_crc(&bytes[..HEADER_SIZE]);
        if stored_header_crc != actual_header_crc {
            return Err(Error::ChecksumMismatch {
                expected: stored_header_crc,
                actual: actual_header_crc,
            });
        }
        let major = read_u16(bytes, HEADER_MAJOR)?;
        if major != T3LF_VERSION {
            return Err(Error::UnsupportedVersion(major));
        }
        if read_u16(bytes, HEADER_MINOR)? != 0
            || read_u16(bytes, HEADER_LENGTH)? as usize != HEADER_SIZE
            || read_u16(bytes, HEADER_FLAGS)? != 0
            || bytes[HEADER_RESERVED..HEADER_SIZE]
                .iter()
                .any(|byte| *byte != 0)
        {
            return Err(Error::InvalidHeader);
        }

        let file_length = read_u32(bytes, HEADER_FILE_LENGTH)? as usize;
        if file_length != bytes.len() {
            return if file_length > bytes.len() {
                Err(Error::Truncated)
            } else {
                Err(Error::InvalidHeader)
            };
        }
        let profile_count_u32 = read_u32(bytes, HEADER_PROFILE_COUNT)?;
        if profile_count_u32 > MAX_PROFILE_COUNT {
            return Err(Error::TooManyProfiles(profile_count_u32));
        }
        let profile_count = profile_count_u32 as usize;
        let knot_count = read_u32(bytes, HEADER_KNOT_COUNT)? as usize;
        let id_pool_length = read_u32(bytes, HEADER_ID_POOL_LENGTH)? as usize;
        let profile_offset = read_u32(bytes, HEADER_PROFILE_OFFSET)? as usize;
        let knot_offset = read_u32(bytes, HEADER_KNOT_OFFSET)? as usize;
        let id_pool_offset = read_u32(bytes, HEADER_ID_POOL_OFFSET)? as usize;

        let expected_knot_offset = HEADER_SIZE
            .checked_add(
                profile_count
                    .checked_mul(PROFILE_SIZE)
                    .ok_or(Error::SizeOverflow)?,
            )
            .ok_or(Error::SizeOverflow)?;
        let expected_id_pool_offset = expected_knot_offset
            .checked_add(
                knot_count
                    .checked_mul(KNOT_SIZE)
                    .ok_or(Error::SizeOverflow)?,
            )
            .ok_or(Error::SizeOverflow)?;
        let expected_file_length = expected_id_pool_offset
            .checked_add(id_pool_length)
            .ok_or(Error::SizeOverflow)?;
        if profile_offset != HEADER_SIZE
            || knot_offset != expected_knot_offset
            || id_pool_offset != expected_id_pool_offset
            || file_length != expected_file_length
        {
            return Err(Error::InvalidDirectory);
        }

        let stored_payload_crc = read_u32(bytes, HEADER_PAYLOAD_CRC)?;
        let actual_payload_crc = crc32c(&bytes[HEADER_SIZE..]);
        if stored_payload_crc != actual_payload_crc {
            return Err(Error::ChecksumMismatch {
                expected: stored_payload_crc,
                actual: actual_payload_crc,
            });
        }

        let id_pool = &bytes[id_pool_offset..];
        let mut profiles = Vec::with_capacity(profile_count);
        let mut next_id_offset = 0_usize;
        let mut next_knot = 0_usize;
        for profile_index in 0..profile_count {
            let offset = profile_offset + profile_index * PROFILE_SIZE;
            let id_offset = read_u32(bytes, offset)? as usize;
            let id_length = read_u16(bytes, offset + 4)? as usize;
            let projection = Projection::from_tag(bytes[offset + 6])?;
            let model_tag = bytes[offset + 7];
            if model_tag == 4 {
                return Err(Error::UnsupportedAcm);
            }
            if model_tag > 3 {
                return Err(Error::UnsupportedDistortionModel(model_tag));
            }
            let first_knot = read_u32(bytes, offset + 40)? as usize;
            let knot_length = read_u16(bytes, offset + 44)? as usize;
            let orientation = Orientation::from_exif(bytes[offset + 46])?;
            if bytes[offset + 47] != 0 || read_u32(bytes, offset + 68)? != 0 {
                return Err(Error::InvalidRecord);
            }
            if id_offset != next_id_offset
                || first_knot != next_knot
                || knot_length == 0
                || knot_length > MAX_KNOTS_PER_PROFILE
            {
                return Err(Error::InvalidRecord);
            }
            let id_end = id_offset
                .checked_add(id_length)
                .ok_or(Error::InvalidRecord)?;
            if id_end > id_pool.len() {
                return Err(Error::InvalidRecord);
            }
            let id_text =
                std::str::from_utf8(&id_pool[id_offset..id_end]).map_err(|_| Error::InvalidUtf8)?;
            let id = ProfileId::new(id_text)?;
            if profiles
                .last()
                .is_some_and(|previous: &LensProfile| previous.id() >= &id)
            {
                return Err(Error::NonCanonicalProfileOrder);
            }

            let knot_end = first_knot
                .checked_add(knot_length)
                .ok_or(Error::InvalidRecord)?;
            if knot_end > knot_count {
                return Err(Error::InvalidRecord);
            }
            let mut knots = Vec::with_capacity(knot_length);
            for knot_index in first_knot..knot_end {
                let knot_at = knot_offset + knot_index * KNOT_SIZE;
                let terms = [
                    read_f32(bytes, knot_at + 8)?,
                    read_f32(bytes, knot_at + 12)?,
                    read_f32(bytes, knot_at + 16)?,
                ];
                validate_unused_terms(model_tag, terms)?;
                let model = DistortionModel::from_tag_and_terms(model_tag, terms)?;
                knots.push(DistortionKnot::new(
                    read_f32(bytes, knot_at)?,
                    read_f32(bytes, knot_at + 4)?,
                    model,
                )?);
            }

            let camera = CameraGeometry::new(
                read_u32(bytes, offset + 8)?,
                read_u32(bytes, offset + 12)?,
                CropRect::new(
                    read_u32(bytes, offset + 16)?,
                    read_u32(bytes, offset + 20)?,
                    read_u32(bytes, offset + 24)?,
                    read_u32(bytes, offset + 28)?,
                ),
                read_u32(bytes, offset + 32)?,
                read_u32(bytes, offset + 36)?,
                orientation,
                read_f32(bytes, offset + 48)?,
            )?;
            let calibration = CalibrationGeometry::new(
                read_f32(bytes, offset + 52)?,
                read_f32(bytes, offset + 56)?,
                read_f32(bytes, offset + 60)?,
                read_f32(bytes, offset + 64)?,
            )?;
            let profile = LensProfile::new(id, camera, calibration, projection, knots)?;
            profiles.push(profile);
            next_id_offset = id_end;
            next_knot = knot_end;
        }

        if next_id_offset != id_pool.len() || next_knot != knot_count {
            return Err(Error::InvalidDirectory);
        }
        // Already strictly sorted; avoid hiding a non-canonical file by sorting
        // again in `Database::new`.
        Ok(Self { profiles })
    }
}

fn validate_unused_terms(model_tag: u8, terms: [f32; 3]) -> Result<()> {
    let positive_zero = |value: f32| value.to_bits() == 0;
    let valid = match model_tag {
        0 => terms.into_iter().all(positive_zero),
        1 => positive_zero(terms[1]) && positive_zero(terms[2]),
        2 => positive_zero(terms[2]),
        3 => true,
        4 => return Err(Error::UnsupportedAcm),
        _ => return Err(Error::UnsupportedDistortionModel(model_tag)),
    };
    if valid {
        Ok(())
    } else {
        Err(Error::InvalidRecord)
    }
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16> {
    let value = bytes.get(offset..offset + 2).ok_or(Error::Truncated)?;
    Ok(u16::from_le_bytes([value[0], value[1]]))
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32> {
    let value = bytes.get(offset..offset + 4).ok_or(Error::Truncated)?;
    Ok(u32::from_le_bytes([value[0], value[1], value[2], value[3]]))
}

fn read_f32(bytes: &[u8], offset: usize) -> Result<f32> {
    Ok(f32::from_bits(read_u32(bytes, offset)?))
}

fn write_u16(bytes: &mut [u8], offset: usize, value: u16) {
    bytes[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}

fn write_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn write_f32(bytes: &mut [u8], offset: usize, value: f32) {
    write_u32(bytes, offset, value.to_bits());
}

fn header_crc(header: &[u8]) -> u32 {
    let mut copy = [0_u8; HEADER_SIZE];
    copy.copy_from_slice(header);
    copy[HEADER_CRC..HEADER_CRC + 4].fill(0);
    crc32c(&copy)
}

fn crc32c(bytes: &[u8]) -> u32 {
    let mut crc = !0_u32;
    for byte in bytes {
        let index = ((crc ^ u32::from(*byte)) & 0xff) as usize;
        crc = CRC32C_TABLE[index] ^ (crc >> 8);
    }
    !crc
}

const fn crc32c_table() -> [u32; 256] {
    let mut table = [0_u32; 256];
    let mut index = 0;
    while index < table.len() {
        let mut value = index as u32;
        let mut bit = 0;
        while bit < 8 {
            let mask = 0_u32.wrapping_sub(value & 1);
            value = (value >> 1) ^ (0x82f6_3b78 & mask);
            bit += 1;
        }
        table[index] = value;
        index += 1;
    }
    table
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_profile(id: &str) -> LensProfile {
        LensProfile::new(
            ProfileId::new(id).unwrap(),
            CameraGeometry::new(
                5184,
                3888,
                CropRect::new(0, 0, 5184, 3888),
                1024,
                768,
                Orientation::Normal,
                2.0,
            )
            .unwrap(),
            CalibrationGeometry::new(2.0, 4.0 / 3.0, 0.002, -0.001).unwrap(),
            Projection::Rectilinear,
            vec![
                DistortionKnot::new(
                    12.0,
                    11.8,
                    DistortionModel::Ptlens {
                        a: 0.001,
                        b: -0.018,
                        c: 0.004,
                    },
                )
                .unwrap(),
                DistortionKnot::new(
                    40.0,
                    39.5,
                    DistortionModel::Ptlens {
                        a: 0.0002,
                        b: -0.004,
                        c: 0.001,
                    },
                )
                .unwrap(),
            ],
        )
        .unwrap()
    }

    fn repair_checksums(bytes: &mut [u8]) {
        write_u32(bytes, HEADER_PAYLOAD_CRC, crc32c(&bytes[HEADER_SIZE..]));
        write_u32(bytes, HEADER_CRC, 0);
        write_u32(bytes, HEADER_CRC, header_crc(&bytes[..HEADER_SIZE]));
    }

    #[test]
    fn crc32c_matches_standard_check_value() {
        assert_eq!(crc32c(b"123456789"), 0xe306_9283);
    }

    #[test]
    fn deterministic_round_trip_and_exact_lookup() {
        let database = Database::new(vec![
            sample_profile("z.profile"),
            sample_profile("A.profile"),
        ])
        .unwrap();
        let first = database.to_t3lf().unwrap();
        let parsed = Database::from_t3lf(&first).unwrap();
        let second = parsed.to_t3lf().unwrap();
        assert_eq!(first, second);
        assert_eq!(parsed.profiles()[0].id().as_str(), "A.profile");
        assert!(parsed.get("A.profile").is_some());
        assert!(parsed.get("a.profile").is_none());
    }

    #[test]
    fn every_truncation_is_rejected() {
        let bytes = Database::new(vec![sample_profile("one")])
            .unwrap()
            .to_t3lf()
            .unwrap();
        for length in 0..bytes.len() {
            assert!(
                Database::from_t3lf(&bytes[..length]).is_err(),
                "length {length}"
            );
        }
    }

    #[test]
    fn payload_corruption_is_detected() {
        let mut bytes = Database::new(vec![sample_profile("one")])
            .unwrap()
            .to_t3lf()
            .unwrap();
        let last = bytes.len() - 1;
        bytes[last] ^= 0x20;
        assert!(matches!(
            Database::from_t3lf(&bytes),
            Err(Error::ChecksumMismatch { .. })
        ));
    }

    #[test]
    fn acm_tag_fails_closed_even_with_valid_checksums() {
        let mut bytes = Database::new(vec![sample_profile("one")])
            .unwrap()
            .to_t3lf()
            .unwrap();
        bytes[HEADER_SIZE + 7] = 4;
        repair_checksums(&mut bytes);
        assert_eq!(
            Database::from_t3lf(&bytes).unwrap_err(),
            Error::UnsupportedAcm
        );
    }

    #[test]
    fn nonzero_unused_coefficient_is_not_canonical() {
        let profile = LensProfile::new(
            ProfileId::new("poly3").unwrap(),
            sample_profile("unused").camera(),
            sample_profile("unused").calibration(),
            Projection::Rectilinear,
            vec![DistortionKnot::new(12.0, 12.0, DistortionModel::Poly3 { k1: 0.01 }).unwrap()],
        )
        .unwrap();
        let mut bytes = Database::new(vec![profile]).unwrap().to_t3lf().unwrap();
        let knot_offset = read_u32(&bytes, HEADER_KNOT_OFFSET).unwrap() as usize;
        write_f32(&mut bytes, knot_offset + 12, 0.1);
        repair_checksums(&mut bytes);
        assert_eq!(
            Database::from_t3lf(&bytes).unwrap_err(),
            Error::InvalidRecord
        );
    }

    #[test]
    fn semantic_corruption_is_rejected_after_checksum_repair() {
        let original = Database::new(vec![sample_profile("one")])
            .unwrap()
            .to_t3lf()
            .unwrap();

        let mut nan_focal = original.clone();
        let knot_offset = read_u32(&nan_focal, HEADER_KNOT_OFFSET).unwrap() as usize;
        write_u32(&mut nan_focal, knot_offset, f32::NAN.to_bits());
        repair_checksums(&mut nan_focal);
        assert_eq!(
            Database::from_t3lf(&nan_focal).unwrap_err(),
            Error::InvalidFocal
        );

        let mut crop_overflow = original.clone();
        write_u32(&mut crop_overflow, HEADER_SIZE + 16, 5_183);
        write_u32(&mut crop_overflow, HEADER_SIZE + 24, 2);
        repair_checksums(&mut crop_overflow);
        assert_eq!(
            Database::from_t3lf(&crop_overflow).unwrap_err(),
            Error::InvalidCrop
        );

        let mut descending_focals = original;
        let knot_offset = read_u32(&descending_focals, HEADER_KNOT_OFFSET).unwrap() as usize;
        write_f32(&mut descending_focals, knot_offset + KNOT_SIZE, 11.0);
        repair_checksums(&mut descending_focals);
        assert_eq!(
            Database::from_t3lf(&descending_focals).unwrap_err(),
            Error::NonIncreasingFocals
        );
    }

    #[test]
    fn duplicate_exact_ids_are_rejected() {
        let mut bytes = Database::new(vec![sample_profile("a"), sample_profile("b")])
            .unwrap()
            .to_t3lf()
            .unwrap();
        let id_pool_offset = read_u32(&bytes, HEADER_ID_POOL_OFFSET).unwrap() as usize;
        bytes[id_pool_offset + 1] = b'a';
        repair_checksums(&mut bytes);
        assert_eq!(
            Database::from_t3lf(&bytes).unwrap_err(),
            Error::NonCanonicalProfileOrder
        );
    }
}

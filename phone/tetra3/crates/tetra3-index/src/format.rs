pub use crate::crc::crc32c;

pub const MAGIC: [u8; 4] = *b"T3IX";
pub const VERSION_MAJOR: u16 = 1;
pub const VERSION_MINOR: u16 = 1;
pub(crate) const ENDIAN_MARKER: u32 = 0x0102_0304;

pub(crate) const HEADER_SIZE: usize = 64;
pub(crate) const SECTION_ENTRY_SIZE: usize = 32;
pub(crate) const LEVEL_DESCRIPTOR_SIZE: usize = 40;
pub const PATTERN_RECORD_SIZE: usize = 14;
pub const VERIFICATION_STAR_SIZE: usize = 8;
pub(crate) const PATTERN_STAR_SIZE: usize = 3;
pub(crate) const SECONDARY_ENTRY_SIZE: usize = 12;

pub(crate) const PRIMARY_BUCKET_COUNT: usize = 1 << 15;
pub(crate) const PRIMARY_DIRECTORY_COUNT: usize = PRIMARY_BUCKET_COUNT + 1;

pub(crate) const SECTION_COUNT: u32 = 7;
pub const MAX_LEVELS: u16 = 64;
pub(crate) const MAX_PATTERN_STARS: u32 = 1 << 20;
pub(crate) const MAX_VERIFICATION_STARS: u32 = 1 << 24;
pub(crate) const MAX_SECONDARY_ENTRIES: u32 = 1 << 24;
pub(crate) const MAX_PATTERNS: u32 = 1 << 28;
pub(crate) const MAX_SPATIAL_CELLS: u32 = 1 << 20;
pub const MAX_RATIO_TOLERANCE_Q8: u8 = 31;

pub(crate) const GLOBAL_LEVEL: u16 = u16::MAX;
pub(crate) const KNOWN_HEADER_FLAGS: u32 = 0;
pub(crate) const KNOWN_SECTION_FLAGS: u32 = 0;

pub(crate) const HEADER_CRC_OFFSET: usize = 60;

pub(crate) const PRIMARY_SECTION_INDEX: u32 = 4;
pub(crate) const SECONDARY_SECTION_INDEX: u32 = 5;
pub(crate) const PATTERNS_SECTION_INDEX: u32 = 6;

pub(crate) fn align8(value: usize) -> Option<usize> {
    value.checked_add(7).map(|value| value & !7)
}

pub(crate) const fn magnitude_bin(magnitude_q4: i8) -> usize {
    (magnitude_q4 as i16 + 128) as usize
}

pub(crate) fn cumulative_magnitude_histogram(mut histogram: [u32; 256]) -> [u32; 256] {
    for index in 1..histogram.len() {
        histogram[index] += histogram[index - 1];
    }
    histogram
}

pub(crate) fn read_u16(bytes: &[u8], offset: usize) -> Option<u16> {
    let value = bytes.get(offset..offset.checked_add(2)?)?;
    Some(u16::from_le_bytes([value[0], value[1]]))
}

pub(crate) fn read_u24_signed(bytes: &[u8], offset: usize) -> Option<i32> {
    let value = bytes.get(offset..offset.checked_add(3)?)?;
    let raw = u32::from(value[0]) | (u32::from(value[1]) << 8) | (u32::from(value[2]) << 16);
    Some(if raw & 0x0080_0000 != 0 {
        (raw | 0xff00_0000) as i32
    } else {
        raw as i32
    })
}

pub(crate) fn read_u24(bytes: &[u8], offset: usize) -> Option<u32> {
    let value = bytes.get(offset..offset.checked_add(3)?)?;
    Some(u32::from(value[0]) | (u32::from(value[1]) << 8) | (u32::from(value[2]) << 16))
}

pub(crate) fn read_u32(bytes: &[u8], offset: usize) -> Option<u32> {
    let value = bytes.get(offset..offset.checked_add(4)?)?;
    Some(u32::from_le_bytes([value[0], value[1], value[2], value[3]]))
}

pub(crate) fn read_u64(bytes: &[u8], offset: usize) -> Option<u64> {
    let value = bytes.get(offset..offset.checked_add(8)?)?;
    Some(u64::from_le_bytes([
        value[0], value[1], value[2], value[3], value[4], value[5], value[6], value[7],
    ]))
}

pub(crate) fn pack_primary(ratios: [u8; 5]) -> u16 {
    let mut result = 0_u16;
    for ratio in ratios {
        result = (result << 3) | u16::from(ratio >> 5);
    }
    result
}

pub(crate) fn pack_secondary(ratios: [u8; 5]) -> u16 {
    let mut result = 0_u16;
    for ratio in ratios {
        result = (result << 2) | u16::from((ratio >> 3) & 0x03);
    }
    result
}

pub(crate) fn pack_ratio_lows(ratios: [u8; 5]) -> u16 {
    let mut result = 0_u16;
    for (index, ratio) in ratios.into_iter().enumerate() {
        result |= u16::from(ratio & 0x07) << (index * 3);
    }
    result
}

pub(crate) fn unpack_ratios(primary: u16, secondary: u16, lows: u16) -> [u8; 5] {
    let mut result = [0_u8; 5];
    for (index, ratio) in result.iter_mut().enumerate() {
        let primary_shift = (4 - index) * 3;
        let secondary_shift = (4 - index) * 2;
        let high3 = ((primary >> primary_shift) & 0x07) as u8;
        let middle2 = ((secondary >> secondary_shift) & 0x03) as u8;
        let low3 = ((lows >> (index * 3)) & 0x07) as u8;
        *ratio = (high3 << 5) | (middle2 << 3) | low3;
    }
    result
}

pub(crate) fn unpack_pattern_refs(bytes: &[u8]) -> Option<[u32; 4]> {
    let bytes = bytes.get(..10)?;
    let mut refs = [0_u32; 4];
    for (reference_index, reference) in refs.iter_mut().enumerate() {
        let bit_offset = reference_index * 20;
        let byte_offset = bit_offset / 8;
        let shift = bit_offset % 8;
        let mut word = 0_u64;
        for byte_index in 0..4 {
            if let Some(&byte) = bytes.get(byte_offset + byte_index) {
                word |= u64::from(byte) << (byte_index * 8);
            }
        }
        *reference = ((word >> shift) & 0x000f_ffff) as u32;
    }
    Some(refs)
}

pub(crate) fn spatial_cell_for_direction(
    direction: [f64; 3],
    z_bins: u16,
    longitude_bins: u16,
) -> u32 {
    let z_position = (direction[2] + 1.0) * 0.5 * f64::from(z_bins);
    let z_bin = (z_position as u32).min(u32::from(z_bins) - 1);
    let longitude = direction[1].atan2(direction[0]);
    let normalized_longitude = (longitude + core::f64::consts::PI) / (2.0 * core::f64::consts::PI);
    let longitude_position = normalized_longitude * f64::from(longitude_bins);
    let longitude_bin = (longitude_position as u32).min(u32::from(longitude_bins) - 1);
    z_bin * u32::from(longitude_bins) + longitude_bin
}

pub(crate) fn write_u24_signed(output: &mut Vec<u8>, value: i32) {
    let bytes = value.to_le_bytes();
    output.extend_from_slice(&bytes[..3]);
}

pub(crate) fn pack_pattern_refs(refs: [u32; 4]) -> [u8; 10] {
    let mut output = [0_u8; 10];
    for (reference_index, reference) in refs.into_iter().enumerate() {
        let bit_offset = reference_index * 20;
        for bit in 0..20 {
            if reference & (1 << bit) != 0 {
                let output_bit = bit_offset + bit;
                output[output_bit / 8] |= 1 << (output_bit % 8);
            }
        }
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ratio_radix_round_trips_every_byte_boundary() {
        for component in 0..5 {
            for value in 0..=u8::MAX {
                let mut ratios = [0_u8; 5];
                ratios[component] = value;
                let decoded = unpack_ratios(
                    pack_primary(ratios),
                    pack_secondary(ratios),
                    pack_ratio_lows(ratios),
                );
                assert_eq!(decoded, ratios);
            }
        }
    }

    #[test]
    fn packed_twenty_bit_references_round_trip_extremes() {
        let refs = [0, 1, 0x000a_bcde, 0x000f_ffff];
        assert_eq!(unpack_pattern_refs(&pack_pattern_refs(refs)), Some(refs));
    }
}

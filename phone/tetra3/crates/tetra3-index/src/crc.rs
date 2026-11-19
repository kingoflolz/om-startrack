const POLYNOMIAL: u32 = 0x82f6_3b78;

const fn make_table() -> [u32; 256] {
    let mut table = [0_u32; 256];
    let mut index = 0;
    while index < table.len() {
        let mut value = index as u32;
        let mut bit = 0;
        while bit < 8 {
            value = if value & 1 != 0 {
                (value >> 1) ^ POLYNOMIAL
            } else {
                value >> 1
            };
            bit += 1;
        }
        table[index] = value;
        index += 1;
    }
    table
}

const TABLE: [u32; 256] = make_table();

/// Compute CRC-32C (Castagnoli), using the conventional reflected encoding.
pub fn crc32c(bytes: &[u8]) -> u32 {
    let mut crc = !0_u32;
    for &byte in bytes {
        let index = ((crc as u8) ^ byte) as usize;
        crc = TABLE[index] ^ (crc >> 8);
    }
    !crc
}

#[cfg(test)]
mod tests {
    #[test]
    fn check_value() {
        assert_eq!(super::crc32c(b"123456789"), 0xe306_9283);
        assert_eq!(super::crc32c(b""), 0);
    }
}

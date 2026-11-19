use core::ops::ControlFlow;

use tetra3_index::writer::{
    IndexBuilder, LevelInput, PatternInput, VerificationStarInput, quantize_edge_radians,
    quantize_ratio,
};
use tetra3_index::{CapBudget, QueryBudget};
use tetra3_index::{Error, IndexView, crc32c};

fn sample_builder() -> IndexBuilder {
    let mut builder = IndexBuilder::new();
    builder.set_spatial_grid(8, 16).unwrap();
    builder.set_catalog_epoch_milliyear(2_016_000).unwrap();
    let inputs = [
        ([1.0, 0.0, 0.0], 5.0),
        ([1.0, 0.01, 0.0], 1.0),
        ([1.0, 0.02, 0.0], 3.0),
        ([0.0, 1.0, 0.0], 2.0),
        ([0.0, 0.0, 1.0], 4.0),
        ([0.0, 0.0, -1.0], 6.0),
    ];
    let mut verification_ids = Vec::new();
    for (direction, magnitude) in inputs {
        verification_ids.push(
            builder
                .add_verification_star(VerificationStarInput {
                    direction,
                    magnitude,
                    flags: 0,
                })
                .unwrap(),
        );
    }
    let first = PatternInput {
        ratios: [31, 64, 95, 128, 250],
        verification_stars: [
            verification_ids[0],
            verification_ids[1],
            verification_ids[2],
            verification_ids[3],
        ],
        largest_edge_q16: 1_000,
    };
    let shared = PatternInput {
        ratios: [32, 65, 96, 129, 251],
        verification_stars: [
            verification_ids[1],
            verification_ids[2],
            verification_ids[3],
            verification_ids[4],
        ],
        largest_edge_q16: 1_200,
    };
    let last = PatternInput {
        ratios: [40, 70, 100, 140, 252],
        verification_stars: [
            verification_ids[2],
            verification_ids[3],
            verification_ids[4],
            verification_ids[5],
        ],
        largest_edge_q16: 3_000,
    };
    builder
        .add_level(LevelInput {
            min_fov_mdeg: 3_000,
            max_fov_mdeg: 10_000,
            default_tolerance_q8: 3,
            neighbor_level_radius: 1,
            max_verification_magnitude_q4: Some(48),
            patterns: vec![shared, first],
        })
        .unwrap();
    builder
        .add_level(LevelInput {
            min_fov_mdeg: 10_001,
            max_fov_mdeg: 45_000,
            default_tolerance_q8: 4,
            neighbor_level_radius: 1,
            max_verification_magnitude_q4: Some(12),
            patterns: vec![last, shared],
        })
        .unwrap();
    builder
}

fn sample_bytes() -> Vec<u8> {
    sample_builder().build().unwrap()
}

#[test]
fn deterministic_round_trip_uses_shared_payload() {
    let first = sample_builder().build().unwrap();
    let second = sample_builder().build().unwrap();
    assert_eq!(first, second);
    assert_eq!(first.len(), 132_130);
    assert_eq!(crc32c(&first), 0x00f9_de45);

    let index = IndexView::parse(&first).unwrap();
    assert_eq!(index.as_bytes().as_ptr(), first.as_ptr());
    assert_eq!(index.catalog_epoch_milliyear(), 2_016_000);
    assert_eq!(index.catalog_epoch(), 2_016.0);
    assert_eq!(index.level_count(), 2);
    assert_eq!(index.pattern_star_count(), 6);
    assert_eq!(index.level_descriptor(0).unwrap().pattern_count, 2);
    assert_eq!(index.level_descriptor(1).unwrap().pattern_count, 2);
    assert_eq!(
        index.level_descriptor(0).unwrap().verification_star_count,
        6
    );
    assert_eq!(
        index.level_descriptor(1).unwrap().verification_star_count,
        3
    );
    assert_eq!(
        index
            .level_for_fov_mdeg(3_000)
            .unwrap()
            .unwrap()
            .descriptor()
            .max_verification_magnitude_q4,
        Some(48)
    );
}

#[test]
fn lookup_crosses_radix_boundary_and_uses_edge_binary_search() {
    let bytes = sample_bytes();
    let index = IndexView::parse(&bytes).unwrap();
    let level = index.level(0).unwrap();
    let mut candidates = Vec::new();
    let stats = level
        .visit_candidates([32, 64, 95, 128, 250], 1, 1_000..=1_000, &mut |candidate| {
            candidates.push(candidate);
            ControlFlow::Continue(())
        })
        .unwrap();
    assert_eq!(candidates.len(), 1);
    assert_eq!(candidates[0].ratios, [31, 64, 95, 128, 250]);
    assert_eq!(candidates[0].largest_edge_q16, 1_000);
    assert_eq!(stats.candidates_emitted, 1);
    assert!(stats.records_examined <= 1);
}

#[test]
fn verification_cells_are_brightness_sorted_and_cap_is_exact() {
    let bytes = sample_bytes();
    let index = IndexView::parse(&bytes).unwrap();
    let grid = index.spatial_grid().unwrap();
    for cell in 0..grid.cell_count() {
        let mut previous = i8::MIN;
        for star in index.spatial_cell_range(cell).unwrap() {
            let star = index.verification_star(star).unwrap();
            assert!(star.magnitude_q4 >= previous);
            previous = star.magnitude_q4;
        }
    }

    let mut magnitudes = Vec::new();
    let stats = index
        .visit_cap([1.0, 0.0, 0.0], 0.1, Some(12), &mut |_id, star| {
            magnitudes.push(star.magnitude_q4);
            ControlFlow::Continue(())
        })
        .unwrap();
    magnitudes.sort_unstable();
    assert_eq!(magnitudes, [4, 12]);
    assert_eq!(stats.stars_emitted, 2);
}

#[test]
fn oct24_mapping_preserves_direction_without_duplication() {
    let mut builder = IndexBuilder::new();
    builder
        .add_verification_star(VerificationStarInput {
            direction: [1.0, 0.01, 0.0],
            magnitude: 1.0,
            flags: 0,
        })
        .unwrap();
    let bytes = builder.build().unwrap();
    let index = IndexView::parse(&bytes).unwrap();
    let direction = index.verification_star(0).unwrap().direction_f64();
    let expected = [1.0_f64, 0.01, 0.0];
    let norm = (expected[0] * expected[0] + expected[1] * expected[1]).sqrt();
    let dot = direction[0] * expected[0] / norm + direction[1] * expected[1] / norm;
    assert!(dot > 0.999_999_9, "dot={dot}");
}

#[test]
fn quantizers_follow_floor_contract_and_shared_golden_values() {
    assert_eq!(quantize_ratio(0.0).unwrap(), 0);
    assert_eq!(quantize_ratio(1.0 / 256.0).unwrap(), 1);
    assert_eq!(quantize_ratio(1.0).unwrap(), 255);
    let golden = [
        0.562_455_916_022_761,
        0.639_041_429_295_630,
        0.669_176_077_598_340,
        0.817_928_399_580_266,
        0.904_136_407_141_932,
    ]
    .map(|ratio| quantize_ratio(ratio).unwrap());
    assert_eq!(golden, [143, 163, 171, 209, 231]);
    assert_eq!(
        quantize_edge_radians(core::f64::consts::PI).unwrap(),
        65_535
    );
    for quantized in 0..=u8::MAX {
        let lower = f64::from(quantized) / 256.0;
        assert_eq!(quantize_ratio(lower).unwrap(), quantized);
        let upper = ((f64::from(quantized) + 1.0) / 256.0).min(1.0);
        let just_below = if upper == 0.0 {
            0.0
        } else {
            f64::from_bits(upper.to_bits() - 1)
        };
        assert_eq!(quantize_ratio(just_below).unwrap(), quantized);
    }
}

#[test]
fn bounded_lookup_stops_before_hard_record_and_byte_caps() {
    let bytes = sample_bytes();
    let index = IndexView::parse(&bytes).unwrap();
    let level = index.level(0).unwrap();
    let mut emitted = 0;
    let no_bytes = level
        .visit_candidates_bounded(
            [31, 64, 95, 128, 250],
            0,
            1_000..=1_000,
            QueryBudget {
                max_records: 10,
                max_bytes: 7,
            },
            &mut |_| {
                emitted += 1;
                ControlFlow::Continue(())
            },
        )
        .unwrap();
    assert!(no_bytes.budget_exhausted);
    assert_eq!(no_bytes.bytes_read, 0);
    assert_eq!(no_bytes.records_examined, 0);
    assert_eq!(emitted, 0);

    let no_records = level
        .visit_candidates_bounded(
            [31, 64, 95, 128, 250],
            0,
            1_000..=1_000,
            QueryBudget {
                max_records: 0,
                max_bytes: u64::MAX,
            },
            &mut |_| {
                emitted += 1;
                ControlFlow::Continue(())
            },
        )
        .unwrap();
    assert!(no_records.budget_exhausted);
    assert_eq!(no_records.records_examined, 0);
    assert_eq!(emitted, 0);

    let bounded = level
        .visit_candidates_bounded(
            [31, 64, 95, 128, 250],
            0,
            1_000..=1_000,
            QueryBudget {
                max_records: 1,
                max_bytes: 256,
            },
            &mut |_| ControlFlow::Continue(()),
        )
        .unwrap();
    assert!(bounded.records_examined <= 1);
    assert!(bounded.bytes_read <= 256);
}

#[test]
fn raw_index_and_catalog_scans_honor_cooperative_cancellation() {
    let bytes = sample_bytes();
    let index = IndexView::parse(&bytes).unwrap();
    let level = index.level(0).unwrap();
    let mut index_polls = 0;
    let stats = level
        .visit_candidates_bounded_cancelable(
            [31, 64, 95, 128, 250],
            0,
            1_000..=1_000,
            QueryBudget::UNLIMITED,
            &mut || {
                index_polls += 1;
                true
            },
            &mut |_| panic!("a cancelled raw index scan must not emit"),
        )
        .unwrap();
    assert_eq!(index_polls, 1);
    assert_eq!(stats.records_examined, 0);
    assert_eq!(stats.candidates_emitted, 0);
    assert!(!stats.budget_exhausted);

    let mut catalog_polls = 0;
    let stats = index
        .visit_cap_bounded_cancelable(
            [1.0, 0.0, 0.0],
            core::f64::consts::PI,
            None,
            CapBudget::UNLIMITED,
            &mut || {
                catalog_polls += 1;
                true
            },
            &mut |_, _| panic!("a cancelled raw catalog scan must not emit"),
        )
        .unwrap();
    assert_eq!(catalog_polls, 1);
    assert_eq!(stats.records_examined, 0);
    assert_eq!(stats.stars_emitted, 0);
    assert!(!stats.budget_exhausted);
}

#[test]
fn decoded_direction_drives_spatial_seams_and_small_caps() {
    let mut builder = IndexBuilder::new();
    let seam = builder
        .add_verification_star(VerificationStarInput {
            direction: [1.0, 0.123, -1.0e-11],
            magnitude: 2.0,
            flags: 0,
        })
        .unwrap();
    let precision = builder
        .add_verification_star(VerificationStarInput {
            direction: [0.041_066_810_49, 0.722_010_254_9, -0.690_662_324_4],
            magnitude: 3.0,
            flags: 0,
        })
        .unwrap();
    assert_ne!(seam, precision);
    let bytes = builder.build().unwrap();
    let index = IndexView::parse(&bytes).unwrap();

    for star_id in 0..index.verification_star_count() {
        let center = index.verification_star(star_id).unwrap().direction_f64();
        let mut found_at_zero = false;
        index
            .visit_cap(center, 0.0, None, &mut |id, _| {
                found_at_zero |= id == star_id;
                ControlFlow::Continue(())
            })
            .unwrap();
        assert!(
            found_at_zero,
            "zero-radius cap missed decoded star {star_id}"
        );

        let mut found_at_75_arcsec = false;
        index
            .visit_cap(
                center,
                75.0_f64.to_radians() / 3_600.0,
                None,
                &mut |id, _| {
                    found_at_75_arcsec |= id == star_id;
                    ControlFlow::Continue(())
                },
            )
            .unwrap();
        assert!(found_at_75_arcsec);
    }
}

#[test]
fn cap_handles_longitude_wrap_and_pole() {
    let mut builder = IndexBuilder::new();
    for direction in [
        [-1.0, 0.000_1, 0.0],
        [-1.0, -0.000_1, 0.0],
        [0.000_01, 0.0, 1.0],
    ] {
        builder
            .add_verification_star(VerificationStarInput {
                direction,
                magnitude: 1.0,
                flags: 0,
            })
            .unwrap();
    }
    let bytes = builder.build().unwrap();
    let index = IndexView::parse(&bytes).unwrap();
    let mut wrap_count = 0;
    index
        .visit_cap([-1.0, 0.0, 0.0], 0.001, None, &mut |_, _| {
            wrap_count += 1;
            ControlFlow::Continue(())
        })
        .unwrap();
    assert_eq!(wrap_count, 2);
    let mut pole_count = 0;
    index
        .visit_cap([0.0, 0.0, 1.0], 0.001, None, &mut |_, _| {
            pole_count += 1;
            ControlFlow::Continue(())
        })
        .unwrap();
    assert_eq!(pole_count, 1);

    let no_bytes = index
        .visit_cap_bounded(
            [0.0, 0.0, 1.0],
            0.001,
            None,
            CapBudget {
                max_cells: 10,
                max_records: 10,
                max_bytes: 3,
            },
            &mut |_, _| panic!("budget must stop before callback"),
        )
        .unwrap();
    assert!(no_bytes.budget_exhausted);
    assert_eq!(no_bytes.bytes_read, 0);
    assert_eq!(no_bytes.records_examined, 0);

    let no_records = index
        .visit_cap_bounded(
            [0.0, 0.0, 1.0],
            0.001,
            None,
            CapBudget {
                max_cells: 10,
                max_records: 0,
                max_bytes: 1_024,
            },
            &mut |_, _| ControlFlow::Continue(()),
        )
        .unwrap();
    assert!(no_records.budget_exhausted);
    assert_eq!(no_records.records_examined, 0);
    assert!(no_records.bytes_read <= 1_024);
}

#[test]
fn full_validation_recomputes_level_window_count() {
    let mut bytes = sample_bytes();
    let level_section_offset = u64::from_le_bytes(bytes[72..80].try_into().unwrap()) as usize;
    bytes[level_section_offset + 8..level_section_offset + 12]
        .copy_from_slice(&1_u32.to_le_bytes());
    repair_section_checksum(&mut bytes, 0);
    assert_eq!(
        IndexView::parse(&bytes).unwrap_err(),
        Error::InvalidLevel { level: 0 }
    );
}

#[test]
fn rejects_every_noncurrent_minor_version() {
    for minor in [0_u16, 2] {
        let mut bytes = sample_bytes();
        bytes[6..8].copy_from_slice(&minor.to_le_bytes());
        assert_eq!(
            IndexView::parse(&bytes).unwrap_err(),
            Error::UnsupportedVersion { major: 1, minor }
        );
    }
}

#[test]
fn rejects_incorrect_verification_count() {
    let mut bytes = sample_bytes();
    let level_section_offset = u64::from_le_bytes(bytes[72..80].try_into().unwrap()) as usize;
    bytes[level_section_offset + 12..level_section_offset + 16]
        .copy_from_slice(&5_u32.to_le_bytes());
    repair_section_checksum(&mut bytes, 0);
    assert_eq!(
        IndexView::parse(&bytes).unwrap_err(),
        Error::InvalidLevel { level: 0 }
    );
}

#[test]
fn rejects_checksums_endian_overflow_and_trailing_bytes() {
    let original = sample_bytes();

    let mut bad_magic = original.clone();
    bad_magic[0] ^= 0xff;
    assert_eq!(IndexView::parse(&bad_magic).unwrap_err(), Error::BadMagic);

    let mut wrong_endian = original.clone();
    wrong_endian[8..12].copy_from_slice(&0x0403_0201_u32.to_le_bytes());
    assert!(matches!(
        IndexView::parse(&wrong_endian),
        Err(Error::WrongEndian { .. })
    ));

    let mut bad_header = original.clone();
    bad_header[48] ^= 1;
    assert_eq!(
        IndexView::parse(&bad_header).unwrap_err(),
        Error::HeaderChecksum
    );
    assert!(IndexView::parse_without_checksums(&bad_header).is_ok());

    let mut bad_table = original.clone();
    bad_table[64] ^= 1;
    assert_eq!(
        IndexView::parse(&bad_table).unwrap_err(),
        Error::SectionTableChecksum
    );

    let mut bad_section = original.clone();
    *bad_section.last_mut().unwrap() ^= 1;
    assert!(matches!(
        IndexView::parse(&bad_section),
        Err(Error::SectionChecksum { section: 6 })
    ));
    assert!(IndexView::parse_without_checksums(&bad_section).is_ok());

    let mut overflow = original.clone();
    let section_zero_length = 64 + 16;
    overflow[section_zero_length..section_zero_length + 8].copy_from_slice(&u64::MAX.to_le_bytes());
    repair_table_and_header_checksums(&mut overflow);
    assert_eq!(
        IndexView::parse(&overflow).unwrap_err(),
        Error::ArithmeticOverflow
    );

    let mut trailing = original;
    trailing.push(0);
    assert_eq!(
        IndexView::parse(&trailing).unwrap_err(),
        Error::FileLengthMismatch
    );
}

fn repair_table_and_header_checksums(bytes: &mut [u8]) {
    let section_count = u32::from_le_bytes(bytes[32..36].try_into().unwrap()) as usize;
    let table_end = 64 + section_count * 32;
    let table_checksum = crc32c(&bytes[64..table_end]);
    bytes[44..48].copy_from_slice(&table_checksum.to_le_bytes());
    let header_checksum = crc32c(&bytes[..60]);
    bytes[60..64].copy_from_slice(&header_checksum.to_le_bytes());
}

fn repair_section_checksum(bytes: &mut [u8], section: usize) {
    let table_offset = 64 + section * 32;
    let offset = u64::from_le_bytes(
        bytes[table_offset + 8..table_offset + 16]
            .try_into()
            .unwrap(),
    ) as usize;
    let length = u64::from_le_bytes(
        bytes[table_offset + 16..table_offset + 24]
            .try_into()
            .unwrap(),
    ) as usize;
    let checksum = crc32c(&bytes[offset..offset + length]);
    bytes[table_offset + 28..table_offset + 32].copy_from_slice(&checksum.to_le_bytes());
    repair_table_and_header_checksums(bytes);
}

#[cfg(feature = "mmap")]
#[test]
fn mmap_accepts_non_page_aligned_asset_offset() {
    use std::io::{Seek, SeekFrom, Write};

    use tetra3_index::MappedIndex;

    let bytes = sample_bytes();
    let mut file = tempfile::tempfile().unwrap();
    file.write_all(&[0xa5; 123]).unwrap();
    file.write_all(&bytes).unwrap();
    file.flush().unwrap();
    file.seek(SeekFrom::Start(0)).unwrap();

    // SAFETY: this test keeps the temporary file alive and immutable until the
    // mapping has been dropped, and the requested region exists in full.
    let mapped = unsafe { MappedIndex::map_trusted_region(&file, 123, bytes.len()) }.unwrap();
    let view = mapped.view();
    assert_eq!(view.catalog_epoch(), 2_016.0);
    assert_eq!(view.level_descriptor(0).unwrap().pattern_count, 2);
}

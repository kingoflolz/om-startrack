use std::ops::ControlFlow;

use tetra3::core::{
    CatalogQuery, Invariant, Observation, PatternIndex, PatternQuery, PixelCentroid,
    PixelObservation, SolveConfig, SolveOutcome, StarCatalog, UnitVec3,
};
use tetra3::index::{IndexView, QueryBudget, writer};
use tetra3::lens::{
    CalibrationGeometry, CameraGeometry, CropRect, DistortionKnot, DistortionModel, LensProfile,
    Orientation, PreparedLens, ProfileId, Projection,
};
use tetra3::{LensProjector, RuntimeIndex};

fn ray(x: f64, y: f64) -> UnitVec3 {
    UnitVec3::new(x, y, 1.0).unwrap()
}

fn synthetic_rays() -> [UnitVec3; 8] {
    [
        ray(-0.18, -0.12),
        ray(0.15, -0.14),
        ray(-0.11, 0.17),
        ray(0.19, 0.13),
        ray(-0.03, -0.21),
        ray(0.05, 0.22),
        ray(-0.23, 0.03),
        ray(0.24, -0.01),
    ]
}

fn build_synthetic_index() -> Vec<u8> {
    let rays = synthetic_rays();
    let mut builder = writer::IndexBuilder::new();
    builder.set_spatial_grid(18, 36).unwrap();
    let mut verification_ids = [0_u32; 8];
    for (index, direction) in rays.iter().copied().enumerate() {
        let direction = direction.as_vec();
        verification_ids[index] = builder
            .add_verification_star(writer::VerificationStarInput {
                direction: [direction.x, direction.y, direction.z],
                magnitude: 4.0 + index as f32 * 0.25,
                flags: 0,
            })
            .unwrap();
    }
    let invariant = Invariant::from_rays(rays[..4].try_into().unwrap()).unwrap();
    builder
        .add_level(writer::LevelInput {
            min_fov_mdeg: 3_000,
            max_fov_mdeg: 45_000,
            default_tolerance_q8: 4,
            neighbor_level_radius: 0,
            max_verification_magnitude_q4: Some(32),
            patterns: vec![
                writer::PatternInput::from_floats(
                    invariant.ratios(),
                    verification_ids[..4].try_into().unwrap(),
                    invariant.largest_edge_rad(),
                )
                .unwrap(),
            ],
        })
        .unwrap();
    builder.build().unwrap()
}

fn add_synthetic_stars(builder: &mut writer::IndexBuilder) -> [u32; 8] {
    let mut verification_ids = [0_u32; 8];
    for (index, direction) in synthetic_rays().iter().copied().enumerate() {
        let direction = direction.as_vec();
        verification_ids[index] = builder
            .add_verification_star(writer::VerificationStarInput {
                direction: [direction.x, direction.y, direction.z],
                magnitude: 4.0 + index as f32 * 0.25,
                flags: 0,
            })
            .unwrap();
    }
    verification_ids
}

fn build_empty_boundary_fallback_index() -> (Vec<u8>, Invariant) {
    let rays = synthetic_rays();
    let invariant = Invariant::from_rays(rays[..4].try_into().unwrap()).unwrap();
    let mut builder = writer::IndexBuilder::new();
    builder.set_spatial_grid(18, 36).unwrap();
    let verification_ids = add_synthetic_stars(&mut builder);
    builder
        .add_level(writer::LevelInput {
            min_fov_mdeg: 3_000,
            max_fov_mdeg: 5_000,
            default_tolerance_q8: 4,
            neighbor_level_radius: 1,
            max_verification_magnitude_q4: Some(18),
            patterns: vec![],
        })
        .unwrap();
    builder
        .add_level(writer::LevelInput {
            min_fov_mdeg: 5_001,
            max_fov_mdeg: 8_000,
            default_tolerance_q8: 4,
            neighbor_level_radius: 1,
            max_verification_magnitude_q4: Some(32),
            patterns: vec![
                writer::PatternInput::from_floats(
                    invariant.ratios(),
                    verification_ids[..4].try_into().unwrap(),
                    invariant.largest_edge_rad(),
                )
                .unwrap(),
            ],
        })
        .unwrap();
    (builder.build().unwrap(), invariant)
}

fn build_decoy_neighbor_index() -> (Vec<u8>, Invariant, u16) {
    let rays = synthetic_rays();
    let target = Invariant::from_rays(rays[..4].try_into().unwrap()).unwrap();
    let decoy = Invariant::from_rays(rays[4..].try_into().unwrap()).unwrap();
    assert!(decoy.max_ratio_delta(target) > 3.0 / 255.0);

    let mut builder = writer::IndexBuilder::new();
    builder.set_spatial_grid(18, 36).unwrap();
    let verification_ids = add_synthetic_stars(&mut builder);
    let decoy_edge = writer::quantize_edge_radians(decoy.largest_edge_rad()).unwrap();
    builder
        .add_level(writer::LevelInput {
            min_fov_mdeg: 3_000,
            max_fov_mdeg: 5_000,
            default_tolerance_q8: 4,
            neighbor_level_radius: 1,
            max_verification_magnitude_q4: Some(18),
            // Deliberately route a geometrically different pattern through the
            // target q8 bucket. The runtime exact filter must reject it before
            // deciding whether adjacent-level fallback is needed.
            patterns: vec![writer::PatternInput {
                ratios: target.quantized_q8(),
                verification_stars: verification_ids[4..].try_into().unwrap(),
                largest_edge_q16: decoy_edge,
            }],
        })
        .unwrap();
    builder
        .add_level(writer::LevelInput {
            min_fov_mdeg: 5_001,
            max_fov_mdeg: 8_000,
            default_tolerance_q8: 4,
            neighbor_level_radius: 1,
            max_verification_magnitude_q4: Some(32),
            patterns: vec![
                writer::PatternInput::from_floats(
                    target.ratios(),
                    verification_ids[..4].try_into().unwrap(),
                    target.largest_edge_rad(),
                )
                .unwrap(),
            ],
        })
        .unwrap();
    (builder.build().unwrap(), target, decoy_edge)
}

fn build_exact_decoy_neighbor_index() -> (Vec<u8>, Invariant) {
    let target_rays = synthetic_rays();
    // A slightly smaller copy of the seed tetrahedron has the same normalized
    // shape to within the lookup tolerance, but cannot satisfy the solver's
    // much tighter four-ray attitude residual gate.
    let decoy_rays = [
        ray(-0.18 * 0.98, -0.12 * 0.98),
        ray(0.15 * 0.98, -0.14 * 0.98),
        ray(-0.11 * 0.98, 0.17 * 0.98),
        ray(0.19 * 0.98, 0.13 * 0.98),
    ];
    let target = Invariant::from_rays(target_rays[..4].try_into().unwrap()).unwrap();
    let decoy = Invariant::from_rays(decoy_rays).unwrap();
    assert!(decoy.max_ratio_delta(target) <= 3.0 / 255.0);
    assert!((decoy.largest_edge_rad() / target.largest_edge_rad() - 1.0).abs() <= 0.03);
    assert_ne!(
        writer::quantize_edge_radians(decoy.largest_edge_rad()).unwrap(),
        writer::quantize_edge_radians(target.largest_edge_rad()).unwrap()
    );

    let mut builder = writer::IndexBuilder::new();
    builder.set_spatial_grid(18, 36).unwrap();
    let target_ids = add_synthetic_stars(&mut builder);
    let mut decoy_ids = [0_u32; 4];
    for (index, direction) in decoy_rays.into_iter().enumerate() {
        let direction = direction.as_vec();
        decoy_ids[index] = builder
            .add_verification_star(writer::VerificationStarInput {
                direction: [direction.x, direction.y, direction.z],
                magnitude: 6.0 + index as f32 * 0.25,
                flags: 0,
            })
            .unwrap();
    }
    builder
        .add_level(writer::LevelInput {
            min_fov_mdeg: 3_000,
            max_fov_mdeg: 5_000,
            default_tolerance_q8: 4,
            neighbor_level_radius: 1,
            max_verification_magnitude_q4: Some(32),
            patterns: vec![
                writer::PatternInput::from_floats(
                    decoy.ratios(),
                    decoy_ids,
                    decoy.largest_edge_rad(),
                )
                .unwrap(),
            ],
        })
        .unwrap();
    builder
        .add_level(writer::LevelInput {
            min_fov_mdeg: 5_001,
            max_fov_mdeg: 8_000,
            default_tolerance_q8: 4,
            neighbor_level_radius: 1,
            max_verification_magnitude_q4: Some(32),
            patterns: vec![
                writer::PatternInput::from_floats(
                    target.ratios(),
                    target_ids[..4].try_into().unwrap(),
                    target.largest_edge_rad(),
                )
                .unwrap(),
            ],
        })
        .unwrap();
    (builder.build().unwrap(), target)
}

#[test]
fn writer_view_adapter_and_core_solve_end_to_end() {
    let bytes = build_synthetic_index();
    let view = IndexView::parse(&bytes).unwrap();
    assert_eq!(view.as_bytes().as_ptr(), bytes.as_ptr());
    let runtime = RuntimeIndex::for_level(view, 0).unwrap();
    assert_eq!(runtime.selected_level(), Some(0));
    assert_eq!(runtime.verification_magnitude_limit_q4(), Some(32));
    let observations: Vec<_> = synthetic_rays()
        .into_iter()
        .enumerate()
        .map(|(index, ray)| Observation {
            ray,
            // Make the indexed seed deterministically be the first quad.
            flux: (100 - index) as f32,
        })
        .collect();
    let config = SolveConfig {
        seed_tolerance_rad: 1.0e-3,
        match_tolerance_rad: 1.0e-3,
        max_chance_probability: 1.0,
        min_matches: 4,
        ..SolveConfig::default()
    };

    let outcome = runtime.solve_rays(&observations, 0.4, &config).unwrap();
    let SolveOutcome::Solved { solution, .. } = outcome else {
        panic!("expected one solution, got {outcome:?}");
    };
    assert_eq!(solution.matches.len(), 4);
    assert!(solution.rms_residual_rad < 2.0e-6);
    assert!(
        solution
            .camera_to_world
            .angle_to(tetra3::core::Quat::identity())
            < 2.0e-6
    );
}

#[test]
fn runtime_forwards_an_immediate_cooperative_deadline_before_the_first_quad() {
    let bytes = build_synthetic_index();
    let runtime = RuntimeIndex::new(IndexView::parse(&bytes).unwrap());
    let observations: Vec<_> = synthetic_rays()
        .into_iter()
        .enumerate()
        .map(|(index, ray)| Observation {
            ray,
            flux: (100 - index) as f32,
        })
        .collect();

    let outcome = runtime
        .solve_rays_with_deadline(&observations, 0.4, &SolveConfig::default(), || true)
        .unwrap();
    let SolveOutcome::NoMatch {
        reason,
        diagnostics,
    } = outcome
    else {
        panic!("an immediate deadline must not produce a solution");
    };
    assert_eq!(reason, tetra3::core::NoMatchReason::DeadlineExceeded);
    assert!(diagnostics.deadline_exceeded);
    assert_eq!(diagnostics.quads_tested, 0);
}

#[test]
fn candidate_invariant_is_reconstructed_from_catalog_vectors() {
    let bytes = build_synthetic_index();
    let runtime = RuntimeIndex::new(IndexView::parse(&bytes).unwrap());
    let image_invariant = Invariant::from_rays(synthetic_rays()[..4].try_into().unwrap()).unwrap();
    let mut candidate = None;
    runtime
        .visit_candidates(
            PatternQuery {
                invariant: image_invariant,
                ratio_tolerance: 3.0 / 255.0,
                largest_edge_min_rad: image_invariant.largest_edge_rad() * 0.99,
                largest_edge_max_rad: image_invariant.largest_edge_rad() * 1.01,
                max_records_to_scan: 1_000,
                max_bytes_to_read: 1_000_000,
            },
            &mut |value| {
                candidate = Some(value);
                ControlFlow::Break(())
            },
        )
        .unwrap();
    let candidate = candidate.expect("stored pattern");
    let decoded_rays = candidate.stars.map(|star| star.direction);
    let decoded_invariant = Invariant::from_rays(decoded_rays).unwrap();
    // Oct24 reconstruction is not manufactured by clamping the query into the
    // q8 bucket: it is genuinely independent, albeit extremely close.
    assert_ne!(decoded_invariant, image_invariant);
    assert!(decoded_invariant.max_ratio_delta(image_invariant) <= 3.0 / 255.0);
}

#[test]
fn empty_boundary_level_falls_back_without_changing_verification_cutoff() {
    let (bytes, invariant) = build_empty_boundary_fallback_index();
    let view = IndexView::parse(&bytes).unwrap();
    let runtime = RuntimeIndex::for_level(view, 0).unwrap();
    let neighbor_maximum = view.level_descriptor(1).unwrap().max_largest_edge_q16;
    let tolerance = 0.03;
    let expected_observed_limit =
        ((f64::from(neighbor_maximum) + 1.0) * std::f64::consts::PI / 65_536.0) / (1.0 - tolerance);
    assert!(
        (runtime
            .maximum_observed_pattern_edge_rad(tolerance)
            .unwrap()
            .unwrap()
            - expected_observed_limit)
            .abs()
            < 1.0e-15
    );
    let mut candidate_count = 0;
    let stats = runtime
        .visit_candidates(
            PatternQuery {
                invariant,
                ratio_tolerance: 3.0 / 255.0,
                largest_edge_min_rad: invariant.largest_edge_rad() * 0.99,
                largest_edge_max_rad: invariant.largest_edge_rad() * 1.01,
                max_records_to_scan: 1_000,
                max_bytes_to_read: 1_000_000,
            },
            &mut |_candidate| {
                candidate_count += 1;
                ControlFlow::Continue(())
            },
        )
        .unwrap();
    assert_eq!(candidate_count, 1);
    assert!(!stats.budget_exhausted);
    assert_eq!(runtime.selected_level(), Some(0));
    assert_eq!(runtime.verification_magnitude_limit_q4(), Some(18));
    assert_eq!(runtime.verification_star_count_hint(), Some(3));
    let density = runtime
        .density_hint_per_steradian(UnitVec3::Z, 0.5)
        .unwrap()
        .unwrap();
    assert!((density - 4.0 * 3.0 / (4.0 * std::f64::consts::PI)).abs() < 1.0e-15);
    let unselected = RuntimeIndex::new(view);
    assert_eq!(unselected.verification_magnitude_limit_q4(), None);
    assert_eq!(unselected.verification_star_count_hint(), Some(8));
    let unselected_density = unselected
        .density_hint_per_steradian(UnitVec3::Z, 0.5)
        .unwrap()
        .unwrap();
    assert!((unselected_density - 4.0 * 8.0 / (4.0 * std::f64::consts::PI)).abs() < 1.0e-15);
    let suppressed = runtime.without_verification_density_hint();
    assert_eq!(suppressed.verification_star_count_hint(), None);
    assert_eq!(
        suppressed
            .density_hint_per_steradian(UnitVec3::Z, 0.5)
            .unwrap(),
        None
    );
    let mut emitted = 0;
    let cap = runtime
        .visit_cap(
            CatalogQuery {
                center: UnitVec3::Z,
                radius_rad: 0.5,
                max_cells_to_scan: u32::MAX,
                max_records_to_scan: u32::MAX,
                max_bytes_to_read: u64::MAX,
            },
            &mut |_star| {
                emitted += 1;
                ControlFlow::Continue(())
            },
        )
        .unwrap();
    // Magnitudes are 4.00, 4.25, 4.50, ...; q4=18 admits exactly
    // the first three even though the matching pattern came from level one.
    assert_eq!(emitted, 3);
    assert!(!cap.budget_exhausted);
}

#[test]
fn exact_filter_controls_fallback_and_probes_share_one_budget() {
    let (bytes, target, decoy_edge) = build_decoy_neighbor_index();
    let view = IndexView::parse(&bytes).unwrap();
    let runtime = RuntimeIndex::for_level(view, 0).unwrap();
    let decoy_edge_rad = f64::from(decoy_edge) * std::f64::consts::PI / 65_536.0;
    let minimum_edge = target.largest_edge_rad().min(decoy_edge_rad) * 0.99;
    let maximum_edge = target.largest_edge_rad().max(decoy_edge_rad) * 1.01;

    let mut emitted = 0;
    let complete = runtime
        .visit_candidates(
            PatternQuery {
                invariant: target,
                ratio_tolerance: 3.0 / 255.0,
                largest_edge_min_rad: minimum_edge,
                largest_edge_max_rad: maximum_edge,
                max_records_to_scan: 1_000,
                max_bytes_to_read: 1_000_000,
            },
            &mut |_candidate| {
                emitted += 1;
                ControlFlow::Continue(())
            },
        )
        .unwrap();
    assert_eq!(emitted, 1);
    assert!(!complete.budget_exhausted);

    let selected_only = view
        .level(0)
        .unwrap()
        .visit_candidates_bounded(
            target.quantized_q8(),
            4,
            decoy_edge..=decoy_edge,
            QueryBudget::UNLIMITED,
            &mut |_candidate| ControlFlow::Continue(()),
        )
        .unwrap();
    assert_eq!(selected_only.candidates_emitted, 1);

    let mut emitted = 0_u32;
    let exhausted = runtime
        .visit_candidates(
            PatternQuery {
                invariant: target,
                ratio_tolerance: 3.0 / 255.0,
                largest_edge_min_rad: minimum_edge,
                largest_edge_max_rad: maximum_edge,
                max_records_to_scan: 1_000,
                // Exactly enough for the selected-level decoy, leaving no
                // bytes for the adjacent-level target.
                max_bytes_to_read: selected_only.bytes_read,
            },
            &mut |_candidate| {
                emitted += 1;
                ControlFlow::Continue(())
            },
        )
        .unwrap();
    assert_eq!(emitted, 0);
    assert_eq!(exhausted.bytes_read, selected_only.bytes_read);
    assert!(exhausted.budget_exhausted);
}

#[test]
fn exact_selected_decoy_does_not_hide_valid_neighbor() {
    let (bytes, target) = build_exact_decoy_neighbor_index();
    let runtime = RuntimeIndex::for_level(IndexView::parse(&bytes).unwrap(), 0).unwrap();
    let edge_minimum = target.largest_edge_rad() * 0.97;
    let edge_maximum = target.largest_edge_rad() * 1.03;

    let mut visited = 0;
    let stats = runtime
        .visit_candidates(
            PatternQuery {
                invariant: target,
                ratio_tolerance: 3.0 / 255.0,
                largest_edge_min_rad: edge_minimum,
                largest_edge_max_rad: edge_maximum,
                max_records_to_scan: 1_000,
                max_bytes_to_read: 1_000_000,
            },
            &mut |_candidate| {
                visited += 1;
                // Level zero is an exact invariant decoy. Continuing models a
                // solver callback rejecting it after attitude verification.
                ControlFlow::Continue(())
            },
        )
        .unwrap();
    assert_eq!(visited, 2);
    assert!(!stats.budget_exhausted);

    let observations: Vec<_> = synthetic_rays()
        .into_iter()
        .enumerate()
        .map(|(index, ray)| Observation {
            ray,
            flux: (100 - index) as f32,
        })
        .collect();
    let config = SolveConfig {
        max_chance_probability: 1.0,
        ..SolveConfig::default()
    };
    let outcome = runtime.solve_rays(&observations, 0.4, &config).unwrap();
    let SolveOutcome::Solved {
        solution,
        diagnostics,
    } = outcome
    else {
        panic!("expected the neighbor candidate to solve after the decoy, got {outcome:?}");
    };
    assert_eq!(solution.matches.len(), 4);
    assert!(diagnostics.candidates_tested >= 2);
    assert!(
        solution
            .camera_to_world
            .angle_to(tetra3::core::Quat::identity())
            < 2.0e-6
    );
}

#[test]
fn lookup_stops_before_hard_byte_budget() {
    let bytes = build_synthetic_index();
    let runtime = RuntimeIndex::new(IndexView::parse(&bytes).unwrap());
    let invariant = Invariant::from_rays(synthetic_rays()[..4].try_into().unwrap()).unwrap();
    let stats = runtime
        .visit_candidates(
            PatternQuery {
                invariant,
                ratio_tolerance: 3.0 / 255.0,
                largest_edge_min_rad: invariant.largest_edge_rad() * 0.99,
                largest_edge_max_rad: invariant.largest_edge_rad() * 1.01,
                max_records_to_scan: u32::MAX,
                max_bytes_to_read: 7,
            },
            &mut |_candidate| panic!("a seven-byte budget cannot reach a candidate"),
        )
        .unwrap();
    assert!(stats.budget_exhausted);
    assert!(stats.bytes_read <= 7);
    assert_eq!(stats.records_scanned, 0);

    // Measure the complete single-candidate path, including its four decoded
    // verification vectors, then prove one byte less cannot emit it.
    let mut emitted = 0;
    let complete = runtime
        .visit_candidates(
            PatternQuery {
                invariant,
                ratio_tolerance: 3.0 / 255.0,
                largest_edge_min_rad: invariant.largest_edge_rad() * 0.99,
                largest_edge_max_rad: invariant.largest_edge_rad() * 1.01,
                max_records_to_scan: u32::MAX,
                max_bytes_to_read: u64::MAX,
            },
            &mut |_candidate| {
                emitted += 1;
                ControlFlow::Break(())
            },
        )
        .unwrap();
    assert_eq!(emitted, 1);
    assert!(complete.bytes_read >= 4 * tetra3_index::VERIFICATION_STAR_SIZE as u64);
    let tight = runtime
        .visit_candidates(
            PatternQuery {
                invariant,
                ratio_tolerance: 3.0 / 255.0,
                largest_edge_min_rad: invariant.largest_edge_rad() * 0.99,
                largest_edge_max_rad: invariant.largest_edge_rad() * 1.01,
                max_records_to_scan: u32::MAX,
                max_bytes_to_read: complete.bytes_read - 1,
            },
            &mut |_candidate| panic!("under-budget query emitted decoded point reads"),
        )
        .unwrap();
    assert!(tight.budget_exhausted);
    assert!(tight.bytes_read < complete.bytes_read);
}

#[test]
fn catalog_cap_stops_before_hidden_cell_or_record_scans() {
    let bytes = build_synthetic_index();
    let runtime = RuntimeIndex::new(IndexView::parse(&bytes).unwrap());
    let stats = runtime
        .visit_cap(
            CatalogQuery {
                center: UnitVec3::Z,
                radius_rad: 0.4,
                max_cells_to_scan: u32::MAX,
                max_records_to_scan: u32::MAX,
                max_bytes_to_read: 7,
            },
            &mut |_star| panic!("a seven-byte budget cannot reach a catalog row"),
        )
        .unwrap();
    assert!(stats.budget_exhausted);
    assert!(stats.bytes_read <= 7);
    assert_eq!(stats.cells_scanned, 0);
    assert_eq!(stats.records_scanned, 0);
}

#[test]
fn prepared_lens_projector_preserves_android_camera_axes() {
    let camera = CameraGeometry::new(
        401,
        301,
        CropRect::new(0, 0, 401, 301),
        401,
        301,
        Orientation::Normal,
        2.0,
    )
    .unwrap();
    let profile = LensProfile::new(
        ProfileId::new("runtime-test").unwrap(),
        camera,
        CalibrationGeometry::new(2.0, 4.0 / 3.0, 0.0, 0.0).unwrap(),
        Projection::Rectilinear,
        vec![DistortionKnot::new(25.0, 25.0, DistortionModel::None).unwrap()],
    )
    .unwrap();
    let lens = profile.prepare(25.0).unwrap();
    let projector = LensProjector::new(&lens);
    let center = projector
        .pixel_to_ray(tetra3::core::PixelCentroid { x: 200.0, y: 150.0 }, 1.0)
        .unwrap();
    assert!(center.angle_to(UnitVec3::Z) < 1.0e-14);
    let right = projector
        .pixel_to_ray(tetra3::core::PixelCentroid { x: 250.0, y: 150.0 }, 1.0)
        .unwrap()
        .as_vec();
    let down = projector
        .pixel_to_ray(tetra3::core::PixelCentroid { x: 200.0, y: 200.0 }, 1.0)
        .unwrap()
        .as_vec();
    assert!(right.x > 0.0 && right.y.abs() < 1.0e-14 && right.z > 0.0);
    assert!(down.y > 0.0 && down.x.abs() < 1.0e-14 && down.z > 0.0);
    assert!(projector.field_radius_rad(1.0).unwrap() > 0.0);
}

#[test]
fn lens_projector_field_radius_covers_full_om1_pixel_rims() {
    let camera = CameraGeometry::new(
        5_184,
        3_888,
        CropRect::new(0, 0, 5_184, 3_888),
        1_296,
        972,
        Orientation::Normal,
        2.0,
    )
    .unwrap();
    let lens = PreparedLens::rectilinear_no_distortion(camera, 300.0, 3.3).unwrap();
    let projector = LensProjector::new(&lens);
    let sensor_half_width_mm = 5_184.0 * 3.3 / 1_000.0 / 2.0;
    let sensor_half_height_mm = 3_888.0 * 3.3 / 1_000.0 / 2.0;
    let expected = f64::hypot(sensor_half_width_mm, sensor_half_height_mm).atan2(300.0);
    assert!((projector.field_radius_rad(1.0).unwrap() - expected).abs() < 1.0e-14);
}

#[test]
fn solve_pixels_projects_only_bounded_top_k_and_preserves_source_indices() {
    const INPUTS: usize = 100_000;
    const BASE_FOV_SCALE: f64 = 1.1;
    let bytes = build_synthetic_index();
    let runtime = RuntimeIndex::new(IndexView::parse(&bytes).unwrap());
    let camera = CameraGeometry::new(
        401,
        301,
        CropRect::new(0, 0, 401, 301),
        401,
        301,
        Orientation::Normal,
        2.0,
    )
    .unwrap();
    let profile = LensProfile::new(
        ProfileId::new("runtime-bounded-pixels").unwrap(),
        camera,
        CalibrationGeometry::new(2.0, 4.0 / 3.0, 0.0, 0.0).unwrap(),
        Projection::Rectilinear,
        vec![DistortionKnot::new(25.0, 25.0, DistortionModel::None).unwrap()],
    )
    .unwrap();
    let lens = profile.prepare(25.0).unwrap();

    // All but eight entries are deliberately outside the lens model. They
    // would make the former O(INPUTS) projection path fail immediately.
    let mut pixels = vec![
        PixelObservation {
            centroid: PixelCentroid { x: -1.0, y: -1.0 },
            flux: 1.0,
        };
        INPUTS
    ];
    let source_indices = [17, 3_000, 250, 70_000, 80_000, 64, 90_000, 512];
    let normalization =
        f64::hypot(36.0, 24.0) / 2.0 / f64::hypot(f64::from(401_u32), f64::from(301_u32)) / 25.0;
    for (ray_index, (source_index, direction)) in
        source_indices.into_iter().zip(synthetic_rays()).enumerate()
    {
        let direction = direction.as_vec();
        pixels[source_index] = PixelObservation {
            centroid: PixelCentroid {
                x: 200.0 + direction.x / direction.z / normalization / BASE_FOV_SCALE,
                y: 150.0 + direction.y / direction.z / normalization / BASE_FOV_SCALE,
            },
            flux: (100 - ray_index) as f32,
        };
    }
    let mut config = SolveConfig {
        seed_tolerance_rad: 1.0e-3,
        match_tolerance_rad: 1.0e-3,
        max_chance_probability: 1.0,
        min_matches: 4,
        ..SolveConfig::default()
    };
    config.budgets.max_observations = 8;

    let outcome = runtime
        .solve_pixels(&lens, &pixels, BASE_FOV_SCALE, &config)
        .expect("discarded low-flux pixels must not be projected");
    let SolveOutcome::Solved { solution, .. } = outcome else {
        panic!("expected one solution, got {outcome:?}");
    };
    assert_eq!(solution.fov_scale, BASE_FOV_SCALE);
    let mut expected_seed_sources = source_indices[..4].to_vec();
    expected_seed_sources.sort_unstable();
    assert_eq!(
        solution
            .seed_matches
            .iter()
            .map(|matched| matched.observation_index)
            .collect::<Vec<_>>(),
        expected_seed_sources
    );
    let mut expected_sources = source_indices[4..].to_vec();
    expected_sources.sort_unstable();
    assert_eq!(
        solution
            .matches
            .iter()
            .map(|matched| matched.observation_index)
            .collect::<Vec<_>>(),
        expected_sources
    );
}

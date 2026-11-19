use tetra3_lens::{
    CalibrationGeometry, CameraGeometry, CropRect, DistortionKnot, DistortionModel, LensProfile,
    Orientation, Pixel, ProfileId, Projection, UnitRay,
};

fn identity_profile(id: &str, camera: CameraGeometry, focal_mm: f32) -> LensProfile {
    LensProfile::new(
        ProfileId::new(id).unwrap(),
        camera,
        CalibrationGeometry::new(2.0, 4.0 / 3.0, 0.0, 0.0).unwrap(),
        Projection::Rectilinear,
        vec![DistortionKnot::new(focal_mm, focal_mm, DistortionModel::None).unwrap()],
    )
    .unwrap()
}

fn assert_same_ray(left: UnitRay, right: UnitRay) {
    assert!((left.x - right.x).abs() < 1e-14, "{left:?} != {right:?}");
    assert!((left.y - right.y).abs() < 1e-14, "{left:?} != {right:?}");
    assert!((left.z - right.z).abs() < 1e-14, "{left:?} != {right:?}");
}

#[test]
fn reflected_orientation_is_undone_before_ray_construction() {
    let normal = identity_profile(
        "normal",
        CameraGeometry::new(
            400,
            300,
            CropRect::new(0, 0, 400, 300),
            400,
            300,
            Orientation::Normal,
            2.0,
        )
        .unwrap(),
        25.0,
    )
    .prepare(25.0)
    .unwrap();
    let mirrored = identity_profile(
        "mirrored",
        CameraGeometry::new(
            400,
            300,
            CropRect::new(0, 0, 400, 300),
            400,
            300,
            Orientation::MirrorHorizontal,
            2.0,
        )
        .unwrap(),
        25.0,
    )
    .prepare(25.0)
    .unwrap();

    let native = Pixel::new(71.25, 211.75);
    let stored_mirrored = Pixel::new(399.0 - native.x, native.y);
    assert_same_ray(
        normal.pixel_to_ray(native).unwrap(),
        mirrored.pixel_to_ray(stored_mirrored).unwrap(),
    );
}

#[test]
fn rotate_90_and_resize_preserve_the_physical_ray() {
    let normal = identity_profile(
        "normal",
        CameraGeometry::new(
            400,
            300,
            CropRect::new(0, 0, 400, 300),
            400,
            300,
            Orientation::Normal,
            2.0,
        )
        .unwrap(),
        25.0,
    )
    .prepare(25.0)
    .unwrap();
    let rotated = identity_profile(
        "rotated",
        CameraGeometry::new(
            400,
            300,
            CropRect::new(0, 0, 400, 300),
            150,
            200,
            Orientation::Rotate90,
            2.0,
        )
        .unwrap(),
        25.0,
    )
    .prepare(25.0)
    .unwrap();

    let native = Pixel::new(99.5, 79.5);
    // Native -> 90-degree-clockwise delivered edge transform, then 2x resize.
    let delivered = Pixel::new((298.5 - native.y) / 2.0, (native.x - 0.5) / 2.0);
    assert_same_ray(
        normal.pixel_to_ray(native).unwrap(),
        rotated.pixel_to_ray(delivered).unwrap(),
    );
}

#[test]
fn om1_like_fixture_has_expected_scale() {
    // Synthetic camera-mode fixture with OM-1 raster/crop geometry and a
    // plausible three-knot standard-zoom profile. Coefficients are test data,
    // not a claim of measured calibration for a particular lens copy.
    let profile = LensProfile::new(
        ProfileId::new("om-system.om-1|12-40-pro-ii|preview-1024x768").unwrap(),
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
                    a: 0.0010,
                    b: -0.0180,
                    c: 0.0040,
                },
            )
            .unwrap(),
            DistortionKnot::new(
                25.0,
                24.7,
                DistortionModel::Ptlens {
                    a: 0.0005,
                    b: -0.0090,
                    c: 0.0020,
                },
            )
            .unwrap(),
            DistortionKnot::new(
                40.0,
                39.5,
                DistortionModel::Ptlens {
                    a: 0.0002,
                    b: -0.0040,
                    c: 0.0010,
                },
            )
            .unwrap(),
        ],
    )
    .unwrap();
    let lens = profile.prepare(25.0).unwrap();
    assert!((lens.real_focal_mm() - 24.7).abs() < 1e-6);

    let pixels = [
        Pixel::new(511.5, 383.5),
        Pixel::new(1023.0, 383.5),
        Pixel::new(511.5, 767.0),
    ];
    let rays = pixels.map(|pixel| lens.pixel_to_ray(pixel).unwrap());
    assert!(rays[0].z > 0.999_999);
    let horizontal_half_angle_deg = rays[1].x.atan2(rays[1].z).to_degrees();
    assert!((17.0..22.0).contains(&horizontal_half_angle_deg));
    assert!(rays[2].y > 0.0, "+Y must point down in the image");
}

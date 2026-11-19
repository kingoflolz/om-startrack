use tetra3::lens::{Database, LensProfile};

use crate::error::BridgeError;

const DECORATIVE_TOKENS: &[&str] = &[
    "asph", "digital", "ed", "fisheye", "is", "lens", "m", "mega", "mzuiko", "ois", "olympus",
    "om", "power", "pro", "zuiko",
];

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct LensIdentity<'a> {
    maker: &'a str,
    model: &'a str,
}

/// Resolve an auditable, equality-based camera name.
///
/// There is deliberately no edit-distance ranking or "closest" result. Name
/// normalization only discards punctuation/case and well-known branding words,
/// then requires a unique calibration. A miss is returned as `None` so the
/// caller can use the explicit rectilinear fallback.
pub(crate) fn resolve_profile<'a>(
    database: &'a Database,
    identifier_or_name: &str,
) -> Result<Option<&'a LensProfile>, BridgeError> {
    let input = identifier_or_name.trim();
    if input.is_empty() {
        return Ok(None);
    }
    let folded_input = fold(input);
    let input_signature = signature(input);
    if folded_input.is_empty() || input_signature.is_empty() {
        return Ok(None);
    }

    let parsed = database
        .profiles()
        .iter()
        .filter_map(|profile| {
            parse_generated_id(profile.id().as_str()).map(|identity| (profile, identity))
        })
        .collect::<Vec<_>>();

    // Prefer literal equality after case/punctuation folding. This tier never
    // loses model words.
    let exact = parsed
        .iter()
        .filter(|(_, identity)| {
            let model = fold(identity.model);
            if folded_input == model {
                return true;
            }
            let mut qualified = fold(identity.maker);
            qualified.push_str(&model);
            folded_input == qualified
        })
        .map(|(profile, _)| *profile)
        .collect::<Vec<_>>();
    if !exact.is_empty() {
        return unique_calibration(exact).map(Some);
    }

    // Camera strings normally begin with the maker. Keep maker-qualified
    // candidates ahead of the cross-maker tier so "OLYMPUS M..." cannot pick
    // an otherwise identical third-party model string.
    let normalized = parsed
        .iter()
        .filter(|(_, identity)| input_signature == signature(identity.model))
        .copied()
        .collect::<Vec<_>>();
    let maker_qualified = normalized
        .iter()
        .filter(|(_, identity)| mentions_maker(&folded_input, identity.maker))
        .map(|(profile, _)| *profile)
        .collect::<Vec<_>>();
    if !maker_qualified.is_empty() {
        return unique_calibration(maker_qualified).map(Some);
    }

    if normalized.is_empty() {
        Ok(None)
    } else {
        unique_calibration(normalized.into_iter().map(|(profile, _)| profile).collect()).map(Some)
    }
}

fn unique_calibration(profiles: Vec<&LensProfile>) -> Result<&LensProfile, BridgeError> {
    // Database profiles are already in canonical ID order, and filtering
    // preserves that order.
    let first = profiles[0];
    if profiles
        .iter()
        .skip(1)
        .all(|profile| equivalent_calibration(first, profile))
    {
        Ok(first)
    } else {
        Err(BridgeError::AmbiguousLens(
            profiles
                .iter()
                .map(|profile| profile.id().as_str().to_owned())
                .collect(),
        ))
    }
}

fn equivalent_calibration(left: &LensProfile, right: &LensProfile) -> bool {
    left.camera() == right.camera()
        && left.calibration() == right.calibration()
        && left.projection() == right.projection()
        && left.knots() == right.knots()
}

fn parse_generated_id(id: &str) -> Option<LensIdentity<'_>> {
    let (_, encoded) = id.split_once("|lens=")?;
    let (maker_length, after_maker_length) = decimal_prefix(encoded)?;
    let maker_end = maker_length.checked_add(after_maker_length)?;
    let maker = encoded.get(after_maker_length..maker_end)?;
    let after_maker = encoded.get(maker_end..)?;
    let (model_length, after_model_length) = decimal_prefix(after_maker)?;
    let model_end = model_length.checked_add(after_model_length)?;
    let model = after_maker.get(after_model_length..model_end)?;
    if model_end != after_maker.len() {
        return None;
    }
    Some(LensIdentity { maker, model })
}

/// Return `(declared byte length, byte offset after N:)`.
fn decimal_prefix(value: &str) -> Option<(usize, usize)> {
    let colon = value.find(':')?;
    if colon == 0 || !value.as_bytes()[..colon].iter().all(u8::is_ascii_digit) {
        return None;
    }
    Some((value[..colon].parse().ok()?, colon + 1))
}

fn fold(value: &str) -> String {
    value
        .chars()
        .filter(|character| character.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

fn signature(value: &str) -> String {
    tokens(value)
        .into_iter()
        .filter(|token| !DECORATIVE_TOKENS.contains(&token.as_str()))
        .collect::<Vec<_>>()
        .join("|")
}

fn tokens(value: &str) -> Vec<String> {
    let mut output = Vec::new();
    let mut current = String::new();
    let mut numeric = None;
    for character in value.chars().flat_map(char::to_lowercase) {
        if !character.is_alphanumeric() {
            if !current.is_empty() {
                output.push(std::mem::take(&mut current));
            }
            numeric = None;
            continue;
        }
        let next_numeric = character.is_numeric();
        if numeric.is_some_and(|previous| previous != next_numeric) && !current.is_empty() {
            output.push(std::mem::take(&mut current));
        }
        current.push(character);
        numeric = Some(next_numeric);
    }
    if !current.is_empty() {
        output.push(current);
    }
    output
}

fn mentions_maker(folded_input: &str, maker: &str) -> bool {
    let maker = fold(maker);
    !maker.is_empty() && folded_input.starts_with(&maker)
}

#[cfg(test)]
mod tests {
    use tetra3::lens::{
        CalibrationGeometry, Database, DistortionKnot, DistortionModel, LensProfile, ProfileId,
        Projection,
    };
    use tetra3::production::om1_star_camera_geometry;

    use super::*;

    fn profile(id: &str, k1: f32) -> LensProfile {
        LensProfile::new(
            ProfileId::new(id).unwrap(),
            om1_star_camera_geometry(),
            CalibrationGeometry::new(2.0, 4.0 / 3.0, 0.0, 0.0).unwrap(),
            Projection::Rectilinear,
            vec![DistortionKnot::new(12.0, 12.0, DistortionModel::Poly3 { k1 }).unwrap()],
        )
        .unwrap()
    }

    #[test]
    fn camera_olympus_name_resolves_generated_olympus_profile() {
        let olympus = profile(
            "lensfun-v2.om1.active-1296x972|lens=7:Olympus23:OLYMPUS OM 12-45mm F4.0",
            0.01,
        );
        let omds = profile(
            "lensfun-v2.om1.active-1296x972|lens=20:OM Digital Solutions15:OM 12-45mm F4.0",
            0.02,
        );
        let database = Database::new(vec![omds, olympus]).unwrap();
        let selected = resolve_profile(&database, "OLYMPUS M.12-45mm F4.0")
            .unwrap()
            .unwrap();
        assert!(selected.id().as_str().contains("7:Olympus"));
    }

    #[test]
    fn camera_8mm_name_resolves_profile_with_fisheye_marketing_suffix() {
        let id = "lensfun-v2.om1.active-1296x972|lens=7:Olympus48:Olympus M.Zuiko Digital ED 8mm f/1.8 Fisheye Pro";
        let database = Database::new(vec![profile(id, -0.00531)]).unwrap();
        assert_eq!(
            resolve_profile(&database, "OLYMPUS M.8mm F1.8")
                .unwrap()
                .unwrap()
                .id()
                .as_str(),
            id
        );
    }

    #[test]
    fn generated_profile_ids_are_not_accepted_as_camera_names() {
        let id = "lensfun-v2.om1.active-1296x972|lens=7:Olympus23:OLYMPUS OM 12-45mm F4.0";
        let database = Database::new(vec![profile(id, 0.01)]).unwrap();
        assert!(resolve_profile(&database, id).unwrap().is_none());
    }

    #[test]
    fn missing_name_requests_fallback() {
        let database = Database::new(vec![profile(
            "lensfun-v2.om1.active-1296x972|lens=7:Olympus23:OLYMPUS OM 12-45mm F4.0",
            0.01,
        )])
        .unwrap();
        assert!(
            resolve_profile(&database, "OLYMPUS M.300mm F4.0")
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn ambiguous_different_calibrations_fail_closed() {
        let database = Database::new(vec![
            profile("p|lens=1:A6:OM 20x", 0.01),
            profile("q|lens=1:A6:OM 20x", 0.02),
        ])
        .unwrap();
        assert!(matches!(
            resolve_profile(&database, "OM 20x"),
            Err(BridgeError::AmbiguousLens(_))
        ));
    }

    #[test]
    fn identical_duplicate_calibration_has_stable_lowest_id() {
        let database = Database::new(vec![
            profile("z|lens=1:A6:OM 20x", 0.01),
            profile("a|lens=1:A6:OM 20x", 0.01),
        ])
        .unwrap();
        assert_eq!(
            resolve_profile(&database, "OM 20x")
                .unwrap()
                .unwrap()
                .id()
                .as_str(),
            "a|lens=1:A6:OM 20x"
        );
    }

    #[test]
    fn malformed_generated_ids_are_not_partial_matches() {
        assert!(parse_generated_id("x|lens=99:a1:b").is_none());
        assert!(parse_generated_id("x|lens=1:a1:btrailing").is_none());
    }

    #[test]
    fn production_bundle_resolves_camera_12_45_name_when_available() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../build/profiles/om1-all-mft.t3lf");
        if !path.is_file() {
            return;
        }
        let bytes = std::fs::read(path).unwrap();
        let database = Database::from_t3lf(&bytes).unwrap();
        let profile = resolve_profile(&database, "OLYMPUS M.12-45mm F4.0")
            .unwrap()
            .unwrap();
        assert_eq!(
            profile.id().as_str(),
            "lensfun-v2.om1.active-1296x972|lens=7:Olympus23:OLYMPUS OM 12-45mm F4.0"
        );
    }

    #[test]
    fn production_bundle_resolves_camera_8mm_name_without_marketing_suffix_when_available() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../build/profiles/om1-all-mft.t3lf");
        if !path.is_file() {
            return;
        }
        let bytes = std::fs::read(path).unwrap();
        let database = Database::from_t3lf(&bytes).unwrap();
        let profile = resolve_profile(&database, "OLYMPUS M.8mm F1.8")
            .unwrap()
            .unwrap();
        assert_eq!(
            profile.id().as_str(),
            "lensfun-v2.om1.active-1296x972|lens=7:Olympus48:Olympus M.Zuiko Digital ED 8mm f/1.8 Fisheye Pro"
        );
        assert_eq!(profile.projection(), Projection::FisheyeEquisolid);
    }
}

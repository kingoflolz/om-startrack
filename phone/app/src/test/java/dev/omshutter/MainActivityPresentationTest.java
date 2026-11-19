package dev.omshutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.StarTelemetry;
import dev.omshutter.solver.SiderealRateCorrection;
import dev.omshutter.solver.SolveResult;
import dev.omshutter.solver.SolveSnapshot;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

public final class MainActivityPresentationTest {
    private static final String ANDROID_NAMESPACE =
            "http://schemas.android.com/apk/res/android";

    @Test
    public void acknowledgedQ16RatesAreShownAsPhysicalMicroRadiansPerSecond() {
        assertEquals("−44.7000", MainActivity.formatMicroRadians(-44.7e-6));
        assertEquals("0.0000", MainActivity.formatMicroRadians(0.0));
        assertEquals("44.7000", MainActivity.formatMicroRadians(44.7e-6));

        SiderealRateCorrection fractional = SiderealRateCorrection.fromBodyAngularVelocity(
                0.0,
                -2.5 * SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND,
                0.0);
        assertEquals("−0.2500", MainActivity.formatMicroRadians(
                fractional.getYawRadiansPerSecond()));
    }

    @Test
    public void lockedDurationFitsOnTheLockSegmentAtTenthsPrecision() {
        assertEquals("00:00.0", MainActivity.formatShutterTimer(-1L));
        assertEquals("00:01.0", MainActivity.formatShutterTimer(1_099L));
        assertEquals("02:03.4", MainActivity.formatShutterTimer(123_499L));
    }

    @Test
    public void latestShutterSnapshotWinsOnlyAfterPointerSequenceEnds() {
        assertFalse(MainActivity.reconciledShutterLockState(true, false, true));
        assertTrue(MainActivity.reconciledShutterLockState(true, true, false));
        assertTrue(MainActivity.reconciledShutterLockState(false, false, true));
        assertFalse(MainActivity.reconciledShutterLockState(false, true, false));
    }

    @Test
    public void bothAdditionalPointerTransitionsCancelTheGesture() {
        assertTrue(MainActivity.isAdditionalPointerTransition(5));
        assertTrue(MainActivity.isAdditionalPointerTransition(6));
        assertFalse(MainActivity.isAdditionalPointerTransition(0));
        assertFalse(MainActivity.isAdditionalPointerTransition(1));
    }

    @Test
    public void inAppBackNavigationPrioritizesConnectionThenStarPage() {
        assertEquals(
                MainActivity.BackDestination.CONTROLS,
                MainActivity.backDestination(true, true));
        assertEquals(
                MainActivity.BackDestination.CONTROLS,
                MainActivity.backDestination(true, false));
        assertEquals(
                MainActivity.BackDestination.SHUTTER,
                MainActivity.backDestination(false, true));
        assertEquals(
                MainActivity.BackDestination.EXIT,
                MainActivity.backDestination(false, false));
    }

    @Test
    public void heldShutterKeepsAstroNavigationFromHidingReleaseControl() {
        assertTrue(MainActivity.isAstroNavigationEnabled(false, false, false));
        assertFalse(MainActivity.isAstroNavigationEnabled(true, false, false));
        assertFalse(MainActivity.isAstroNavigationEnabled(false, true, false));
        assertFalse(MainActivity.isAstroNavigationEnabled(false, false, true));

        assertTrue(MainActivity.shouldReturnToShutterPage(false, true, true));
        assertFalse(MainActivity.shouldReturnToShutterPage(false, true, false));
        assertFalse(MainActivity.shouldReturnToShutterPage(false, false, true));
        assertFalse(MainActivity.shouldReturnToShutterPage(true, true, true));
    }

    @Test
    public void staleAcknowledgementCannotReachVisibleRateLine() {
        SiderealRateCorrection rate = SiderealRateCorrection.fromBodyAngularVelocity(
                1.0e-5, -2.0e-5, 3.0e-5);
        assertTrue(MainActivity.hasCurrentAcknowledgedRate(rate, 17L, 17L));
        assertFalse(MainActivity.hasCurrentAcknowledgedRate(rate, 18L, 17L));
        assertFalse(MainActivity.hasCurrentAcknowledgedRate(null, 17L, 17L));
        assertFalse(MainActivity.hasCurrentAcknowledgedRate(rate, 17L, 17L, false));
    }

    @Test
    public void correctionDiagnosticsDistinguishFitSupportFromVerificationRms() {
        java.util.Locale locale = java.util.Locale.US;
        assertEquals("Fit 4+0", MainActivity.formatFitSupport(0));
        assertEquals("Fit 4+7", MainActivity.formatFitSupport(7));
        assertEquals("Fit 4+0", MainActivity.formatFitSupport(-1));
        assertEquals("Verify RMS 0.8″", MainActivity.formatVerificationRms(0.75, locale));

        assertEquals(
                "Centered ≈—",
                MainActivity.formatApproximateExposure("Centered", Double.NaN, locale));
        assertEquals(
                "Remaining ≈1.3 s",
                MainActivity.formatApproximateExposure("Remaining", 1.25, locale));
        assertEquals(
                "Centered ≈12 s",
                MainActivity.formatApproximateExposure("Centered", 12.4, locale));

        assertEquals(
                "Fit used 4 seed stars plus 7 verification stars. Verification RMS 0.8″. "
                        + "Centered-stage exposure estimate unavailable. "
                        + "Remaining exposure estimate unavailable.",
                MainActivity.correctionDiagnosticsDescription(
                        7, 0.75, Double.NaN, Double.NaN, locale));
    }

    @Test
    public void solveDurationUsesCompactMillisecondsAndSeconds() {
        java.util.Locale locale = java.util.Locale.US;
        assertEquals("—", MainActivity.formatSolveDuration(-1L, locale));
        assertEquals("850 ms", MainActivity.formatSolveDuration(850_400_000L, locale));
        assertEquals("1.25 s", MainActivity.formatSolveDuration(1_250_000_000L, locale));
        assertEquals("12.4 s", MainActivity.formatSolveDuration(12_350_000_000L, locale));
        assertEquals("125 s", MainActivity.formatSolveDuration(125_400_000_000L, locale));
    }

    @Test
    public void captureSettingsStayCollapsedAndThreeWide() throws Exception {
        Document layout = readResourceXml("layout/activity_main.xml");
        Element panel = findByAndroidId(layout, "capture_settings_panel");
        assertEquals("horizontal", androidAttribute(panel, "orientation"));
        assertEquals("gone", androidAttribute(panel, "visibility"));

        String[] pickerIds = {"star_aperture", "star_shutter_speed", "star_iso"};
        Set<Node> cells = new HashSet<>();
        for (String pickerId : pickerIds) {
            Element picker = findByAndroidId(layout, pickerId);
            Element cell = (Element) picker.getParentNode();
            cells.add(cell);
            assertSame(panel, cell.getParentNode());
            assertEquals("vertical", androidAttribute(cell, "orientation"));
            assertEquals("0dp", androidAttribute(cell, "layout_width"));
            assertEquals("1", androidAttribute(cell, "layout_weight"));
            assertNotNull(findLabelFor(cell, pickerId));
        }
        assertEquals(3, cells.size());
    }

    @Test
    public void detectorInputsStayOnePerLineWithVisibleValidRanges() throws Exception {
        Document layout = readResourceXml("layout/activity_main.xml");
        Element panel = findByAndroidId(layout, "detector_settings_panel");
        assertEquals("vertical", androidAttribute(panel, "orientation"));
        assertEquals("gone", androidAttribute(panel, "visibility"));

        String[] inputIds = {
                "detector_sigma",
                "detector_min_area",
                "detector_max_area",
                "detector_elongation",
                "detector_sharpness",
                "detector_border"
        };
        Set<Node> rows = new HashSet<>();
        for (String inputId : inputIds) {
            Element input = findByAndroidId(layout, inputId);
            Element row = (Element) input.getParentNode();
            rows.add(row);
            assertEquals("vertical", androidAttribute(row, "orientation"));
            assertEquals("match_parent", androidAttribute(row, "layout_width"));
            assertEquals("match_parent", androidAttribute(input, "layout_width"));
            assertNotNull(findLabelFor(row, inputId));
        }
        assertEquals(6, rows.size());

        Map<String, String> strings = readStrings();
        assertEquals("Sigma (0.00390625–64)", strings.get("detector_sigma"));
        assertEquals("Min area (1–65535, ≤ max)", strings.get("detector_min_area"));
        assertEquals("Max area (1–65535, ≥ min)", strings.get("detector_max_area"));
        assertEquals(
                "Max elongation (0 off, 1–255.99609375)",
                strings.get("detector_elongation"));
        assertEquals("Sharpness (0–1)", strings.get("detector_sharpness"));
        assertEquals("Border (0–1943)", strings.get("detector_border"));
    }

    @Test
    public void manualOverrideCoordinatesShareOneHiddenTwoColumnRow() throws Exception {
        Document layout = readResourceXml("layout/activity_main.xml");
        Element panel = findByAndroidId(layout, "manual_attitude_fields");
        assertEquals("vertical", androidAttribute(panel, "orientation"));
        assertEquals("gone", androidAttribute(panel, "visibility"));

        Element rightAscension = findByAndroidId(layout, "manual_ra");
        Element declination = findByAndroidId(layout, "manual_dec");
        Element rightAscensionCell = (Element) rightAscension.getParentNode();
        Element declinationCell = (Element) declination.getParentNode();
        Element row = (Element) rightAscensionCell.getParentNode();
        assertSame(row, declinationCell.getParentNode());
        assertSame(panel, row.getParentNode());
        assertEquals("horizontal", androidAttribute(row, "orientation"));

        Element[] cells = {rightAscensionCell, declinationCell};
        Element[] inputs = {rightAscension, declination};
        String[] inputIds = {"manual_ra", "manual_dec"};
        for (int index = 0; index < cells.length; index++) {
            assertEquals("vertical", androidAttribute(cells[index], "orientation"));
            assertEquals("0dp", androidAttribute(cells[index], "layout_width"));
            assertEquals("1", androidAttribute(cells[index], "layout_weight"));
            assertEquals("match_parent", androidAttribute(inputs[index], "layout_width"));
            assertEquals("48dp", androidAttribute(inputs[index], "layout_height"));
            assertNotNull(findLabelFor(cells[index], inputIds[index]));
        }

        boolean hasEightDpSpacing = false;
        NodeList children = row.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element
                    && "Space".equals(((Element) child).getTagName())
                    && "8dp".equals(androidAttribute((Element) child, "layout_width"))) {
                hasEightDpSpacing = true;
            }
        }
        assertTrue(hasEightDpSpacing);
    }

    @Test
    public void liveAnglesFitOneLineWhileEquatorialCoordinatesAndSolveAgeWrap()
            throws Exception {
        Document layout = readResourceXml("layout/activity_main.xml");
        for (String prefix : new String[]{"shutter", "star_tracker"}) {
            boolean tracker = "star_tracker".equals(prefix);
            Element container = findByAndroidId(layout, prefix + "_last_solve_coordinates");
            Element altitudeAzimuth = findByAndroidId(layout, prefix + "_last_solve_altaz");
            Element rightAscensionDeclination =
                    findByAndroidId(layout, prefix + "_last_solve_radec");
            Element age = findByAndroidId(layout, prefix + "_last_solve_latency");
            assertSame(findByAndroidId(layout,
                    tracker ? "star_tracker_panel" : "shutter_astro_panel"),
                    container.getParentNode());
            assertSame(container, nextElementSibling(findByAndroidId(layout,
                    tracker ? "star_status" : "shutter_correction_status")));
            assertEquals("vertical", androidAttribute(container, "orientation"));
            assertEquals("gone", androidAttribute(container, "visibility"));
            assertEquals("", androidAttribute(container, "accessibilityLiveRegion"));
            assertEquals("yes", androidAttribute(container, "importantForAccessibility"));
            assertEquals("@string/camera_pointing_unavailable",
                    androidAttribute(container, "contentDescription"));
            assertSame(rightAscensionDeclination, nextElementSibling(altitudeAzimuth));
            assertSame(age, nextElementSibling(rightAscensionDeclination));

            for (Element line : new Element[]{altitudeAzimuth, rightAscensionDeclination, age}) {
                assertSame(container, line.getParentNode());
                // Each line uses the available width without another column squeezing it.
                assertEquals("match_parent", androidAttribute(line, "layout_width"));
                assertEquals("false", androidAttribute(line, "singleLine"));
                assertEquals(line == altitudeAzimuth ? "1" : "",
                        androidAttribute(line, "maxLines"));
                assertEquals("", androidAttribute(line, "ellipsize"));
                assertEquals("start", androidAttribute(line, "gravity"));
                assertEquals("no", androidAttribute(line, "importantForAccessibility"));
            }
            assertEquals("12sp", androidAttribute(altitudeAzimuth, "textSize"));
            assertEquals("false", androidAttribute(altitudeAzimuth, "scrollHorizontally"));
            assertEquals("uniform", androidAttribute(altitudeAzimuth, "autoSizeTextType"));
            assertEquals("9sp", androidAttribute(altitudeAzimuth, "autoSizeMinTextSize"));
            assertEquals("12sp", androidAttribute(altitudeAzimuth, "autoSizeMaxTextSize"));
            assertEquals("0.5sp", androidAttribute(altitudeAzimuth, "autoSizeStepGranularity"));
            assertEquals(tracker ? "@color/text_primary" : "@color/text_secondary",
                    androidAttribute(altitudeAzimuth, "textColor"));
            assertEquals(tracker ? "bold" : "", androidAttribute(altitudeAzimuth, "textStyle"));
            assertEquals(tracker ? "14sp" : "12sp",
                    androidAttribute(rightAscensionDeclination, "textSize"));
            assertEquals("@color/text_secondary",
                    androidAttribute(rightAscensionDeclination, "textColor"));
            assertEquals("12sp", androidAttribute(age, "textSize"));
            assertEquals("@string/camera_radec_unavailable",
                    androidAttribute(rightAscensionDeclination, "text"));
            assertEquals("@string/last_solve_altaz_unavailable",
                    androidAttribute(altitudeAzimuth, "text"));
            assertEquals("@string/last_solve_age_unavailable", androidAttribute(age, "text"));
        }

        Map<String, String> strings = readStrings();
        assertEquals("Camera RA — · Dec —", strings.get("camera_radec_unavailable"));
        assertEquals("Camera RA ≈%1$s · Dec ≈%2$s", strings.get("camera_radec_format"));
        assertEquals("Alt — · Az — · Roll —", strings.get("last_solve_altaz_unavailable"));
        assertEquals("Alt ≈%1$s · Az ≈%2$s · Roll ≈%3$s", strings.get("last_solve_altaz_format"));
        assertEquals("Solved —", strings.get("last_solve_age_unavailable"));
        assertEquals("Solved %1$s", strings.get("last_solve_age_format"));
        assertEquals("Zero attitude (test)", strings.get("zero_camera_altaz_test"));
        assertEquals(
                "Camera pointing. Right ascension approximately %1$s. "
                        + "Declination approximately %2$s. Altitude approximately %3$s. "
                        + "Azimuth approximately %4$s. Roll approximately %5$s, "
                        + "positive clockwise viewed from behind. %6$s.",
                strings.get("camera_pointing_accessibility"));
    }

    @Test
    public void locationRefreshStaysVisibleOutsidePointingAndSolveDetails() throws Exception {
        Document layout = readResourceXml("layout/activity_main.xml");
        for (String prefix : new String[]{"shutter", "star_tracker"}) {
            boolean tracker = "star_tracker".equals(prefix);
            Element row = findByAndroidId(layout, prefix + "_location_row");
            Element status = findByAndroidId(layout, prefix + "_location_status");
            Element refresh = findByAndroidId(layout, prefix + "_location_refresh_button");
            assertSame(findByAndroidId(layout,
                    tracker ? "star_tracker_panel" : "shutter_astro_panel"), row.getParentNode());
            assertSame(row, nextElementSibling(
                    findByAndroidId(layout, prefix + "_last_solve_coordinates")));
            assertSame(findByAndroidId(layout,
                    tracker ? "capture_settings_toggle" : "shutter_correction_diagnostics"),
                    nextElementSibling(row));
            assertEquals("", androidAttribute(row, "visibility"));
            assertEquals("horizontal", androidAttribute(row, "orientation"));
            assertSame(row, status.getParentNode());
            assertSame(row, refresh.getParentNode());
            assertEquals("0dp", androidAttribute(status, "layout_width"));
            assertEquals("1", androidAttribute(status, "layout_weight"));
            assertEquals("false", androidAttribute(status, "singleLine"));
            assertEquals("", androidAttribute(status, "maxLines"));
            assertEquals("", androidAttribute(status, "ellipsize"));
            assertEquals("@string/location_unavailable", androidAttribute(status, "text"));
            assertEquals("48dp", androidAttribute(refresh, "layout_height"));
            assertEquals("@string/location_refresh", androidAttribute(refresh, "text"));
            assertEquals("@string/location_refresh_accessibility",
                    androidAttribute(refresh, "contentDescription"));
        }
        Map<String, String> strings = readStrings();
        assertEquals("Location unavailable", strings.get("location_unavailable"));
        assertEquals("Location updated %1$s", strings.get("location_age_format"));
        assertEquals("Getting location…", strings.get("location_refreshing"));
        assertEquals("Location refresh failed", strings.get("location_refresh_failed"));
        assertEquals("Refresh", strings.get("location_refresh"));
        assertEquals("Refresh location", strings.get("location_refresh_accessibility"));
    }

    @Test
    public void rejectedNewAttemptCannotReuseTheRetainedSuccessfulStatus() {
        SolveResult result = new SolveResult(
                12.0, 20.0, 1.0, 3.0, 1.0,
                new int[]{0, 1, 2, 3}, new int[]{4, 5});
        SolveSnapshot retained = SolveSnapshot.of(
                SolveSnapshot.State.SOLVED, 8L, result, "", false, false);
        assertEquals(R.string.plate_solve_solved,
                MainActivity.solveStatusLabel(retained, 20L, true));
        // An admission rejection publishes a new intent but no new solver snapshot.
        assertEquals(R.string.plate_solve_rejected,
                MainActivity.solveStatusLabel(retained, 21L, false));
        assertSame(result, retained.getResult());
        assertEquals(R.string.plate_solve_solving,
                MainActivity.solveStatusLabel(SolveSnapshot.of(
                        SolveSnapshot.State.SOLVING, 9L, null, "", false, false),
                        22L, true));
        assertEquals(0, MainActivity.solveStatusLabel(SolveSnapshot.idle(), -1L, false));
    }

    @Test
    public void solveDetailsAreAlwaysAvailableAndDefaultCollapsedWithoutDuplicatingResults()
            throws Exception {
        Document layout = readResourceXml("layout/activity_main.xml");
        Element tracker = findByAndroidId(layout, "star_tracker_panel");
        Element coordinates = findByAndroidId(layout, "star_tracker_last_solve_coordinates");
        Element toggle = findByAndroidId(layout, "solve_details_toggle");
        Element details = findByAndroidId(layout, "solve_details_panel");
        assertSame(tracker, toggle.getParentNode());
        assertSame(tracker, details.getParentNode());
        assertSame(findByAndroidId(layout, "capture_settings_toggle"),
                nextElementSibling(findByAndroidId(layout, "star_tracker_location_row")));
        assertSame(findByAndroidId(layout, "detector_settings_toggle"),
                nextElementSibling(findByAndroidId(layout, "capture_settings_panel")));
        assertSame(toggle, nextElementSibling(findByAndroidId(layout, "detector_settings_panel")));
        assertSame(details, nextElementSibling(toggle));
        assertSame(findByAndroidId(layout, "solver_settings_toggle"), nextElementSibling(details));
        Element zeroAttitude = findByAndroidId(layout, "zero_camera_altaz_button");
        assertSame(tracker, zeroAttitude.getParentNode());
        assertSame(null, nextElementSibling(zeroAttitude));
        assertEquals("@string/solve_details_collapsed", androidAttribute(toggle, "text"));
        assertEquals("", androidAttribute(toggle, "visibility"));
        assertEquals("gone", androidAttribute(details, "visibility"));
        assertEquals("vertical", androidAttribute(details, "orientation"));

        for (String diagnosticId : new String[]{
                "star_visualization", "star_match_legend", "star_result_summary",
                "star_capture_details", "plate_solve_result"}) {
            assertSame(details, findByAndroidId(layout, diagnosticId).getParentNode());
        }
        Element imageResult = findByAndroidId(layout, "plate_solve_result");
        for (String diagnosticId : new String[]{
                "plate_solve_coordinates", "plate_solve_orientation",
                "plate_solve_lens_source", "plate_solve_duration"}) {
            assertSame(imageResult, findByAndroidId(layout, diagnosticId).getParentNode());
        }
        Element quality = findByAndroidId(layout, "plate_solve_quality");
        assertSame(coordinates, quality.getParentNode());
        assertEquals("gone", androidAttribute(quality, "visibility"));
        assertEquals("no", androidAttribute(quality, "importantForAccessibility"));
        Element focal = findByAndroidId(layout, "plate_solve_focal_comparison");
        assertSame(coordinates, focal.getParentNode());
        assertSame(focal, nextElementSibling(quality));
        assertEquals("gone", androidAttribute(focal, "visibility"));
        assertEquals("false", androidAttribute(focal, "singleLine"));
        assertEquals("", androidAttribute(focal, "maxLines"));
        assertEquals("", androidAttribute(focal, "ellipsize"));
        assertEquals("no", androidAttribute(focal, "importantForAccessibility"));

        for (String settingsId : new String[]{
                "capture_settings_toggle", "capture_settings_panel",
                "detector_settings_toggle", "detector_settings_panel",
                "solver_settings_toggle", "solver_settings_panel"}) {
            assertSame(tracker, findByAndroidId(layout, settingsId).getParentNode());
        }
        for (String duplicateId : new String[]{
                "plate_solve_status", "plate_solve_detail"}) {
            assertFalse(hasAndroidId(layout, duplicateId));
        }
        assertSame(tracker, findByAndroidId(layout, "star_status").getParentNode());

        Map<String, String> strings = readStrings();
        assertEquals("Solve details ▸", strings.get("solve_details_collapsed"));
        assertEquals("Solve details ▾", strings.get("solve_details_expanded"));
        assertEquals("Image roll %1$s · FOV %2$s", strings.get("plate_solve_orientation"));
        assertEquals("Focal %1$.2f mm · supplied %2$.2f mm\\n%3$+.2f mm (%4$+.2f%%)",
                strings.get("plate_solve_focal_comparison"));
        assertEquals("Last solve · RA %1$s · Dec %2$s", strings.get("last_solve_radec_format"));
        assertEquals("Calculation %1$s", strings.get("plate_solve_duration"));
        assertEquals("@string/last_solve_radec_unavailable",
                androidAttribute(findByAndroidId(layout, "plate_solve_coordinates"), "text"));
    }

    @Test
    public void shutterScreenStaysFlatWithOneCompactActionAndIbisBar() throws Exception {
        Document layout = readResourceXml("layout/activity_main.xml");
        assertFalse(hasAndroidId(layout, "mode_tabs"));
        assertFalse(hasAndroidId(layout, "shutter_tab"));
        assertFalse(hasAndroidId(layout, "star_tracker_tab"));

        Element actionBar = findByAndroidId(layout, "shutter_action_bar");
        Element halfPress = findByAndroidId(layout, "half_press_button");
        Element shutter = findByAndroidId(layout, "shutter_button");
        Element lock = findByAndroidId(layout, "shutter_lock_target");
        assertSame(actionBar, halfPress.getParentNode());
        assertSame(actionBar, shutter.getParentNode());
        assertSame(actionBar, lock.getParentNode());
        assertEquals("horizontal", androidAttribute(actionBar, "orientation"));
        for (Element segment : new Element[]{halfPress, shutter, lock}) {
            assertEquals("0dp", androidAttribute(segment, "layout_width"));
            assertEquals("1", androidAttribute(segment, "layout_weight"));
            assertEquals("match_parent", androidAttribute(segment, "layout_height"));
        }
        assertEquals(
                "80dp",
                readNamedValues("values/dimens.xml", "dimen")
                        .get("shutter_action_bar_height"));
        for (Element segment : new Element[]{halfPress, shutter, lock}) {
            assertEquals("16sp", androidAttribute(segment, "textSize"));
        }
        assertEquals(
                "@string/half_press_accessibility",
                androidAttribute(halfPress, "contentDescription"));
        assertEquals("@color/shutter_action_text", androidAttribute(halfPress, "textColor"));
        assertEquals(
                "@color/shutter_action_primary_text",
                androidAttribute(shutter, "textColor"));
        assertEquals("@color/shutter_action_text", androidAttribute(lock, "textColor"));
        assertEquals("true", androidAttribute(lock, "clickable"));
        assertEquals("true", androidAttribute(lock, "focusable"));
        assertEquals("yes", androidAttribute(lock, "importantForAccessibility"));
        assertEquals(
                "@string/shutter_lock_accessibility",
                androidAttribute(lock, "contentDescription"));

        assertFalse(hasAndroidId(layout, "lock_drop_zone"));
        assertFalse(hasAndroidId(layout, "held_timer"));
        assertFalse(hasAndroidId(layout, "release_button"));

        for (String panelId : new String[]{
                "camera_exposure_settings_panel", "remote_panel", "shutter_astro_panel",
                "star_tracker_panel"}) {
            assertEquals("", androidAttribute(findByAndroidId(layout, panelId), "background"));
        }

        for (String drawable : new String[]{
                "bg_shutter_action_left.xml", "bg_shutter_action_center.xml",
                "bg_shutter_action_right.xml"}) {
            assertTrue(hasDisabledState(readResourceXml("drawable/" + drawable)));
        }
        assertTrue(hasDisabledState(readResourceXml("color/shutter_action_text.xml")));
        assertTrue(hasDisabledState(readResourceXml("color/shutter_action_primary_text.xml")));

        Map<String, String> strings = readStrings();
        for (String stringId : new String[]{
                "shutter_accessibility", "shutter_unlock_accessibility",
                "shutter_lock_accessibility", "shutter_locked_duration_accessibility"}) {
            String instruction = strings.get(stringId).toLowerCase(java.util.Locale.US);
            assertFalse(instruction.contains("left"));
            assertFalse(instruction.contains("right"));
        }

        Element settings = findByAndroidId(layout, "astro_settings_button");
        assertEquals("48dp", androidAttribute(settings, "layout_width"));
        assertEquals("48dp", androidAttribute(settings, "layout_height"));
        assertEquals("@string/astro_settings", androidAttribute(settings, "contentDescription"));
        Element astroHeading = (Element) settings.getParentNode();
        assertSame(findByAndroidId(layout, "shutter_astro_panel"), astroHeading.getParentNode());
        assertSame(
                astroHeading,
                findByAndroidId(layout, "shutter_correction_enabled").getParentNode());

        Element correctionStatus = findByAndroidId(layout, "shutter_correction_status");
        Element diagnostics = findByAndroidId(layout, "shutter_correction_diagnostics");
        assertSame(findByAndroidId(layout, "shutter_astro_panel"), diagnostics.getParentNode());
        assertSame(
                diagnostics,
                nextElementSibling(findByAndroidId(layout, "shutter_location_row")));
        assertEquals("vertical", androidAttribute(diagnostics, "orientation"));
        assertEquals("gone", androidAttribute(diagnostics, "visibility"));
        assertEquals("yes", androidAttribute(diagnostics, "importantForAccessibility"));
        assertEquals(
                "@string/shutter_correction_diagnostics_unavailable",
                androidAttribute(diagnostics, "contentDescription"));
        assertEquals("", androidAttribute(diagnostics, "background"));
        for (String diagnosticId : new String[]{
                "shutter_correction_stars_used",
                "shutter_correction_rms_error",
                "shutter_correction_max_exposure",
                "shutter_correction_remaining_exposure"}) {
            Element diagnostic = findByAndroidId(layout, diagnosticId);
            assertSame(diagnostics, diagnostic.getParentNode().getParentNode());
            assertEquals("0dp", androidAttribute(diagnostic, "layout_width"));
            assertEquals("1", androidAttribute(diagnostic, "layout_weight"));
            assertEquals("12sp", androidAttribute(diagnostic, "textSize"));
            assertEquals("@color/text_secondary", androidAttribute(diagnostic, "textColor"));
            assertEquals("1", androidAttribute(diagnostic, "maxLines"));
            assertEquals("true", androidAttribute(diagnostic, "singleLine"));
            assertEquals("end", androidAttribute(diagnostic, "ellipsize"));
            assertEquals("no", androidAttribute(diagnostic, "importantForAccessibility"));
            assertEquals("", androidAttribute(diagnostic, "background"));
        }
        Element starsUsed = findByAndroidId(layout, "shutter_correction_stars_used");
        Element rmsError = findByAndroidId(layout, "shutter_correction_rms_error");
        Element maxExposure = findByAndroidId(layout, "shutter_correction_max_exposure");
        Element remainingExposure =
                findByAndroidId(layout, "shutter_correction_remaining_exposure");
        assertSame(starsUsed.getParentNode(), rmsError.getParentNode());
        assertSame(maxExposure.getParentNode(), remainingExposure.getParentNode());
        assertNotSame(starsUsed.getParentNode(), maxExposure.getParentNode());
        assertEquals(
                "horizontal",
                androidAttribute((Element) starsUsed.getParentNode(), "orientation"));
        assertEquals(
                "horizontal",
                androidAttribute((Element) maxExposure.getParentNode(), "orientation"));

        Element remotePanel = findByAndroidId(layout, "remote_panel");
        Element controlsScreen = findByAndroidId(layout, "controls_screen");
        Element shutterPage = findByAndroidId(layout, "shutter_page");
        Element starPanel = findByAndroidId(layout, "star_tracker_panel");
        Element starHeader = findByAndroidId(layout, "star_tracker_header");
        Element starBack = findByAndroidId(layout, "star_tracker_back_button");
        Element starHeading = findByAndroidId(layout, "star_tracker_page_heading");
        Element controlsFooter = findByAndroidId(layout, "controls_footer");
        Element ibisPanel = findByAndroidId(layout, "shutter_ibis_panel");
        Element ibis = findByAndroidId(layout, "shutter_ibis_visualization");
        assertSame(shutterPage.getParentNode(), starPanel.getParentNode());
        assertSame(controlsScreen, shutterPage.getParentNode());
        assertSame(controlsScreen, starPanel.getParentNode());
        assertSame(controlsScreen, controlsFooter.getParentNode());
        assertFalse(isDescendantOf(starPanel, shutterPage));
        assertTrue(isDescendantOf(settings, shutterPage));
        assertSame(starPanel, starHeader.getParentNode());
        assertSame(starHeader, starBack.getParentNode());
        assertSame(starHeader, starHeading.getParentNode());
        assertEquals("48dp", androidAttribute(starBack, "layout_height"));
        assertEquals("@string/back_to_shutter", androidAttribute(starBack, "text"));
        assertEquals("@string/star_tracker_heading", androidAttribute(starHeading, "text"));
        assertFalse(isDescendantOf(ibis, remotePanel));
        assertFalse(isDescendantOf(ibisPanel, shutterPage));
        assertFalse(isDescendantOf(ibis, starPanel));
        assertTrue(isDescendantOf(ibis, ibisPanel));
        assertSame(controlsFooter, ibisPanel.getParentNode());
        assertFalse(hasAndroidId(layout, "ibis_status"));
        assertFalse(hasAndroidId(layout, "ibis_visualization"));
        assertEquals("gone", androidAttribute(starPanel, "visibility"));
        assertEquals(
                "112dp",
                readNamedValues("values/dimens.xml", "dimen")
                        .get("compact_ibis_visualization_height"));
        assertSame(findByAndroidId(layout, "night_mode_button"), nextElementSibling(ibisPanel));
    }

    @Test
    public void verboseOperationalStatesAreReducedToOneLine() {
        assertEquals(
                "Correction sent",
                MainActivity.compactCorrectionStatus(
                        "Correction published to Linux; physical application is unconfirmed"));
        assertEquals(
                "Applying…",
                MainActivity.compactCorrectionStatus(
                        "Solved; confirming camera state before applying correction"));
        assertEquals(
                "Applying…",
                MainActivity.compactCorrectionStatus(
                        "Confirming terminal correction stop (2/3)"));
        assertEquals(
                "Capturing…",
                MainActivity.compactStarStatus(
                        "Received 12 peaks; waiting for terminal result"));
        assertEquals(
                "Too close to vertical",
                MainActivity.compactCorrectionStatus(
                        "Correction not applied: Too close to vertical"));
        assertEquals(
                "RA/Dec doesn't match camera tilt",
                MainActivity.compactCorrectionStatus(
                        "Correction not applied: RA/Dec doesn't match camera tilt"));
    }

    @Test
    public void manualGravityContextIsRequiredOnlyForTheSelectedCorrectionPath() {
        assertTrue(MainActivity.shouldUseManualAttitude(false, false));
        assertTrue(MainActivity.shouldUseManualAttitude(true, true));
        assertFalse(MainActivity.shouldUseManualAttitude(false, true));

        assertFalse(MainActivity.shouldShowManualAttitudeDetails(false, false));
        assertFalse(MainActivity.shouldShowManualAttitudeDetails(false, true));
        assertTrue(MainActivity.shouldShowManualAttitudeDetails(true, false));
        assertTrue(MainActivity.shouldShowManualAttitudeDetails(true, true));

        assertTrue(MainActivity.shouldRequestManualSolveContext(true, true, 1));
        assertFalse(MainActivity.shouldRequestManualSolveContext(false, true, 100));
        assertFalse(MainActivity.shouldRequestManualSolveContext(true, false, 100));
        assertFalse(MainActivity.shouldRequestManualSolveContext(true, true, 0));
    }

    @Test
    public void ibisStateDropsRawTelemetryCommentary() {
        int stable = StarTelemetry.IBIS_FLAG_SOURCE_MAPPED
                | StarTelemetry.IBIS_FLAG_MIRROR_ENABLED
                | StarTelemetry.IBIS_FLAG_DOUBLE_READ_STABLE;
        assertEquals(
                "Stable",
                MainActivity.compactIbisStatus(
                        new StarTelemetry.IbisSample(stable, 1, 0, 0, 0)));
        assertEquals("Unavailable", MainActivity.compactIbisStatus(null));
    }

    private static Document readResourceXml(String relativePath) throws Exception {
        Path path = findResourcePath(relativePath);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static Path findResourcePath(String relativePath) {
        Path[] candidates = {
                Path.of("src/main/res", relativePath),
                Path.of("app/src/main/res", relativePath),
                Path.of("phone/app/src/main/res", relativePath)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new AssertionError("Missing Android resource " + relativePath);
    }

    private static Element findByAndroidId(Document document, String id) {
        NodeList elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            String value = androidAttribute(element, "id");
            if (("@+id/" + id).equals(value) || ("@id/" + id).equals(value)) {
                return element;
            }
        }
        throw new AssertionError("Missing Android view " + id);
    }

    private static boolean hasAndroidId(Document document, String id) {
        NodeList elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            String value = androidAttribute(element, "id");
            if (("@+id/" + id).equals(value) || ("@id/" + id).equals(value)) return true;
        }
        return false;
    }

    private static boolean hasDisabledState(Document document) {
        NodeList items = document.getElementsByTagName("item");
        for (int index = 0; index < items.getLength(); index++) {
            if ("false".equals(androidAttribute((Element) items.item(index), "state_enabled"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDescendantOf(Node child, Node ancestor) {
        Node current = child;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParentNode();
        }
        return false;
    }

    private static Element nextElementSibling(Node node) {
        Node next = node.getNextSibling();
        while (next != null && !(next instanceof Element)) next = next.getNextSibling();
        return (Element) next;
    }

    private static Element findLabelFor(Element parent, String id) {
        NodeList elements = parent.getElementsByTagName("TextView");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (("@id/" + id).equals(androidAttribute(element, "labelFor"))) {
                return element;
            }
        }
        return null;
    }

    private static String androidAttribute(Element element, String name) {
        return element.getAttributeNS(ANDROID_NAMESPACE, name);
    }

    private static Map<String, String> readStrings() throws Exception {
        return readNamedValues("values/strings.xml", "string");
    }

    private static Map<String, String> readNamedValues(String relativePath, String tag)
            throws Exception {
        Document document = readResourceXml(relativePath);
        Map<String, String> strings = new HashMap<>();
        NodeList elements = document.getElementsByTagName(tag);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            strings.put(element.getAttribute("name"), element.getTextContent());
        }
        return strings;
    }
}

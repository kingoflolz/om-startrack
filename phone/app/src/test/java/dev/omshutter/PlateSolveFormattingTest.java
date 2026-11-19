package dev.omshutter;

import static org.junit.Assert.assertEquals;

import dev.omshutter.solver.SolveResult;
import dev.omshutter.solver.PlanarFocalCalibration;

import java.util.Locale;

import org.junit.Test;

public final class PlateSolveFormattingTest {
    private static final String FOCAL_PATTERN =
            "Focal %1$.2f mm · supplied %2$.2f mm\n%3$+.2f mm (%4$+.2f%%)";

    @Test
    public void rightAscensionNormalizesAndCarriesRoundedSeconds() {
        assertEquals("00h 00m 00.0s", PlateSolveFormatting.rightAscension(360d, Locale.US));
        assertEquals("23h 00m 00.0s", PlateSolveFormatting.rightAscension(-15d, Locale.US));
        assertEquals(
                "01h 00m 00.0s",
                PlateSolveFormatting.rightAscension(14.9999999d, Locale.US));
    }

    @Test
    public void declinationPreservesSignAndCarriesRoundedSeconds() {
        assertEquals(
                "+12° 30′ 00.0″",
                PlateSolveFormatting.declination(12.5d, Locale.US));
        assertEquals(
                "−12° 30′ 00.0″",
                PlateSolveFormatting.declination(-12.5d, Locale.US));
        assertEquals(
                "+13° 00′ 00.0″",
                PlateSolveFormatting.declination(12.9999999d, Locale.US));
        assertEquals(
                "−00° 00′ 00.0″",
                PlateSolveFormatting.declination(-0d, Locale.US));
    }

    @Test
    public void invalidAnglesUseUnavailableMarker() {
        assertEquals("—", PlateSolveFormatting.rightAscension(Double.NaN, Locale.US));
        assertEquals("—", PlateSolveFormatting.declination(90.1d, Locale.US));
        assertEquals("—", PlateSolveFormatting.degrees(Double.POSITIVE_INFINITY, Locale.US));
        assertEquals("—", PlateSolveFormatting.arcseconds(Double.NaN, Locale.US));
    }

    @Test
    public void focalComparisonShowsBothSolveValuesAndSignedDifference() {
        assertEquals("Focal 303.00 mm · supplied 300.00 mm\n+3.00 mm (+1.00%)",
                focalComparison(calibratedResult(300.0, 303.0), Locale.US));
        assertEquals("Focal 297.00 mm · supplied 300.00 mm\n-3.00 mm (-1.00%)",
                focalComparison(calibratedResult(300.0, 297.0), Locale.US));
        assertEquals("Focal 300.00 mm · supplied 300.00 mm\n+0.00 mm (+0.00%)",
                focalComparison(calibratedResult(300.0, 300.0), Locale.US));
    }

    @Test
    public void focalComparisonUsesLocaleAndAvoidsRoundedNegativeZero() {
        assertEquals("Focal 303,00 mm · supplied 300,00 mm\n+3,00 mm (+1,00%)",
                focalComparison(calibratedResult(300.0, 303.0), Locale.GERMANY));
        assertEquals("Focal 300.00 mm · supplied 300.00 mm\n+0.00 mm (+0.00%)",
                focalComparison(calibratedResult(300.0, 299.99999), Locale.US));
    }

    @Test
    public void focalComparisonClearsWithoutAResultOrLegacyCalibration() {
        assertEquals("", focalComparison(null, Locale.US));
        SolveResult legacy = new SolveResult(120.0, 25.0, 10.0, 3.0, 0.8,
                new int[]{0, 1, 2, 3}, new int[]{4, 5});
        assertEquals("", focalComparison(legacy, Locale.US));
    }

    @Test
    public void focalComparisonFollowsDisplayedSolveAcrossClearingAndReplacement() {
        LastSolveCoordinateAssociation association = new LastSolveCoordinateAssociation();
        SolveResult first = calibratedResult(300.0, 303.0);
        association.observe(first, 41L, 2);
        assertEquals("Focal 303.00 mm · supplied 300.00 mm\n+3.00 mm (+1.00%)",
                focalComparison(association.getDisplayedResult(), Locale.US));

        // Admission clears the old solve; an unsuccessful attempt supplies no new result.
        association.observe(null, -1L, 2);
        assertEquals("", focalComparison(association.getDisplayedResult(), Locale.US));

        SolveResult replacement = calibratedResult(150.0, 147.0);
        association.observe(replacement, 42L, 2);
        assertEquals("Focal 147.00 mm · supplied 150.00 mm\n-3.00 mm (-2.00%)",
                focalComparison(association.getDisplayedResult(), Locale.US));
        assertEquals("Focal 303.00 mm · supplied 300.00 mm\n+3.00 mm (+1.00%)",
                focalComparison(first, Locale.US));
    }

    private static String focalComparison(SolveResult result, Locale locale) {
        return PlateSolveFormatting.focalComparison(result, FOCAL_PATTERN, locale);
    }

    private static SolveResult calibratedResult(double supplied, double solved) {
        return new SolveResult(120.0, 25.0, 10.0, 3.0, 0.8,
                new int[]{0, 1, 2, 3}, new int[]{4, 5},
                PlanarFocalCalibration.of(supplied, solved));
    }
}

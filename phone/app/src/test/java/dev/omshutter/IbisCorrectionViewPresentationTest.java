package dev.omshutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

public final class IbisCorrectionViewPresentationTest {
    private static final String ANDROID_NAMESPACE =
            "http://schemas.android.com/apk/res/android";

    @Test
    public void liveAxesUseNeutralTelemetryColorInsteadOfGreenAccent() throws Exception {
        Document colors = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(findMainPath("res/values/colors.xml").toFile());
        String meter = colorValue(colors, "ibis_meter");
        String accent = colorValue(colors, "accent");

        assertEquals("#AAB7C2", meter.toUpperCase());
        assertFalse(meter.equalsIgnoreCase(accent));

        String source = readMainText("java/dev/omshutter/IbisCorrectionView.java");
        assertTrue(source.contains("R.color.ibis_meter"));
        assertFalse(source.contains("R.color.accent"));
    }

    @Test
    public void sharedIbisFrameAvoidsPickerSelectionHighlight() throws Exception {
        Document layout = readMainXml("res/layout/activity_main.xml");
        Element view = findByAndroidId(layout, "shutter_ibis_visualization");
        assertEquals(
                "@drawable/bg_ibis_visualization",
                view.getAttributeNS(ANDROID_NAMESPACE, "background"));

        String frame = readMainText("res/drawable/bg_ibis_visualization.xml");
        assertTrue(frame.contains("@color/bg"));
        assertTrue(frame.contains("@color/panel_stroke"));
        assertFalse(frame.contains("@color/accent"));
        assertFalse(frame.contains("layer-list"));
    }

    @Test
    public void compactHeightDistributesThreeBodyAxesInsideFrame() throws Exception {
        float top = 4f;
        float bottom = 108f;
        float x = IbisCorrectionView.rowCenter(top, bottom, 0);
        float y = IbisCorrectionView.rowCenter(top, bottom, 1);
        float roll = IbisCorrectionView.rowCenter(top, bottom, 2);

        assertTrue(x > top);
        assertTrue(roll < bottom);
        assertEquals(y - x, roll - y, 0.001f);
        assertEquals(56f, y, 0.001f);
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> IbisCorrectionView.rowCenter(top, bottom, 3));

        String dimensions = readMainText("res/values/dimens.xml");
        assertTrue(dimensions.contains(
                "<dimen name=\"compact_ibis_visualization_height\">112dp</dimen>"));
    }

    @Test
    public void q10RowsRenderOnlyFrozenDegreeConversions() throws Exception {
        assertEquals(
                Math.toDegrees(Math.atan(0.6 / 300.0)),
                IbisCorrectionView.planarQ10Degrees(1024, 300.0),
                1.0e-12);
        assertEquals(
                5325.0 / 8192.0,
                IbisCorrectionView.rollQ10Degrees(1024),
                1.0e-12);
        assertTrue(Double.isNaN(IbisCorrectionView.planarQ10Degrees(1, Double.NaN)));
        assertEquals("—°", IbisCorrectionView.formatDegrees(Double.NaN));
        assertEquals("+0.650°", IbisCorrectionView.formatDegrees(5325.0 / 8192.0));

        String source = readMainText("java/dev/omshutter/IbisCorrectionView.java");
        assertFalse(source.contains("Lens yaw"));
        assertFalse(source.contains("Lens pitch"));
        assertFalse(source.contains("latest cached lens command"));
        assertFalse(source.contains("Integer.toString(values"));
    }

    private static String colorValue(Document document, String name) {
        NodeList colors = document.getElementsByTagName("color");
        for (int index = 0; index < colors.getLength(); index++) {
            Element color = (Element) colors.item(index);
            if (name.equals(color.getAttribute("name"))) {
                return color.getTextContent().trim();
            }
        }
        throw new AssertionError("Missing color " + name);
    }

    private static Document readMainXml(String relativePath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(findMainPath(relativePath).toFile());
    }

    private static Element findByAndroidId(Document document, String id) {
        NodeList elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            String value = element.getAttributeNS(ANDROID_NAMESPACE, "id");
            if (("@+id/" + id).equals(value) || ("@id/" + id).equals(value)) {
                return element;
            }
        }
        throw new AssertionError("Missing Android view " + id);
    }

    private static String readMainText(String relativePath) throws Exception {
        return new String(
                Files.readAllBytes(findMainPath(relativePath)),
                StandardCharsets.UTF_8);
    }

    private static Path findMainPath(String relativePath) {
        Path[] candidates = {
                Path.of("src/main", relativePath),
                Path.of("app/src/main", relativePath),
                Path.of("phone/app/src/main", relativePath)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new AssertionError("Missing Android main source " + relativePath);
    }
}

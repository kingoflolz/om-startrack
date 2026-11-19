package dev.omshutter.protocol;

/** State test double, independent of the transport codec. */
public final class ExposureFixture implements CameraExposureState {
    private final String shutter, aperture, iso, minimum, maximum;
    public ExposureFixture(String shutter, String aperture, String iso,
                           String minimum, String maximum) {
        this.shutter = shutter;
        this.aperture = aperture;
        this.iso = iso;
        this.minimum = minimum;
        this.maximum = maximum;
    }
    public int getShutterValue() { return 1; }
    public int getApertureValue() { return 2; }
    public int getIsoValue() { return 3; }
    public boolean hasFocalLength() { return true; }
    public int getFocalLengthBits() { return Float.floatToRawIntBits(24f); }
    public float getFocalLengthMm() { return 24f; }
    public String getShutter() { return shutter; }
    public String getAperture() { return aperture; }
    public String getIso() { return iso; }
    public String apertureMinLabel() { return minimum; }
    public String apertureMaxLabel() { return maximum; }
    public boolean hasLensName() { return false; }
    public String getLensName() { return ""; }
}

package dev.omshutter.protocol;

/**
 * Resolved authoritative shutter/aperture/ISO settings shared by legacy capture metadata views
 * and the STAR indexed STATE surface. This is not exposure timing telemetry.
 */
public interface CameraExposureState {
    int getShutterValue();
    int getApertureValue();
    int getIsoValue();
    boolean hasFocalLength();
    int getFocalLengthBits();
    float getFocalLengthMm();
    String getShutter();
    String getAperture();
    String getIso();
    String apertureMinLabel();
    String apertureMaxLabel();
    boolean hasLensName();
    String getLensName();
}

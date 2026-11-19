package dev.omshutter.solver;

/**
 * Local apparent sidereal motion in a stationary, horizontally stratified atmosphere.
 *
 * <p>The solved center is a catalog (unrefracted) direction. Output axes are its tangent
 * frame parallel-transported toward the zenith to the apparent center. The absolute
 * refraction offset is not added to pointing. Apply this to the unquantized celestial
 * rate before transporting that rate between camera attitudes or quantizing wire lanes.
 * The local symmetric distortion cannot be corrected by rigid sensor translation/roll.
 */
public final class AtmosphericRefractionCorrection {
    public static final double MIN_TRUE_ALTITUDE_DEG = 10.0;

    public enum Status { APPLIED, BELOW_MINIMUM_ALTITUDE }

    /** Explicit optical observing conditions; site pressure already incorporates elevation. */
    public static final class Atmosphere {
        private final double pressureHpa;
        private final double temperatureC;
        private final double relativeHumidity;
        private final double wavelengthMicrometers;
        private final double a;
        private final double b;

        private Atmosphere(double pressureHpa, double temperatureC,
                double relativeHumidity, double wavelengthMicrometers) {
            this.pressureHpa = pressureHpa;
            this.temperatureC = temperatureC;
            this.relativeHumidity = relativeHumidity;
            this.wavelengthMicrometers = wavelengthMicrometers;
            if (pressureHpa == 0.0) {
                a = 0.0;
                b = 0.0;
                return;
            }
            // Optical ERFA/SOFA refco equations; provenance and license below.
            double saturation = Math.pow(10.0,
                    (0.7859 + 0.03477 * temperatureC) / (1.0 + 0.00412 * temperatureC))
                    * (1.0 + pressureHpa
                    * (4.5e-6 + 6.0e-10 * temperatureC * temperatureC));
            double vaporPressure = relativeHumidity * saturation
                    / (1.0 - (1.0 - relativeHumidity) * saturation / pressureHpa);
            if (!(vaporPressure >= 0.0 && vaporPressure <= pressureHpa)) {
                throw new IllegalArgumentException("water vapor pressure exceeds total pressure");
            }
            double kelvin = temperatureC + 273.15;
            double wavelengthSquared = wavelengthMicrometers * wavelengthMicrometers;
            double refractivity = ((77.53484e-6
                    + (4.39108e-7 + 3.666e-9 / wavelengthSquared) / wavelengthSquared)
                    * pressureHpa - 11.2684e-6 * vaporPressure) / kelvin;
            double scaleHeightRatio = 4.4474e-6 * kelvin;
            a = refractivity * (1.0 - scaleHeightRatio);
            b = -refractivity * (scaleHeightRatio - refractivity / 2.0);
        }

        /** Sea-level estimate, not measured weather: 1013.25 hPa, 15 C, 50% RH, 550 nm. */
        public static Atmosphere standard() {
            return of(1013.25, 15.0, 0.5, 0.55);
        }

        public static Atmosphere of(double pressureHpa, double temperatureC,
                double relativeHumidity, double wavelengthMicrometers) {
            requireRange(pressureHpa, 0.0, 1200.0, "pressure");
            requireRange(temperatureC, -80.0, 60.0, "temperature");
            requireRange(relativeHumidity, 0.0, 1.0, "relative humidity");
            requireRange(wavelengthMicrometers, 0.3, 2.0, "optical wavelength");
            return new Atmosphere(pressureHpa, temperatureC,
                    relativeHumidity, wavelengthMicrometers);
        }

        public double getPressureHpa() { return pressureHpa; }
        public double getTemperatureC() { return temperatureC; }
        public double getRelativeHumidity() { return relativeHumidity; }
        public double getWavelengthMicrometers() { return wavelengthMicrometers; }
        public double getTanCoefficientRadians() { return a; }
        public double getTanCubedCoefficientRadians() { return b; }
    }

    public static final class Result {
        private final Status status;
        private final double trueAltitudeDeg;
        private final double apparentAltitudeDeg;
        private final SiderealRateCorrection.BodyAngularVelocity bodyAngularVelocity;

        private Result(Status status, double trueAltitudeDeg, double apparentAltitudeDeg,
                SiderealRateCorrection.BodyAngularVelocity bodyAngularVelocity) {
            this.status = status;
            this.trueAltitudeDeg = trueAltitudeDeg;
            this.apparentAltitudeDeg = apparentAltitudeDeg;
            this.bodyAngularVelocity = bodyAngularVelocity;
        }

        public Status getStatus() { return status; }
        public boolean isApplied() { return status == Status.APPLIED; }
        public double getTrueAltitudeDeg() { return trueAltitudeDeg; }
        /** NaN when the below-altitude guard prevents evaluating the model. */
        public double getApparentAltitudeDeg() { return apparentAltitudeDeg; }
        public SiderealRateCorrection.BodyAngularVelocity getBodyAngularVelocity() {
            return bodyAngularVelocity;
        }
    }

    private AtmosphericRefractionCorrection() {}

    /** Local zenith in the same right/down/forward ICRS camera basis as the solve. */
    public static double[] zenithBody(double rightAscensionDeg, double declinationDeg,
            double imageUpPositionAngleDeg, ObserverCoordinateContext observer,
            long elapsedRealtimeNanos) {
        if (observer == null) throw new IllegalArgumentException("observer is unavailable");
        requireFinite(rightAscensionDeg, "right ascension");
        requireRange(declinationDeg, -90.0, 90.0, "declination");
        requireFinite(imageUpPositionAngleDeg, "image-up position angle");
        ObserverCoordinateContext.UtcInstant utc = observer.utcAtCaptureElapsed(elapsedRealtimeNanos);
        double[] zenith = GravityRollEstimator.celestialZenithComponents(
                Math.toRadians(Math.IEEEremainder(rightAscensionDeg, 360.0)),
                Math.toRadians(declinationDeg), Math.toRadians(observer.getLatitudeDeg()),
                Math.toRadians(observer.getLongitudeEastDeg()),
                utc.getEpochSecond(), utc.getNano());
        double angle = Math.toRadians(Math.IEEEremainder(imageUpPositionAngleDeg, 360.0));
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return new double[] {
                -zenith[0] * cos + zenith[1] * sin,
                -zenith[0] * sin - zenith[1] * cos,
                zenith[2]
        };
    }

    /**
     * Pushes the rigid celestial velocity field through the refraction map analytically.
     *
     * <p>For true zenith distance t and apparent distance u, t=u+A tan(u)+B tan^3(u).
     * Its two tangent scales are alpha=du/dt and beta=sin(u)/sin(t). In a tangent basis
     * p toward zenith and h=forward cross p, angular components become beta*omega_p,
     * alpha*omega_h. Half the local velocity curl supplies roll: C*omega_forward +
     * (cos(u)-C*cos(t))/sin(t)*omega_p, where C=(beta/alpha+alpha/beta)/2.
     * This includes the apparent-frame orientation exactly once.
     *
     * <p>Below 10 degrees true altitude the original rate is returned with an explicit
     * status. No extrapolation through the horizon is attempted.
     */
    public static Result correct(SiderealRateCorrection.BodyAngularVelocity unrefracted,
            double[] zenithBody, Atmosphere atmosphere) {
        if (unrefracted == null) throw new IllegalArgumentException("body rate is unavailable");
        if (atmosphere == null) throw new IllegalArgumentException("atmosphere is unavailable");
        if (zenithBody == null || zenithBody.length != 3) {
            throw new IllegalArgumentException("zenith must contain three components");
        }
        for (double component : zenithBody) requireFinite(component, "zenith component");
        double norm = Math.hypot(Math.hypot(zenithBody[0], zenithBody[1]), zenithBody[2]);
        if (!(norm > 0.0) || !Double.isFinite(norm)) {
            throw new IllegalArgumentException("zenith has invalid norm");
        }
        double zx = zenithBody[0] / norm, zy = zenithBody[1] / norm;
        double zz = zenithBody[2] / norm;
        double sinTrue = Math.hypot(zx, zy);
        double trueDistance = Math.atan2(sinTrue, zz);
        double trueAltitude = 90.0 - Math.toDegrees(trueDistance);
        if (trueAltitude < MIN_TRUE_ALTITUDE_DEG - 1e-12) {
            return new Result(Status.BELOW_MINIMUM_ALTITUDE, trueAltitude, Double.NaN,
                    unrefracted);
        }
        if (atmosphere.a == 0.0 && atmosphere.b == 0.0) {
            return new Result(Status.APPLIED, trueAltitude, trueAltitude, unrefracted);
        }
        double apparentDistance = apparentZenithDistance(trueDistance, atmosphere);
        double tangent = Math.tan(apparentDistance);
        double alpha = 1.0 / (1.0 + (atmosphere.a
                + 3.0 * atmosphere.b * tangent * tangent) * (1.0 + tangent * tangent));
        double x = unrefracted.getXRadiansPerSecond();
        double y = unrefracted.getYRadiansPerSecond();
        double z = unrefracted.getZRadiansPerSecond();
        SiderealRateCorrection.BodyAngularVelocity corrected;
        if (sinTrue == 0.0) {
            // Axial symmetry at zenith: shrink translations equally; preserve roll.
            corrected = SiderealRateCorrection.BodyAngularVelocity.of(alpha * x, alpha * y, z);
        } else {
            double beta = Math.sin(apparentDistance) / sinTrue;
            double px = zx / sinTrue, py = zy / sinTrue;
            double towardZenith = x * px + y * py;
            // Stable forms avoid subtracting near-equal cosines or C-1 near zenith.
            double difference = beta - alpha;
            double cMinusOne = difference * difference / (2.0 * alpha * beta);
            double tilt = (2.0 * Math.sin((trueDistance + apparentDistance) / 2.0)
                    * Math.sin((trueDistance - apparentDistance) / 2.0)
                    - cMinusOne * zz) / sinTrue;
            corrected = SiderealRateCorrection.BodyAngularVelocity.of(
                    alpha * x + difference * towardZenith * px,
                    alpha * y + difference * towardZenith * py,
                    (1.0 + cMinusOne) * z + tilt * towardZenith);
        }
        return new Result(Status.APPLIED, trueAltitude,
                90.0 - Math.toDegrees(apparentDistance), corrected);
    }

    /**
     * Re-evaluates apparent motion at a qualified sensor displacement in the original image.
     * The celestial pole and zenith must both be frozen in the solve's capture-time frame.
     * This result already includes the planar gnomonic adjustment and local roll; do not
     * subsequently apply {@link SolveLatchedQ10RateAdjuster} to it.
     */
    public static Result correctAtGnomonic(
            SiderealRateCorrection.BodyAngularVelocity unrefracted, double[] frozenZenithBody,
            Atmosphere atmosphere, double imageRight, double imageDown) {
        requireFinite(imageRight, "image-right offset");
        requireFinite(imageDown, "image-down offset");
        if (Math.hypot(imageRight, imageDown) > 0.02) {
            throw new IllegalArgumentException("image offset exceeds the qualified radius");
        }
        Result center = correct(unrefracted, frozenZenithBody, atmosphere);
        if (imageRight == 0.0 && imageDown == 0.0) return center;
        SiderealRateCorrection.BodyAngularVelocity geometric = geometricAtGnomonic(
                unrefracted, imageRight, imageDown);
        if (!center.isApplied() || atmosphere.a == 0.0 && atmosphere.b == 0.0) {
            return new Result(center.status, center.trueAltitudeDeg,
                    center.apparentAltitudeDeg, geometric);
        }

        // Express the frozen physical vectors in the original APPARENT camera frame.
        // This is a basis rotation, not a second application of the refraction Jacobian.
        double norm = Math.hypot(Math.hypot(frozenZenithBody[0], frozenZenithBody[1]),
                frozenZenithBody[2]);
        double zx = frozenZenithBody[0] / norm, zy = frozenZenithBody[1] / norm;
        double radial = Math.hypot(zx, zy);
        double px = radial == 0.0 ? 1.0 : zx / radial;
        double py = radial == 0.0 ? 0.0 : zy / radial;
        double apparentDistance = Math.toRadians(90.0 - center.apparentAltitudeDeg);
        double bend = Math.toRadians(center.apparentAltitudeDeg - center.trueAltitudeDeg);
        double[] zenith = {Math.sin(apparentDistance) * px,
                Math.sin(apparentDistance) * py, Math.cos(apparentDistance)};
        double x = unrefracted.getXRadiansPerSecond(), y = unrefracted.getYRadiansPerSecond();
        double z = unrefracted.getZRadiansPerSecond();
        double along = x * px + y * py;
        double shiftedAlong = Math.cos(bend) * along - Math.sin(bend) * z;
        double[] omega = {x + (shiftedAlong - along) * px,
                y + (shiftedAlong - along) * py,
                Math.sin(bend) * along + Math.cos(bend) * z};

        Dual u = new Dual(imageRight, 1, 0), v = new Dual(imageDown, 0, 1);
        Dual normRay = u.times(u).plus(v.times(v)).plus(1).sqrt();
        Dual[] ray = {u.over(normRay), v.over(normRay), normRay.reciprocal()};
        Dual cosine = dot(zenith, ray);
        double observed = Math.acos(Math.max(-1, Math.min(1, cosine.value)));
        double tangentValue = Math.tan(observed);
        double truth = observed + (atmosphere.a + atmosphere.b * tangentValue * tangentValue)
                * tangentValue;
        double trueAltitude = 90.0 - Math.toDegrees(truth);
        if (trueAltitude < MIN_TRUE_ALTITUDE_DEG - 1e-12) {
            return new Result(Status.BELOW_MINIMUM_ALTITUDE, trueAltitude, Double.NaN, geometric);
        }

        // Analytic derivative of the spherical pushforward, evaluated with two tangent duals.
        Dual sineSquared = cosine.times(cosine).times(-1).plus(1);
        Dual secantSquared = cosine.times(cosine).reciprocal();
        Dual alpha = sineSquared.times(secantSquared).times(3 * atmosphere.b)
                .plus(atmosphere.a).times(secantSquared).plus(1).reciprocal();
        Dual kappa;
        if (sineSquared.value < 1e-8) {
            // Continuous zenith series; omitted terms are O(sin^2(z)) in this coefficient.
            double g = 1 + atmosphere.a;
            kappa = Dual.constant((g * g - 1) / (3 * g)
                    - 2 * (atmosphere.a / 3 + atmosphere.b) / (g * g));
        } else {
            Dual sine = sineSquared.sqrt();
            Dual tangent = sine.over(cosine);
            Dual refraction = tangent.times(tangent).times(atmosphere.b)
                    .plus(atmosphere.a).times(tangent);
            Dual cosBend = refraction.cos();
            Dual sinBendOverSine = refraction.sin().over(sine);
            Dual beta = cosBend.plus(cosine.times(sinBendOverSine)).reciprocal();
            Dual cosTrue = cosine.times(cosBend).minus(sineSquared.times(sinBendOverSine));
            kappa = alpha.times(cosine).minus(beta.times(cosTrue)).over(sineSquared);
        }
        double omegaZenith = omega[0] * zenith[0] + omega[1] * zenith[1] + omega[2] * zenith[2];
        Dual extra = kappa.times(dot(omega, ray)).plus(
                Dual.constant(1).minus(alpha).minus(kappa.times(cosine)).times(omegaZenith));
        Dual[] rigid = cross(omega, ray), azimuth = cross(zenith, ray);
        Dual[] velocity = new Dual[3];
        for (int i = 0; i < 3; i++) velocity[i] = alpha.times(rigid[i]).plus(extra.times(azimuth[i]));
        Dual imageVelocityX = velocity[0].minus(u.times(velocity[2])).over(ray[2]);
        Dual imageVelocityY = velocity[1].minus(v.times(velocity[2])).over(ray[2]);
        return new Result(Status.APPLIED, trueAltitude, 90.0 - Math.toDegrees(observed),
                SiderealRateCorrection.BodyAngularVelocity.of(-imageVelocityY.value,
                        imageVelocityX.value, (imageVelocityY.dx - imageVelocityX.dy) / 2));
    }

    private static SiderealRateCorrection.BodyAngularVelocity geometricAtGnomonic(
            SiderealRateCorrection.BodyAngularVelocity omega, double u, double v) {
        double x = omega.getXRadiansPerSecond(), y = omega.getYRadiansPerSecond();
        double z = omega.getZRadiansPerSecond();
        return SiderealRateCorrection.BodyAngularVelocity.of(
                x * (1 + v * v) - y * u * v - z * u,
                y * (1 + u * u) - x * u * v - z * v,
                z + (x * u + y * v) / 2);
    }

    private static Dual dot(double[] vector, Dual[] other) {
        return other[0].times(vector[0]).plus(other[1].times(vector[1])).plus(other[2].times(vector[2]));
    }

    private static Dual[] cross(double[] vector, Dual[] other) {
        return new Dual[]{other[2].times(vector[1]).minus(other[1].times(vector[2])),
                other[0].times(vector[2]).minus(other[2].times(vector[0])),
                other[1].times(vector[0]).minus(other[0].times(vector[1]))};
    }

    /** Value and exact first derivatives with respect to the two fixed image coordinates. */
    private static final class Dual {
        final double value, dx, dy;
        Dual(double value, double dx, double dy) { this.value = value; this.dx = dx; this.dy = dy; }
        static Dual constant(double value) { return new Dual(value, 0, 0); }
        Dual plus(double other) { return new Dual(value + other, dx, dy); }
        Dual plus(Dual other) { return new Dual(value + other.value, dx + other.dx, dy + other.dy); }
        Dual minus(Dual other) { return plus(other.times(-1)); }
        Dual times(double other) { return new Dual(value * other, dx * other, dy * other); }
        Dual times(Dual other) {
            return new Dual(value * other.value, dx * other.value + value * other.dx,
                    dy * other.value + value * other.dy);
        }
        Dual over(Dual other) { return times(other.reciprocal()); }
        Dual reciprocal() {
            double inverse = 1 / value;
            return new Dual(inverse, -dx * inverse * inverse, -dy * inverse * inverse);
        }
        Dual sqrt() {
            double root = Math.sqrt(value);
            return new Dual(root, dx / (2 * root), dy / (2 * root));
        }
        Dual sin() { return new Dual(Math.sin(value), Math.cos(value) * dx, Math.cos(value) * dy); }
        Dual cos() { return new Dual(Math.cos(value), -Math.sin(value) * dx, -Math.sin(value) * dy); }
    }

    private static double apparentZenithDistance(double trueDistance, Atmosphere atmosphere) {
        double apparent = trueDistance;
        // Above the guarded altitude the monotone, near-identity map converges in a few steps.
        for (int iteration = 0; iteration < 8; iteration++) {
            double tangent = Math.tan(apparent);
            double square = tangent * tangent;
            double residual = apparent + (atmosphere.a + atmosphere.b * square) * tangent
                    - trueDistance;
            double derivative = 1.0 + (atmosphere.a + 3.0 * atmosphere.b * square)
                    * (1.0 + square);
            apparent -= residual / derivative;
        }
        return apparent;
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " is not finite");
    }

    private static void requireRange(double value, double low, double high, String field) {
        requireFinite(value, field);
        if (value < low || value > high) {
            throw new IllegalArgumentException(field + " is outside " + low + ".." + high);
        }
    }
}

/* Optical coefficient equations adapted from ERFA refco (derived from SOFA):
 * https://github.com/liberfa/erfa/blob/master/src/refco.c
 * Copyright (C) 2013-2023, NumFOCUS Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 1 Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * 2 Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * 3 Neither the name of the Standards Of Fundamental Astronomy Board, the
 *   International Astronomical Union nor the names of its contributors may be
 *   used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

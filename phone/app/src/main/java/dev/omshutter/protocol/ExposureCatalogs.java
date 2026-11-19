package dev.omshutter.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable camera-authored exposure labels used by the Android settings UI. */
public final class ExposureCatalogs {
    public static final int MAX_OPTIONS_PER_KIND = StarCaptureProtocol.MAX_EXPOSURE_OPTIONS;
    public static final int MAX_ENCODED_BYTES = StarCaptureProtocol.MAX_ATTRIBUTE_VALUE_BYTES;

    public enum Kind {
        SHUTTER,
        APERTURE,
        ISO
    }

    /** One exact camera-authored label. The label is the option's stable identity. */
    public static final class Option {
        private final Kind kind;
        private final String label;

        public Option(Kind kind, String label) {
            if (kind == null) throw new IllegalArgumentException("option kind is null");
            requireLabel(label, "option label");
            this.kind = kind;
            this.label = label;
        }

        public Kind getKind() {
            return kind;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Option)) return false;
            Option that = (Option) other;
            return kind == that.kind && label.equals(that.label);
        }

        @Override
        public int hashCode() {
            return 31 * kind.hashCode() + label.hashCode();
        }
    }

    /** A complete exposure choice expressed only as exact camera-authored labels. */
    public static final class Selection {
        private final String shutter;
        private final String aperture;
        private final String iso;

        public Selection(String shutter, String aperture, String iso) {
            requireLabel(shutter, "shutter label");
            requireLabel(aperture, "aperture label");
            requireLabel(iso, "ISO label");
            this.shutter = shutter;
            this.aperture = aperture;
            this.iso = iso;
        }

        public static Selection from(CameraExposureState state) {
            if (state == null) throw new IllegalArgumentException("camera state is null");
            return new Selection(state.getShutter(), state.getAperture(), state.getIso());
        }

        public String getShutter() {
            return shutter;
        }

        public String getAperture() {
            return aperture;
        }

        public String getIso() {
            return iso;
        }

        public String get(Kind kind) {
            if (kind == Kind.SHUTTER) return shutter;
            if (kind == Kind.APERTURE) return aperture;
            if (kind == Kind.ISO) return iso;
            throw new IllegalArgumentException("selection kind is null");
        }

        /** Returns true only when all three exact labels equal the latest STATE value. */
        public boolean matches(CameraExposureState state) {
            return state != null
                    && shutter.equals(state.getShutter())
                    && aperture.equals(state.getAperture())
                    && iso.equals(state.getIso());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Selection)) return false;
            Selection that = (Selection) other;
            return shutter.equals(that.shutter)
                    && aperture.equals(that.aperture)
                    && iso.equals(that.iso);
        }

        @Override
        public int hashCode() {
            return Objects.hash(shutter, aperture, iso);
        }
    }

    private final EnumMap<Kind, List<Option>> options = new EnumMap<>(Kind.class);

    public ExposureCatalogs(
            List<Option> shutter,
            List<Option> aperture,
            List<Option> iso) {
        options.put(Kind.SHUTTER, checkedCopy(Kind.SHUTTER, shutter));
        options.put(Kind.APERTURE, checkedCopy(Kind.APERTURE, aperture));
        options.put(Kind.ISO, checkedCopy(Kind.ISO, iso));
    }

    public List<Option> get(Kind kind) {
        List<Option> result = options.get(kind);
        if (result == null) throw new IllegalArgumentException("catalog kind is null");
        return result;
    }

    public Option findByLabel(Kind kind, String label) {
        if (label == null) return null;
        for (Option option : get(kind)) {
            if (option.label.equals(label)) return option;
        }
        return null;
    }

    public Selection toSelection(Option shutter, Option aperture, Option iso) {
        requireMember(shutter, Kind.SHUTTER);
        requireMember(aperture, Kind.APERTURE);
        requireMember(iso, Kind.ISO);
        return new Selection(shutter.label, aperture.label, iso.label);
    }

    public boolean contains(Selection selection) {
        if (selection == null) return false;
        for (Kind kind : Kind.values()) {
            if (findByLabel(kind, selection.get(kind)) == null) return false;
        }
        return true;
    }

    private void requireMember(Option option, Kind kind) {
        if (option == null || option.kind != kind
                || !option.equals(findByLabel(kind, option.label))) {
            throw new IllegalArgumentException(
                    "selection is not in the active " + kind + " catalog");
        }
    }

    private static List<Option> checkedCopy(Kind kind, List<Option> source) {
        if (source == null || source.isEmpty() || source.size() > MAX_OPTIONS_PER_KIND) {
            throw new IllegalArgumentException(kind + " catalog must contain 1..128 options");
        }
        ArrayList<Option> copy = new ArrayList<>(source.size());
        Set<String> labels = new HashSet<>();
        int encodedBytes = 1;
        for (Option option : source) {
            if (option == null || option.kind != kind) {
                throw new IllegalArgumentException(kind + " catalog contains a foreign option");
            }
            if (!labels.add(option.label)) {
                throw new IllegalArgumentException(kind + " catalog has duplicate labels");
            }
            encodedBytes += 1 + asciiLength(option.label);
            copy.add(option);
        }
        if (encodedBytes > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException(kind + " catalog exceeds 512 encoded bytes");
        }
        return Collections.unmodifiableList(copy);
    }

    private static void requireLabel(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is null");
        int length = asciiLength(value);
        if (length < 1 || length > StarCaptureProtocol.MAX_EXPOSURE_LABEL_BYTES) {
            throw new IllegalArgumentException(field + " must contain 1..31 ASCII bytes");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException(field + " is not printable camera ASCII");
            }
        }
    }

    private static int asciiLength(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }
}

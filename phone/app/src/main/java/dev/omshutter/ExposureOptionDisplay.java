package dev.omshutter;

import dev.omshutter.protocol.ExposureCatalogs;

/** Presentation-only formatting for exact camera-authored exposure labels. */
final class ExposureOptionDisplay {
    private ExposureOptionDisplay() {}

    static String format(ExposureCatalogs.Option option) {
        if (option == null) throw new IllegalArgumentException("option is null");
        return option.getKind() == ExposureCatalogs.Kind.SHUTTER
                ? formatShutter(option.getLabel())
                : option.getLabel();
    }

    static String formatShutter(String label) {
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("shutter label is empty");
        }
        if (label.endsWith("\"")) {
            return label.substring(0, label.length() - 1) + "s";
        }
        if (isUnsignedNumber(label)) {
            return "1/" + label + "s";
        }
        if (isFraction(label)) return label + "s";
        return label;
    }

    private static boolean isUnsignedInteger(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') return false;
        }
        return !value.isEmpty();
    }

    private static boolean isUnsignedNumber(String value) {
        int decimal = value.indexOf('.');
        if (decimal < 0) return isUnsignedInteger(value);
        return decimal > 0
                && decimal == value.lastIndexOf('.')
                && decimal < value.length() - 1
                && isUnsignedInteger(value.substring(0, decimal))
                && isUnsignedInteger(value.substring(decimal + 1));
    }

    private static boolean isFraction(String value) {
        int slash = value.indexOf('/');
        return slash > 0
                && slash == value.lastIndexOf('/')
                && slash < value.length() - 1
                && isUnsignedInteger(value.substring(0, slash))
                && isUnsignedInteger(value.substring(slash + 1));
    }
}

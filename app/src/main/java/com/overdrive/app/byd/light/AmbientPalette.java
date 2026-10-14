package com.overdrive.app.byd.light;

public abstract class AmbientPalette {
    public static final String[] PALETTE_7 = new String[]{
        "#7FD4FF", "#EAF6FF", "#FFE2B0", "#FF5A1F", "#FF0000", "#D0208F", "#0030FF"
    };

    // Upstream compat alias
    public static final String[] a = PALETTE_7;

    /**
     * Converts an RGB color integer to CIE-Lab coordinates [L*, a*, b*].
     */
    public static double[] toLab(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        int max = Math.max(r, Math.max(g, b));
        if (max == 0) {
            return null;
        }

        double scale = 255.0 / (double) max;
        double rNorm = gammaCorrect(((double) r * scale) / 255.0);
        double gNorm = gammaCorrect(((double) g * scale) / 255.0);
        double bNorm = gammaCorrect(((double) b * scale) / 255.0);

        // Standard sRGB to XYZ (D65 illuminant)
        double x = (rNorm * 0.4124564 + gNorm * 0.3575761 + bNorm * 0.1804375) / 0.95047;
        double y = (rNorm * 0.2126729 + gNorm * 0.7151522 + bNorm * 0.0721750);
        double z = (rNorm * 0.0193339 + gNorm * 0.1191920 + bNorm * 0.9503041) / 1.08883;

        double fx = fLab(x);
        double fy = fLab(y);
        double fz = fLab(z);

        double lVal = 116.0 * fy - 16.0;
        double aVal = 500.0 * (fx - fy);
        double bVal = 200.0 * (fy - fz);

        return new double[]{lVal, aVal, bVal};
    }

    private static double fLab(double t) {
        if (t > 0.008856) {
            return Math.cbrt(t);
        }
        return (903.3 * t + 16.0) / 116.0;
    }

    private static double gammaCorrect(double c) {
        if (c <= 0.04045) {
            return c / 12.92;
        }
        return Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /**
     * Returns ambient palette based on count and capability.
     */
    public static String[] getPalette(int count, boolean allowSeven) {
        if (count == 31) {
            return LightConstants.PALETTE_31;
        }
        if (allowSeven && count == 7) {
            return PALETTE_7;
        }
        return null;
    }

    /**
     * Parses a hex color string like "#FFAABB" into an RGB int, or -1 on error.
     */
    public static int parseHex(String hex) {
        if (hex == null) return -1;
        hex = hex.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6) return -1;
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (Exception e) {
            return -1;
        }
    }

    // Upstream compat aliases
    public static double[] a(int rgb) { return toLab(rgb); }
    public static double b(double t) { return fLab(t); }
    public static double c(double c) { return gammaCorrect(c); }
    public static String[] d(int count, boolean allowSeven) { return getPalette(count, allowSeven); }
    public static int e(String hex) { return parseHex(hex); }
}

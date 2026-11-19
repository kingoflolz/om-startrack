package dev.omshutter.plate;

import java.util.Arrays;

/** Deterministic genuine-star fixture for the OM-1 8 mm fisheye field. */
final class NativeTetra3Android8mm256Fixture {
    static final int SOURCE_COUNT = 256;
    static final String CAMERA_LENS_NAME = "OLYMPUS M.8mm F1.8";
    static final String EXPECTED_PROFILE_ID =
            "lensfun-v2.om1.active-1296x972|lens=7:Olympus48:Olympus M.Zuiko Digital ED 8mm f/1.8 Fisheye Pro";
    static final double FOCAL_LENGTH_MM = 8.0;
    static final double PIXEL_PITCH_UM = 3.3;
    static final double EXPECTED_RA_DEG = 222.492235949962151;
    static final double EXPECTED_DEC_DEG = 38.682187453489441;
    static final double EXPECTED_ROLL_DEG = 111.246117974981075;
    static final double EXPECTED_HORIZONTAL_FOV_DEG = 131.584915420666761;
    static final double MAX_INDEXED_PATTERN_EDGE_DEG = 54.311536022068303;
    static final double BRIGHTEST_FOUR_SPAN_DEG = 80.894609772859638;
    private static final int[] WIRE_XY = {
        430, 494, 1176, 165, 348, 118, 624, 29, 1128, 108, 1138, 676, 73, 220,
        398, 231, 346, 849, 575, 345, 994, 180, 953, 299, 816, 870, 989, 618,
        27, 700, 321, 38, 234, 656, 311, 657, 597, 204, 767, 809, 1263, 345,
        131, 81, 3, 506, 46, 938, 968, 0, 791, 227, 772, 331, 686, 902,
        1295, 358, 1108, 423, 5, 146, 8, 366, 165, 29, 39, 665, 345, 105,
        532, 83, 1197, 4, 953, 113, 595, 711, 632, 519, 1213, 155, 1081, 167,
        865, 519, 935, 782, 978, 586, 1236, 597, 9, 476, 52, 864, 212, 540,
        262, 848, 368, 935, 512, 274, 553, 493, 935, 70, 617, 414, 765, 289,
        674, 470, 1133, 180, 1139, 191, 705, 834, 797, 771, 1194, 355, 1291, 822,
        915, 581, 994, 463, 1293, 471, 316, 179, 212, 826, 380, 548, 791, 16,
        590, 370, 801, 109, 923, 84, 586, 959, 857, 175, 1267, 188, 1069, 266,
        981, 351, 940, 917, 882, 648, 76, 121, 103, 78, 22, 902, 205, 121,
        198, 173, 151, 405, 110, 622, 158, 529, 219, 340, 187, 742, 291, 358,
        389, 208, 415, 406, 483, 267, 499, 245, 565, 208, 451, 931, 524, 454,
        814, 92, 570, 590, 1221, 93, 674, 581, 894, 268, 1264, 189, 791, 403,
        1081, 244, 776, 648, 887, 473, 1237, 936, 881, 633, 998, 895, 998, 638,
        1076, 757, 1120, 759, 59, 82, 49, 172, 37, 248, 187, 75, 188, 77,
        164, 141, 97, 390, 258, 31, 192, 411, 181, 589, 183, 947, 188, 857,
        310, 316, 262, 603, 264, 911, 281, 869, 583, 98, 376, 678, 541, 282,
        431, 669, 761, 44, 730, 126, 546, 410, 487, 768, 488, 665, 1104, 0,
        722, 208, 841, 108, 1100, 20, 1133, 53, 887, 138, 999, 109, 627, 756,
        1195, 122, 671, 735, 800, 373, 1220, 199, 842, 364, 763, 574, 776, 672,
        876, 949, 909, 464, 1260, 372, 1263, 406, 1271, 405, 1152, 420, 924, 731,
        929, 727, 1289, 688, 1121, 486, 1124, 818, 1085, 609, 1167, 667, 52, 39,
        14, 319, 177, 34, 218, 37, 236, 35, 43, 660, 90, 863, 237, 193,
        142, 495, 120, 669, 333, 71, 417, 9, 167, 870, 250, 474, 410, 230,
        533, 94, 512, 146, 398, 413, 469, 287, 706, 14, 395, 558, 432, 415,
        646, 89, 755, 30, 786, 22, 525, 407, 501, 494, 484, 676, 548, 485,
        809, 115, 616, 370, 1220, 58, 664, 415, 940, 128, 999, 120, 1064, 98,
        941, 153, 1229, 109, 703, 420, 784, 326, 777, 380, 701, 572, 690, 717,
        972, 256, 1282, 240, 1086, 225, 1153, 291, 1195, 306, 1060, 340, 1135, 317,
        1177, 366, 994, 936, 1232, 877, 1167, 433, 993, 860, 1224, 481, 1013, 654,
        1036, 603, 1244, 563, 1117, 641, 1175, 630, 1126, 707, 2, 141, 40, 107,
        133, 44, 124, 101, 139, 71, 141, 58, 127, 133, 217, 14, 128, 216,
        44, 526, 16, 781, 66, 448, 264, 3, 151, 242, 69, 509, 129, 378,
        339, 8, 245, 308, 165, 905, 506, 0, 223, 915, 227, 751, 248, 960,
        261, 809, 345, 392, 450, 185, 506, 103,
    };
    private static final int[] EXPECTED_PATTERN_SOURCE_IDS = {
        36, 37, 40, 41,
    };
    private static final int[] EXPECTED_FAR_VALIDATION_SOURCE_IDS = {
        0, 2, 6, 8, 12, 14, 15, 16, 17, 19, 21, 22,
        23, 27, 30, 31, 32, 33, 34, 38, 43, 46, 47, 48,
        49, 50, 59, 60, 62, 66, 67, 68, 73, 78, 80, 81,
        82, 83, 84, 85, 86, 87, 88, 89, 90, 92, 96, 99,
        106, 108, 110, 114, 115, 116, 117, 118, 119, 120, 121, 122,
        123, 124, 125, 126, 127, 128, 129, 131, 133, 137, 138, 146,
        148, 153, 154, 160, 164, 167, 168, 169, 170, 171, 172, 173,
        174, 175, 176, 177, 179, 180, 184, 187, 188, 193, 194, 209,
        218, 219, 221, 229, 230, 231, 232, 233, 234, 235, 236, 237,
        238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 249, 250,
        251, 252, 253,
    };
    private static final int[] FLUX_BITS = {
        0x3f800000, 0x3ea1e89b, 0x3e224b06, 0x3e00e9f9, 0x3dcccccd, 0x3da2adad, 0x3d813856, 0x3d4d494a,
        0x3d231090, 0x3d0186e2, 0x3ccdc614, 0x3ca373af, 0x3c81d59f,
    };
    private static final int[] FLUX_COUNTS = {
        1, 1, 4, 8, 7, 9, 16, 20, 14, 34, 53, 62, 27,
    };
    private static final double[] XY = makeXy();
    private static final float[] FLUX = makeFlux();

    static {
        if (WIRE_XY.length != SOURCE_COUNT * 2
                || FLUX.length != SOURCE_COUNT) {
            throw new AssertionError("Invalid generated 8 mm fixture shape");
        }
    }

    private NativeTetra3Android8mm256Fixture() {}

    static int size() {
        return SOURCE_COUNT;
    }

    static double[] xy() {
        return XY;
    }

    static float[] flux() {
        return FLUX;
    }

    static int[] expectedPatternSourceIds() {
        return EXPECTED_PATTERN_SOURCE_IDS;
    }

    static int[] expectedFarValidationSourceIds() {
        return EXPECTED_FAR_VALIDATION_SOURCE_IDS;
    }

    private static double[] makeXy() {
        double[] xy = new double[WIRE_XY.length];
        for (int index = 0; index < WIRE_XY.length; index++) {
            xy[index] = WIRE_XY[index];
        }
        return xy;
    }

    private static float[] makeFlux() {
        float[] flux = new float[SOURCE_COUNT];
        int start = 0;
        for (int level = 0; level < FLUX_BITS.length; level++) {
            Arrays.fill(
                    flux,
                    start,
                    start + FLUX_COUNTS[level],
                    Float.intBitsToFloat(FLUX_BITS[level]));
            start += FLUX_COUNTS[level];
        }
        if (start != SOURCE_COUNT) {
            throw new AssertionError("Invalid flux groups");
        }
        return flux;
    }

}

package dev.omshutter.plate;

import java.util.Arrays;

/** Deterministic genuine-star fixture for the exact OM-1 300 mm field. */
final class NativeTetra3Android256Fixture {
    private static final int SOURCE_COUNT = 256;

    // Generated from the production index by validate-solves for horizontal FOV
    // 3.2663496137060335 degrees, pointing 0/8 and its first deterministic roll. These are the
    // integer 1296x972 STAR wire centroids before JNI's encoded-pixel phase correction.
    private static final int[] WIRE_XY = {
        599, 393, 498, 576, 498, 723, 455, 302, 627, 908, 904, 654, 542, 741,
        368, 370, 1100, 290, 988, 269, 767, 52, 1216, 912, 964, 611, 559, 541,
        432, 35, 38, 688, 562, 768, 1170, 313, 980, 364, 404, 864, 1196, 572,
        669, 644, 1247, 196, 881, 170, 1184, 529, 1101, 612, 1008, 504, 279,
        599, 132, 393, 1184, 151, 703, 96, 572, 203, 146, 832, 492, 721, 130,
        594, 1232, 34, 1231, 99, 1295, 316, 945, 108, 171, 764, 1148, 954,
        557, 317, 477, 399, 493, 681, 136, 199, 1216, 113, 1282, 301, 892, 81,
        906, 314, 870, 389, 724, 31, 620, 936, 1120, 687, 936, 858, 722, 827,
        333, 747, 1241, 59, 1197, 247, 1251, 373, 911, 170, 230, 28, 864, 474,
        812, 433, 561, 478, 9, 617, 77, 232, 1216, 200, 1238, 346, 1103, 74,
        943, 313, 1054, 430, 909, 143, 805, 400, 668, 6, 529, 853, 610, 945,
        548, 881, 374, 833, 364, 897, 1263, 933, 1243, 949, 1054, 567, 1076,
        640, 856, 648, 819, 750, 906, 906, 850, 899, 499, 488, 471, 650, 512,
        713, 1122, 22, 1127, 198, 1007, 255, 846, 8, 869, 308, 563, 22, 514,
        243, 305, 178, 176, 116, 521, 905, 351, 837, 239, 909, 33, 852, 31,
        854, 1057, 679, 975, 618, 986, 654, 840, 833, 636, 577, 701, 794, 598,
        797, 579, 449, 604, 498, 604, 530, 421, 559, 426, 297, 462, 299, 308,
        598, 208, 620, 301, 720, 202, 271, 70, 523, 165, 667, 20, 459, 1222,
        409, 1041, 2, 948, 146, 528, 165, 343, 53, 43, 80, 24, 83, 643, 905,
        476, 943, 148, 851, 1193, 785, 1208, 901, 1131, 605, 1183, 668, 1083,
        947, 1011, 477, 832, 651, 903, 725, 813, 467, 833, 526, 724, 681, 661,
        618, 553, 330, 622, 377, 639, 447, 642, 469, 500, 582, 524, 652, 418,
        713, 539, 715, 307, 305, 367, 372, 432, 420, 264, 471, 385, 490, 354,
        516, 278, 538, 322, 710, 141, 513, 207, 554, 84, 591, 1274, 50, 1242,
        299, 1156, 466, 1103, 63, 1037, 35, 1012, 32, 1010, 106, 1007, 178,
        1011, 371, 880, 377, 843, 406, 730, 53, 742, 83, 740, 134, 525, 243,
        373, 104, 79, 46, 56, 54, 45, 75, 118, 141, 497, 865, 291, 900, 410,
        916, 34, 795, 73, 813, 65, 917, 1268, 718, 1251, 745, 1211, 808, 1191,
        820, 1130, 941, 1087, 566, 1102, 869, 1006, 841, 953, 834, 918, 453,
        896, 491, 946, 643, 952, 660, 950, 760, 839, 419, 845, 432, 721, 463,
        770, 616, 771, 625, 687, 694, 658, 382, 617, 470, 560, 549, 417, 616,
        478, 726, 523, 753, 457, 352, 261, 662, 281, 682, 167, 705, 127, 449,
        114, 583, 1, 198, 71, 194, 62, 201, 1273, 513, 1201, 23, 1157, 82,
        1078, 25, 1007, 193, 1042, 346, 866, 31, 693, 72, 723, 80, 742, 165,
        641, 264, 574, 279, 677, 307, 516, 236, 10, 92, 561, 922, 495, 845,
        465, 852, 357, 942, 83, 911, 187, 958, 48, 814, 1183, 786, 1237, 885,
        1210, 953, 1137, 888, 1131, 656, 1029, 614, 1085, 681, 1090, 754
    };

    private static final double[] XY = makeXy();
    private static final float[] FLUX = makeFlux();

    static {
        if (WIRE_XY.length != SOURCE_COUNT * 2
                || FLUX.length != SOURCE_COUNT) {
            throw new AssertionError("Invalid generated 256-source fixture shape");
        }
    }

    private NativeTetra3Android256Fixture() {}

    static int size() {
        return SOURCE_COUNT;
    }

    // JNI reads these synchronously and does not retain or mutate them. Returning the generated
    // arrays directly keeps allocation/copy time out of the solve-only benchmark.
    static double[] xy() {
        return XY;
    }

    static float[] flux() {
        return FLUX;
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
        float[] levels = {
            1_000_000.0f, 794_328.2f, 398_107.2f, 316_227.8f, 251_188.64f,
            199_526.23f, 158_489.31f, 125_892.53f, 100_000.0f, 79_432.82f,
            63_095.73f, 50_118.72f, 39_810.72f, 31_622.775f, 25_118.865f,
            19_952.623f, 15_848.933f, 12_589.254f
        };
        int[] counts = {1, 3, 1, 3, 3, 4, 4, 5, 8, 7, 12, 10, 14, 25, 32, 54, 55, 15};
        int start = 0;
        for (int level = 0; level < levels.length; level++) {
            Arrays.fill(flux, start, start + counts[level], levels[level]);
            start += counts[level];
        }
        if (start != SOURCE_COUNT) {
            throw new AssertionError("Invalid generated fixture flux groups");
        }
        return flux;
    }
}

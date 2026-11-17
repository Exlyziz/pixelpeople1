package com.pixelpeople;

/**
 * Steve (64x64 skin, classic arms) split into 1x1x1 pixel cubes. Only the surface cubes exist.
 * Character space (pixels): +X = character's left, +Y = up, +Z = forward, origin = between the feet.
 * Cube grid index inside a part: cx 0 = character's right, cy 0 = top, cz 0 = front.
 * Faces: 0 front(+z) 1 back(-z) 2 right(-x) 3 left(+x) 4 top(+y) 5 bottom(-y).
 */
public final class PixelModel {
    public static final int HEAD = 0, BODY = 1, R_ARM = 2, L_ARM = 3, R_LEG = 4, L_LEG = 5, PARTS = 6;
    /** Blocks per pixel (player model is rendered at 15/16 scale). */
    public static final float SCALE = 0.9375f / 16f;

    // w, h, d, skinU, skinV, minX, maxY, maxZ
    private static final int[][] DEF = {
            {8, 8, 8, 0, 0, -4, 32, 4},    // head
            {8, 12, 4, 16, 16, -4, 24, 2}, // body
            {4, 12, 4, 40, 16, -8, 24, 2}, // right arm
            {4, 12, 4, 32, 48, 4, 24, 2},  // left arm
            {4, 12, 4, 0, 16, -4, 12, 2},  // right leg
            {4, 12, 4, 16, 48, 0, 12, 2},  // left leg
    };
    public static final float[][] PIVOT = {
            {0, 24, 0}, {0, 12, 0}, {-6, 22, 0}, {6, 22, 0}, {-2, 12, 0}, {2, 12, 0}
    };

    public static final int COUNT;
    /** First cube index of each part; START[PARTS] == COUNT. */
    public static final int[] START = new int[PARTS + 1];
    public static final byte[] PART;
    public static final float[] CX, CY, CZ;
    /** COUNT*6 entries: texel x | (texel y << 8), or -1 when that face is not exposed. */
    public static final int[] TEX;

    static {
        int n = 0;
        for (int p = 0; p < PARTS; p++) {
            START[p] = n;
            int w = DEF[p][0], h = DEF[p][1], d = DEF[p][2];
            for (int cx = 0; cx < w; cx++)
                for (int cy = 0; cy < h; cy++)
                    for (int cz = 0; cz < d; cz++)
                        if (surface(cx, cy, cz, w, h, d)) n++;
        }
        START[PARTS] = n;
        COUNT = n;
        PART = new byte[n];
        CX = new float[n];
        CY = new float[n];
        CZ = new float[n];
        TEX = new int[n * 6];
        java.util.Arrays.fill(TEX, -1);

        int i = 0;
        for (int p = 0; p < PARTS; p++) {
            int w = DEF[p][0], h = DEF[p][1], d = DEF[p][2], u = DEF[p][3], v = DEF[p][4];
            int minX = DEF[p][5], maxY = DEF[p][6], maxZ = DEF[p][7];
            for (int cx = 0; cx < w; cx++)
                for (int cy = 0; cy < h; cy++)
                    for (int cz = 0; cz < d; cz++) {
                        if (!surface(cx, cy, cz, w, h, d)) continue;
                        PART[i] = (byte) p;
                        CX[i] = minX + cx + 0.5f;
                        CY[i] = maxY - cy - 0.5f;
                        CZ[i] = maxZ - cz - 0.5f;
                        int b = i * 6;
                        if (cz == 0)     TEX[b]     = pack(u + d + cx, v + d + cy);
                        if (cz == d - 1) TEX[b + 1] = pack(u + 2 * d + w + (w - 1 - cx), v + d + cy);
                        if (cx == 0)     TEX[b + 2] = pack(u + (d - 1 - cz), v + d + cy);
                        if (cx == w - 1) TEX[b + 3] = pack(u + d + w + cz, v + d + cy);
                        if (cy == 0)     TEX[b + 4] = pack(u + d + cx, v + (d - 1 - cz));
                        if (cy == h - 1) TEX[b + 5] = pack(u + d + w + cx, v + cz);
                        i++;
                    }
        }
    }

    private static boolean surface(int cx, int cy, int cz, int w, int h, int d) {
        return cx == 0 || cx == w - 1 || cy == 0 || cy == h - 1 || cz == 0 || cz == d - 1;
    }

    private static int pack(int x, int y) {
        return x | (y << 8);
    }

    private PixelModel() {}
}

package com.pixelpeople;

import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Computes the pose of every body part in character space (pixels).
 * Shared by the renderer and by the debris spawner so flying pixels start exactly where they were drawn.
 */
public final class PixelPose {
    // Rest angles of the limp "doll" while lying down (blended in by the lie amount).
    private static final float[] REST_X = {0.15f, 0f, 0.35f, 0.35f, 0.05f, 0.05f};
    private static final float[] REST_Z = {0f, 0f, -0.5f, 0.5f, -0.18f, 0.18f};

    public static float restX(int i) { return REST_X[i]; }
    public static float restZ(int i) { return REST_Z[i]; }

    public static void compute(PixelPerson e, float pt, Matrix4f[] out) {
        float lie = Mth.lerp(pt, e.lieO, e.lie);
        float sit = Mth.lerp(pt, e.sitO, e.sit);
        float drop = Mth.lerp(pt, e.dropO, e.drop);
        float panic = Mth.lerp(pt, e.panicO, e.panic);
        float bounce = Mth.lerp(pt, e.bounceO, e.bounce);
        float twist = Mth.lerp(pt, e.twistO, e.twist);
        float side = e.isFaceDown() ? 1f : -1f; // -1: falls on its back, +1: falls on its face
        float age = e.tickCount + pt;

        float push = 0f;
        if (e.getState() == PixelPerson.GETTING_UP) {
            push = Mth.sin(Mth.PI * Math.min(1f, (e.stateTicks + pt) / PixelPerson.GETUP_TICKS));
        }

        float stand = (1f - lie) * (1f - sit);
        float walkPos = e.walkAnimation.position(pt);
        float walkSpeed = Math.min(1f, e.walkAnimation.speed(pt));
        float swing = Mth.cos(walkPos * 0.6662f) * walkSpeed * stand;

        // Root: rotate the whole body around the hips, drop the hips to the ground, centre the lying body.
        Matrix4f root = new Matrix4f()
                .translate(0f, -10f * drop + bounce * lie, -side * 4f * lie)
                .translate(0f, 12f, 0f)
                .rotateY(twist * lie)
                .rotateX(side * Mth.HALF_PI * lie)
                .translate(0f, -12f, 0f);

        float headYaw = Mth.wrapDegrees(Mth.rotLerp(pt, e.yHeadRotO, e.yHeadRot) - Mth.rotLerp(pt, e.yBodyRotO, e.yBodyRot));
        headYaw = Mth.clamp(headYaw, -60f, 60f);
        float headPitch = Mth.lerp(pt, e.xRotO, e.getXRot());

        for (int i = 0; i < PixelModel.PARTS; i++) {
            float ax = Mth.lerp(pt, e.sxO[i], e.sx[i]);
            float az = Mth.lerp(pt, e.szO[i], e.sz[i]);
            float ay = 0f;
            switch (i) {
                case PixelModel.HEAD -> {
                    ay = -headYaw * Mth.DEG_TO_RAD * stand;
                    ax += headPitch * Mth.DEG_TO_RAD * stand;
                }
                case PixelModel.R_ARM -> {
                    ax += -swing - push * 0.9f;
                    ax = Mth.lerp(panic, ax, -2.7f + Mth.sin(age * 0.8f) * 0.35f);
                }
                case PixelModel.L_ARM -> {
                    ax += swing - push * 0.9f;
                    ax = Mth.lerp(panic, ax, -2.7f - Mth.sin(age * 0.8f) * 0.35f);
                }
                case PixelModel.R_LEG -> ax += swing * 1.2f - Mth.HALF_PI * sit;
                case PixelModel.L_LEG -> ax += -swing * 1.2f - Mth.HALF_PI * sit;
                default -> { }
            }
            float[] pv = PixelModel.PIVOT[i];
            out[i].set(root)
                    .translate(pv[0], pv[1], pv[2])
                    .rotateY(ay)
                    .rotateX(ax)
                    .rotateZ(az)
                    .translate(-pv[0], -pv[1], -pv[2]);
        }
    }

    private PixelPose() {}
}

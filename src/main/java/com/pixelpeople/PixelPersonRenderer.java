package com.pixelpeople;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the solid pixel body. A face is drawn when nothing is attached next to it:
 * on the outside of the body it shows the skin pixel, where a neighbour fell off it shows red flesh.
 */
public class PixelPersonRenderer extends EntityRenderer<PixelPerson> {
    private static final ResourceLocation SKIN =
            ResourceLocation.fromNamespaceAndPath(PixelPeopleMod.MODID, "textures/entity/pixel_person.png");
    private static final ResourceLocation FLESH =
            ResourceLocation.fromNamespaceAndPath(PixelPeopleMod.MODID, "textures/entity/flesh.png");

    private static final Matrix4f[] MATS = new Matrix4f[PixelModel.PARTS];
    static {
        for (int i = 0; i < MATS.length; i++) MATS[i] = new Matrix4f();
    }

    private static final Vector3f V = new Vector3f();
    private static final Quaternionf Q = new Quaternionf();

    // face normal and the two tangent axes of each face
    private static final float[][] N  = {{0,0,1},{0,0,-1},{-1,0,0},{1,0,0},{0,1,0},{0,-1,0}};
    private static final float[][] TA = {{1,0,0},{1,0,0},{0,0,1},{0,0,1},{1,0,0},{1,0,0}};
    private static final float[][] TB = {{0,1,0},{0,1,0},{0,1,0},{0,1,0},{0,0,1},{0,0,1}};
    private static final float[] SA = {-1, 1, 1, -1};
    private static final float[] SB = {-1, -1, 1, 1};

    public PixelPersonRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.4f;
    }

    @Override
    public ResourceLocation getTextureLocation(PixelPerson entity) {
        return SKIN;
    }

    @Override
    public void render(PixelPerson e, float entityYaw, float pt, PoseStack ps, MultiBufferSource buffer, int light) {
        int overlay = LivingEntityRenderer.getOverlayCoords(e, 0f);
        // The buffer source closes the previous buffer when another render type is requested,
        // so draw in two separate passes: first everything with the skin, then everything red.
        draw(e, pt, ps, buffer.getBuffer(RenderType.entityCutoutNoCull(SKIN)), false, light, overlay);
        draw(e, pt, ps, buffer.getBuffer(RenderType.entityCutoutNoCull(FLESH)), true, light, overlay);
        super.render(e, entityYaw, pt, ps, buffer, light);
    }

    private void draw(PixelPerson e, float pt, PoseStack ps, VertexConsumer vc, boolean flesh, int light, int overlay) {
        float s = PixelModel.SCALE;

        // ---- flying pixels (world aligned) ----
        double ex = Mth.lerp(pt, e.xo, e.getX());
        double ey = Mth.lerp(pt, e.yo, e.getY());
        double ez = Mth.lerp(pt, e.zo, e.getZ());
        for (PixelPerson.Debris d : e.debris) {
            int left = d.life - d.age;
            float sc = left < 8 ? Math.max(0f, left / 8f) : 1f; // shrink away in the last moments
            if (sc <= 0f) continue;
            ps.pushPose();
            ps.translate(Mth.lerp(pt, d.xo, d.x) - ex, Mth.lerp(pt, d.yo, d.y) - ey, Mth.lerp(pt, d.zo, d.z) - ez);
            ps.mulPose(new Quaternionf().rotationXYZ(d.rx, d.ry, d.rz));
            ps.scale(s * sc, s * sc, s * sc);
            PoseStack.Pose pose = ps.last();
            for (int f = 0; f < 6; f++) {
                int tex = PixelModel.TEX[d.cube * 6 + f];
                if (!flesh && tex >= 0) face(vc, pose, 0f, 0f, 0f, f, tex, 64f, light, overlay);
                else if (flesh && tex < 0) face(vc, pose, 0f, 0f, 0f, f, PixelModel.fleshTex(d.cube), 4f, light, overlay);
            }
            ps.popPose();
        }

        // ---- the body (walking pose, physics ragdoll, or the blend while getting up) ----
        e.computeRenderMatrices(pt, MATS);
        for (int p = 0; p < PixelModel.PARTS; p++) {
            ps.pushPose();
            MATS[p].transformPosition(V.set(0f, 0f, 0f));
            MATS[p].getNormalizedRotation(Q);
            ps.translate(V.x, V.y, V.z);
            ps.mulPose(Q);
            ps.scale(s, s, s);
            PoseStack.Pose pose = ps.last();
            for (int i = PixelModel.START[p]; i < PixelModel.START[p + 1]; i++) {
                if (e.detached.get(i)) continue;
                int b = i * 6;
                for (int f = 0; f < 6; f++) {
                    int nb = PixelModel.NEIGH[b + f];
                    if (nb < 0) {
                        if (!flesh) face(vc, pose, PixelModel.CX[i], PixelModel.CY[i], PixelModel.CZ[i], f,
                                PixelModel.TEX[b + f], 64f, light, overlay);
                    } else if (flesh && e.detached.get(nb)) {
                        face(vc, pose, PixelModel.CX[i], PixelModel.CY[i], PixelModel.CZ[i], f,
                                PixelModel.fleshTex(i), 4f, light, overlay);
                    }
                }
            }
            ps.popPose();
        }
    }

    private static void face(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z,
                             int f, int tex, float texSize, int light, int overlay) {
        float u = ((tex & 255) + 0.5f) / texSize;
        float v = ((tex >> 8) + 0.5f) / texSize;
        float[] n = N[f], a = TA[f], b = TB[f];
        for (int k = 0; k < 4; k++) {
            float sa = SA[k] * 0.5f, sb = SB[k] * 0.5f;
            vc.addVertex(pose,
                            x + n[0] * 0.5f + a[0] * sa + b[0] * sb,
                            y + n[1] * 0.5f + a[1] * sa + b[1] * sb,
                            z + n[2] * 0.5f + a[2] * sa + b[2] * sb)
                    .setColor(255, 255, 255, 255)
                    .setUv(u, v)
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, n[0], n[1], n[2]);
        }
    }
}

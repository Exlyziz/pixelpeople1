package com.pixelpeople;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: which pixel cubes fell off, where and in which direction the hit landed, and how hard. */
public record HitPayload(int entityId, int[] cubes, float dx, float dy, float dz, float strength,
                         float hx, float hy, float hz) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<HitPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PixelPeopleMod.MODID, "hit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HitPayload> CODEC =
            StreamCodec.of(HitPayload::write, HitPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, HitPayload p) {
        buf.writeVarInt(p.entityId);
        buf.writeVarInt(p.cubes.length);
        for (int c : p.cubes) buf.writeVarInt(c);
        buf.writeFloat(p.dx);
        buf.writeFloat(p.dy);
        buf.writeFloat(p.dz);
        buf.writeFloat(p.strength);
        buf.writeFloat(p.hx);
        buf.writeFloat(p.hy);
        buf.writeFloat(p.hz);
    }

    private static HitPayload read(RegistryFriendlyByteBuf buf) {
        int id = buf.readVarInt();
        int n = buf.readVarInt();
        int[] cubes = new int[n];
        for (int i = 0; i < n; i++) cubes[i] = buf.readVarInt();
        return new HitPayload(id, cubes, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

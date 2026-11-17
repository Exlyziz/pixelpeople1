package com.pixelpeople;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: which pixel cubes fell off, in which direction, and how hard the hit was. */
public record HitPayload(int entityId, int[] cubes, float dx, float dy, float dz, float strength)
        implements CustomPacketPayload {

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
    }

    private static HitPayload read(RegistryFriendlyByteBuf buf) {
        int id = buf.readVarInt();
        int n = buf.readVarInt();
        int[] cubes = new int[n];
        for (int i = 0; i < n; i++) cubes[i] = buf.readVarInt();
        return new HitPayload(id, cubes, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

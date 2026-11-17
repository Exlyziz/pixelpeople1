package com.pixelpeople;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: full set of already detached cubes (sent when a player starts tracking the entity). */
public record MaskPayload(int entityId, byte[] bits) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MaskPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PixelPeopleMod.MODID, "mask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MaskPayload> CODEC =
            StreamCodec.of(MaskPayload::write, MaskPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, MaskPayload p) {
        buf.writeVarInt(p.entityId);
        buf.writeByteArray(p.bits);
    }

    private static MaskPayload read(RegistryFriendlyByteBuf buf) {
        return new MaskPayload(buf.readVarInt(), buf.readByteArray());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

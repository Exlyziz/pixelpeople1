package com.pixelpeople;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue USE_SABLE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        USE_SABLE = b.comment(
                "Let the Sable: Player Ragdoll mod (the one Ragdoll Reactions uses) ragdoll the Pixel Person.",
                "If Sable cannot take the entity, or this is false, the built-in physics ragdoll is used.")
                .define("useSableRagdoll", true);
        SPEC = b.build();
    }

    private Config() {}
}

package com.pixelpeople;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, PixelPeopleMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<PixelPerson>> PIXEL_PERSON =
            ENTITIES.register("pixel_person", () -> EntityType.Builder
                    .of(PixelPerson::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .clientTrackingRange(10)
                    .build("pixel_person"));

    private ModEntities() {}
}

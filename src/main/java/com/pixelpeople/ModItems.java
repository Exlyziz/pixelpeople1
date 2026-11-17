package com.pixelpeople;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(PixelPeopleMod.MODID);

    public static final DeferredItem<DeferredSpawnEggItem> SPAWN_EGG = ITEMS.register(
            "pixel_person_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.PIXEL_PERSON, 0x00AAAA, 0xE0AC80, new Item.Properties()));

    private ModItems() {}
}

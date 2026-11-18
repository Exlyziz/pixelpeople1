package com.pixelpeople;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(PixelPeopleMod.MODID)
public class PixelPeopleMod {
    public static final String MODID = "pixelpeople";

    public PixelPeopleMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ModEntities.ENTITIES.register(modBus);
        ModItems.ITEMS.register(modBus);
        modBus.addListener(this::onAttributes);
        modBus.addListener(this::onPayloads);
        modBus.addListener(this::onCreativeTab);
    }

    private void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.PIXEL_PERSON.get(), PixelPerson.createAttributes().build());
    }

    private void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.SPAWN_EGG.get());
        }
    }

    private void onPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar("1");
        reg.playToClient(HitPayload.TYPE, HitPayload.CODEC, PixelPeopleMod::handleHit);
        reg.playToClient(MaskPayload.TYPE, MaskPayload.CODEC, PixelPeopleMod::handleMask);
    }

    private static void handleHit(HitPayload p, IPayloadContext ctx) {
        Entity e = ctx.player().level().getEntity(p.entityId());
        if (e instanceof PixelPerson pp) {
            pp.clientHit(p.cubes(), new Vec3(p.dx(), p.dy(), p.dz()), p.strength(), new Vec3(p.hx(), p.hy(), p.hz()));
        }
    }

    private static void handleMask(MaskPayload p, IPayloadContext ctx) {
        Entity e = ctx.player().level().getEntity(p.entityId());
        if (e instanceof PixelPerson pp) {
            pp.clientSetMask(p.bits());
        }
    }
}

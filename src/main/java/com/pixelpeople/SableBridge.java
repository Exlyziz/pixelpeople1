package com.pixelpeople;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Talks to the public API of Sable: Player Ragdoll (dev.leo.sableplayerragdoll.api.RagdollAPI) through
 * reflection, so Pixel People still compiles and runs when that API changes or is missing.
 */
public final class SableBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean initialised, ok;
    private static Method launchMob, isMobRagdolled, releaseMob, defaultOptions;

    public static boolean available() {
        if (!initialised) {
            initialised = true;
            try {
                if (ModList.get() == null || !ModList.get().isLoaded("sable_player_ragdoll")) return false;
                Class<?> api = Class.forName("dev.leo.sableplayerragdoll.api.RagdollAPI");
                Class<?> options = Class.forName("dev.leo.sableplayerragdoll.mob.api.MobRagdollLaunchOptions");
                defaultOptions = options.getMethod("defaults");
                for (Method m : api.getMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getName().equals("launchMob") && m.getParameterCount() == 5) launchMob = m;
                    else if (m.getName().equals("isMobRagdolled") && m.getParameterCount() == 1) isMobRagdolled = m;
                    else if (m.getName().equals("releaseMob") && m.getParameterCount() == 1) releaseMob = m;
                }
                ok = launchMob != null && isMobRagdolled != null && releaseMob != null;
                LOGGER.info("[pixelpeople] Sable ragdoll API {}", ok ? "found" : "NOT usable (methods missing)");
            } catch (Throwable t) {
                ok = false;
                LOGGER.warn("[pixelpeople] Sable ragdoll API not usable: {}", t.toString());
            }
        }
        return ok;
    }

    public static boolean isRagdolled(LivingEntity mob) {
        if (!available()) return false;
        try {
            return Boolean.TRUE.equals(isMobRagdolled.invoke(null, mob));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Starts a Sable mob ragdoll. Returns false if Sable refused or failed. */
    public static boolean launch(ServerLevel level, LivingEntity mob, Vec3 linear, Vec3 angular) {
        if (!available()) return false;
        try {
            Object session = launchMob.invoke(null, level, mob, linear, angular, defaultOptions.invoke(null));
            return session != null;
        } catch (Throwable t) {
            LOGGER.warn("[pixelpeople] Sable launchMob failed: {}", t.toString());
            return false;
        }
    }

    public static void release(LivingEntity mob) {
        if (!available()) return;
        try {
            releaseMob.invoke(null, mob);
        } catch (Throwable t) {
            LOGGER.warn("[pixelpeople] Sable releaseMob failed: {}", t.toString());
        }
    }

    private SableBridge() {}
}

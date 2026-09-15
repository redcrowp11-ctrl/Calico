package com.calico.worldgen;

import com.calico.Calico;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Hooks world-create spawn selection and first-join teleport for dangerous Calico terrains.
 * Leaves normal overworld spawn untouched.
 */
public final class CalicoSpawnEvents {
    private CalicoSpawnEvents() {
    }

    /**
     * On first world init, replace vanilla spawn picking for dangerous Calico styles.
     * Canceling skips vanilla; we set spawn ourselves (search → platform fallback).
     */
    @SubscribeEvent
    public static void onCreateSpawnPosition(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        if (!CalicoSafeSpawn.needsSafeSpawn(level)) {
            return;
        }
        event.setCanceled(true);
        CalicoSafeSpawn.ensureWorldSpawn(level, event.getSettings());
    }

    /**
     * First join / unsafe landing: if the player is standing in void/air/lava on a
     * dangerous Calico world, move them to a safe spot (repairing world spawn if needed).
     * Marked per-player so we do not re-teleport on every login when already safe.
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!CalicoSafeSpawn.needsSafeSpawn(level)) {
            return;
        }

        BlockPos feet = player.blockPosition();
        boolean unsafeHere = CalicoSafeSpawn.isUnsafeStanding(level, feet);
        if (!unsafeHere && CalicoSafeSpawn.isPlayerMarked(player)) {
            return;
        }
        if (!unsafeHere) {
            CalicoSafeSpawn.markPlayer(player);
            return;
        }

        BlockPos shared = level.getSharedSpawnPos();
        BlockPos target = shared;
        if (CalicoSafeSpawn.isUnsafeStanding(level, shared)) {
            BlockPos found = CalicoSafeSpawn.findSafeSpawn(level);
            if (found == null) {
                found = CalicoSafeSpawn.placeFallbackPlatform(level);
            }
            level.setDefaultSpawnPos(found, 0.0F);
            target = found;
            Calico.LOGGER.info(
                    "Calico: repaired unsafe world spawn → {} (style={})",
                    found,
                    CalicoSafeSpawn.describeStyle(level));
        }

        player.teleportTo(
                level,
                target.getX() + 0.5D,
                target.getY(),
                target.getZ() + 0.5D,
                player.getYRot(),
                player.getXRot());
        Calico.LOGGER.info(
                "Calico: teleported {} to safe spawn {} (style={})",
                player.getGameProfile().getName(),
                target,
                CalicoSafeSpawn.describeStyle(level));
        CalicoSafeSpawn.markPlayer(player);
    }
}

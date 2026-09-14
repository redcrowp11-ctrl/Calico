package com.calico;

import org.slf4j.Logger;

import com.calico.worldgen.BiomeRegistryDiscovery;
import com.calico.worldgen.CalicoBiomeSources;
import com.calico.worldgen.CalicoWorldPresets;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Calico mod entrypoint — Phase 1 multi-biome world generation hooks.
 */
@Mod(Calico.MOD_ID)
public class Calico {
    public static final String MOD_ID = "calico";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Calico(IEventBus modEventBus, ModContainer modContainer) {
        CalicoBiomeSources.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::gatherData);
        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Calico Phase 1 loaded — weighted biome source + world preset hooks ready");
    }

    private void gatherData(GatherDataEvent event) {
        CalicoWorldPresets.addDatagenProviders(event);
    }

    /** Registries/tags reloaded — drop cached biome discovery. */
    @SubscribeEvent
    public void onTagsUpdated(TagsUpdatedEvent event) {
        BiomeRegistryDiscovery.invalidateCache();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        BiomeRegistryDiscovery.invalidateCache();
    }
}

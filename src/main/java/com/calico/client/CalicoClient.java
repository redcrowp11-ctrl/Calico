package com.calico.client;

import com.calico.Calico;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-side entry. Create-world UI belongs in a later phase; this only bootstraps client.
 */
@Mod(value = Calico.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Calico.MOD_ID, value = Dist.CLIENT)
public class CalicoClient {
    public CalicoClient(ModContainer container) {
        // UI screens (CalicoCreateWorldScreen etc.) land in a later phase.
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        Calico.LOGGER.debug("Calico client setup");
    }
}

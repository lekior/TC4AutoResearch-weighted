package com.Emil.TCAutoResearch.proxy;

import com.Emil.TCAutoResearch.ClientResearchTickHandler;
import com.Emil.TCAutoResearch.Config;
import com.Emil.TCAutoResearch.GuiHandler;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.network.NetworkRegistry;

public class ClientProxy implements IProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientResearchTickHandler());

    }

    @Override
    public void loadComplete(FMLLoadCompleteEvent event) {
        if (Loader.isModLoaded("ThaumcraftResearchTweaks")) {
            NetworkRegistry.INSTANCE.registerGuiHandler("ThaumcraftResearchTweaks", new GuiHandler());
        }
    }
}

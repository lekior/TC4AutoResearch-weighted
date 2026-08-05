package com.Emil.TCAutoResearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.Emil.TCAutoResearch.proxy.IProxy;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = TCAutoResearch.MODID,
    version = "1.1.0-weighted",
    name = "TCAutoResearch",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*",
    dependencies = "after:ThaumcraftResearchTweaks")
public class TCAutoResearch {

    public static final String MODID = "TCAutoResearchByEmil";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(
        clientSide = "com.Emil.TCAutoResearch.proxy.ClientProxy",
        serverSide = "com.Emil.TCAutoResearch.proxy.ServerProxy")
    public static IProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        proxy.loadComplete(event);
    }

}

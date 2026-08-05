package com.Emil.TCAutoResearch.proxy;

import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public interface IProxy {

    void preInit(FMLPreInitializationEvent event);

    default void loadComplete(FMLLoadCompleteEvent event) {}
}

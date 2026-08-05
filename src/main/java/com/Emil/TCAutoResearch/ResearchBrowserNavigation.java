package com.Emil.TCAutoResearch;

import thaumcraft.api.research.ResearchItem;

public final class ResearchBrowserNavigation {

    private static ResearchItem pending;

    private ResearchBrowserNavigation() {}

    public static synchronized void request(ResearchItem research) {
        pending = research;
    }

    public static synchronized ResearchItem consume() {
        ResearchItem research = pending;
        pending = null;
        return research;
    }
}

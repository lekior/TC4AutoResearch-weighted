package com.Emil.TCAutoResearch;

import thaumcraft.api.aspects.Aspect;

/** Uses Thaumcraft's aspect-name key, not the longer aspect help-text key. */
public final class AspectLocalization {

    private AspectLocalization() {}

    public static String name(Aspect aspect) {
        if (aspect == null) return "";
        String localized = aspect.getLocalizedDescription();
        String key = "tc.aspect." + aspect.getTag();
        return localized == null || localized.equals(key) ? aspect.getName() : localized;
    }
}

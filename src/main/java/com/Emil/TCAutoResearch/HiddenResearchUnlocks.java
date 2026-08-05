package com.Emil.TCAutoResearch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.research.ResearchItem;

public final class HiddenResearchUnlocks {

    private HiddenResearchUnlocks() {}

    public static String describe(ResearchItem research) {
        if (research == null) return StatCollector.translateToLocal("tcautores.hidden_unlock.unknown");
        Set<String> methods = new LinkedHashSet<>();
        appendItems(methods, research.getItemTriggers());
        appendEntities(methods, research.getEntityTriggers());
        appendAspects(methods, research.getAspectTriggers());
        if (methods.isEmpty()) return StatCollector.translateToLocal("tcautores.hidden_unlock.unknown");
        return String.join(StatCollector.translateToLocal("tcautores.hidden_unlock.or"), methods);
    }

    private static void appendItems(Set<String> methods, ItemStack[] triggers) {
        if (triggers == null) return;
        for (ItemStack stack : triggers) {
            if (stack == null) continue;
            methods.add(
                String.format(StatCollector.translateToLocal("tcautores.hidden_unlock.item"), stack.getDisplayName()));
        }
    }

    private static void appendEntities(Set<String> methods, String[] triggers) {
        if (triggers == null) return;
        for (String entity : triggers) {
            if (entity == null || entity.isEmpty()) continue;
            methods.add(
                String.format(StatCollector.translateToLocal("tcautores.hidden_unlock.entity"), entityName(entity)));
        }
    }

    private static void appendAspects(Set<String> methods, Aspect[] triggers) {
        if (triggers == null) return;
        for (Aspect aspect : triggers) {
            if (aspect == null) continue;
            methods.add(
                String.format(
                    StatCollector.translateToLocal("tcautores.hidden_unlock.aspect"),
                    AspectLocalization.name(aspect),
                    aspect.getTag()));
        }
    }

    private static String entityName(String entity) {
        List<String> keys = new ArrayList<>();
        keys.add("entity." + entity + ".name");
        int separator = entity.indexOf(':');
        if (separator >= 0 && separator + 1 < entity.length())
            keys.add("entity." + entity.substring(separator + 1) + ".name");
        for (String key : keys) {
            String translated = StatCollector.translateToLocal(key);
            if (!translated.equals(key)) return translated + " [" + entity + "]";
        }
        return entity;
    }
}

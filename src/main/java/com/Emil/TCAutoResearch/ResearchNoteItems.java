package com.Emil.TCAutoResearch;

import net.minecraft.item.ItemStack;

import thaumcraft.common.items.ItemResearchNotes;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;

public final class ResearchNoteItems {

    private ResearchNoteItems() {}

    public static boolean isIncomplete(ItemStack stack) {
        ResearchNoteData data = data(stack);
        return data != null && stack.getItemDamage() < 64 && !data.complete;
    }

    public static boolean isComplete(ItemStack stack) {
        ResearchNoteData data = data(stack);
        return data != null && (stack.getItemDamage() >= 64 || data.complete);
    }

    public static boolean hasKey(ItemStack stack, String key) {
        ResearchNoteData data = data(stack);
        return data != null && key != null && key.equals(data.key);
    }

    public static ResearchNoteData data(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemResearchNotes)) return null;
        ResearchNoteData data = ResearchManager.getData(stack);
        return data == null || data.key == null || data.key.isEmpty() ? null : data;
    }
}

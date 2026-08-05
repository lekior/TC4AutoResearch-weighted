package com.Emil.TCAutoResearch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import thaumcraft.common.items.ItemResearchNotes;

class ResearchNoteItemsTest {

    @Test
    void acceptsOnlyValidIncompleteResearchNotes() {
        assertTrue(ResearchNoteItems.isIncomplete(note("VALID", false, 0)));

        assertFalse(ResearchNoteItems.isIncomplete(note("VALID", true, 64)));
        assertFalse(ResearchNoteItems.isIncomplete(note("VALID", false, 64)));
        assertFalse(ResearchNoteItems.isIncomplete(note("", false, 24)));
        assertFalse(ResearchNoteItems.isIncomplete(new ItemStack(new Item())));
        assertFalse(ResearchNoteItems.isIncomplete(null));
    }

    @Test
    void matchesResearchKeysExactlyAndRecognizesCompletedDiscoveries() {
        ItemStack incomplete = note("RESEARCH_A", false, 0);
        ItemStack complete = note("RESEARCH_A", true, 64);

        assertTrue(ResearchNoteItems.hasKey(incomplete, "RESEARCH_A"));
        assertFalse(ResearchNoteItems.hasKey(incomplete, "RESEARCH"));
        assertFalse(ResearchNoteItems.hasKey(incomplete, null));
        assertTrue(ResearchNoteItems.isComplete(complete));
        assertFalse(ResearchNoteItems.isComplete(incomplete));
    }

    private static ItemStack note(String key, boolean complete, int damage) {
        ItemStack stack = new ItemStack(new ItemResearchNotes(), 1, damage);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("key", key);
        tag.setBoolean("complete", complete);
        stack.setTagCompound(tag);
        return stack;
    }
}

package com.Emil.TCAutoResearch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.Test;

class ResearchNoteGenerationControllerTest {

    @Test
    void countsOnlySlotsThatCanHoldTheGeneratedNote() {
        ItemStack[] inventory = { null, new ItemStack(Items.paper, 1), new ItemStack(new Item()) };

        assertEquals(2, ResearchNoteGenerationController.generatedNoteCapacity(inventory, 0));
        assertEquals(1, ResearchNoteGenerationController.generatedNoteCapacity(inventory, 1));
        assertEquals(0, ResearchNoteGenerationController.generatedNoteCapacity(inventory, 2));
    }

    @Test
    void aNonEmptyPaperStackDoesNotReleaseItsSlot() {
        ItemStack[] inventory = { new ItemStack(Items.paper, 2), new ItemStack(new Item()) };
        assertEquals(0, ResearchNoteGenerationController.generatedNoteCapacity(inventory, 0));

        inventory[1].stackSize = 0;
        assertEquals(1, ResearchNoteGenerationController.generatedNoteCapacity(inventory, 0));
    }
}

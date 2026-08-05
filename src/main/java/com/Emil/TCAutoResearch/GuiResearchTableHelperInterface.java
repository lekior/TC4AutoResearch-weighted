package com.Emil.TCAutoResearch;

import java.util.List;

import net.minecraft.item.ItemStack;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.common.lib.utils.HexUtils;

public interface GuiResearchTableHelperInterface {

    void combine(Aspect aspect1, Aspect aspect2);

    void place(HexUtils.Hex hex, Aspect aspect);

    boolean hasInk();

    AspectList availableAspects();

    ItemStack researchNoteStack();

    ItemStack scribingToolsStack();

    int findIncompleteResearchNoteSlot();

    int findIncompleteResearchNoteSlot(String key);

    int countIncompleteResearchNotes();

    int countIncompleteResearchNotes(String key);

    int findCompletedResearchNoteSlot(String key);

    List<String> researchNoteKeys();

    int findUsableScribingToolsSlot();
}

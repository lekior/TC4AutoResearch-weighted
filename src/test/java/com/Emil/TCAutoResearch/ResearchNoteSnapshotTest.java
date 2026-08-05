package com.Emil.TCAutoResearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;

class ResearchNoteSnapshotTest {

    @Test
    void copiesMutableHexEntries() {
        ResearchNoteData source = new ResearchNoteData();
        source.key = "snapshot";
        source.hexEntries.put("0:0", new ResearchManager.HexEntry(Aspect.AIR, 1));

        ResearchNoteData copy = ResearchNoteSnapshot.copyOf(source);
        source.hexEntries.get("0:0").aspect = Aspect.FIRE;

        assertNotSame(source.hexEntries.get("0:0"), copy.hexEntries.get("0:0"));
        assertEquals(Aspect.AIR, copy.hexEntries.get("0:0").aspect);
    }
}

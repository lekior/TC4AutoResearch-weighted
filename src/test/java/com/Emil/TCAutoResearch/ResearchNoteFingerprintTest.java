package com.Emil.TCAutoResearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;

class ResearchNoteFingerprintTest {

    @Test
    void puzzleFingerprintSurvivesPlacementAndFreshDataObjects() {
        ResearchNoteData first = note(Aspect.AIR);
        ResearchNoteData refreshed = note(Aspect.AIR);
        refreshed.hexEntries.put("1:0", new ResearchManager.HexEntry(Aspect.MOTION, 2));

        assertEquals(ResearchNoteFingerprint.topology(first), ResearchNoteFingerprint.topology(refreshed));
        assertEquals(ResearchNoteFingerprint.identity(first), ResearchNoteFingerprint.identity(refreshed));
        assertNotEquals(ResearchNoteFingerprint.state(first), ResearchNoteFingerprint.state(refreshed));
    }

    @Test
    void puzzleFingerprintIncludesFixedAnchorAspects() {
        assertNotEquals(
            ResearchNoteFingerprint.topology(note(Aspect.AIR)),
            ResearchNoteFingerprint.topology(note(Aspect.FIRE)));
    }

    private static ResearchNoteData note(Aspect anchor) {
        ResearchNoteData note = new ResearchNoteData();
        note.key = "fingerprint-test";
        note.color = 123;
        note.hexEntries.put("0:0", new ResearchManager.HexEntry(anchor, 1));
        note.hexEntries.put("1:0", new ResearchManager.HexEntry(null, 0));
        return note;
    }
}

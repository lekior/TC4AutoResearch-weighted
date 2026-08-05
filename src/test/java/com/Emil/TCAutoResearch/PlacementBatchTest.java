package com.Emil.TCAutoResearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;

class PlacementBatchTest {

    @Test
    void sendsOnlyUnconfirmedPlacementsOnRetry() {
        ResearchNoteData note = note(Aspect.AIR, null);

        ResearchSolveController.PlacementBatch batch = ResearchSolveController.scanPlacements(placements(), note);

        assertFalse(batch.conflict);
        assertEquals(1, batch.missing.size());
        assertEquals(
            "2:0",
            batch.missing.get(0)
                .getKey());
    }

    @Test
    void rejectsAConflictingPlayerPlacement() {
        ResearchNoteData note = note(Aspect.EARTH, null);

        ResearchSolveController.PlacementBatch batch = ResearchSolveController.scanPlacements(placements(), note);

        assertTrue(batch.conflict);
        assertTrue(batch.missing.isEmpty());
    }

    @Test
    void recognizesAConfirmedBatch() {
        ResearchNoteData note = note(Aspect.AIR, Aspect.FIRE);

        ResearchSolveController.PlacementBatch batch = ResearchSolveController.scanPlacements(placements(), note);

        assertFalse(batch.conflict);
        assertTrue(batch.missing.isEmpty());
    }

    @Test
    void requiresOnlyUndiscoveredFixedAnchors() {
        ResearchNoteData note = new ResearchNoteData();
        note.hexEntries.put("0:0", new ResearchManager.HexEntry(Aspect.LIGHT, 1));
        note.hexEntries.put("2:0", new ResearchManager.HexEntry(Aspect.MOTION, 1));
        Map<Aspect, Integer> required = new LinkedHashMap<>();
        required.put(Aspect.LIGHT, 2);

        ResearchSolveController.addUndiscoveredAnchors(required, note, aspect -> aspect == Aspect.LIGHT);

        assertEquals(2, required.get(Aspect.LIGHT));
        assertEquals(1, required.get(Aspect.MOTION));
    }

    private static List<Map.Entry<String, Aspect>> placements() {
        List<Map.Entry<String, Aspect>> result = new ArrayList<>();
        result.add(new AbstractMap.SimpleImmutableEntry<>("1:0", Aspect.AIR));
        result.add(new AbstractMap.SimpleImmutableEntry<>("2:0", Aspect.FIRE));
        return result;
    }

    private static ResearchNoteData note(Aspect first, Aspect second) {
        ResearchNoteData note = new ResearchNoteData();
        note.hexEntries.put("1:0", new ResearchManager.HexEntry(first, first == null ? 0 : 2));
        note.hexEntries.put("2:0", new ResearchManager.HexEntry(second, second == null ? 0 : 2));
        return note;
    }
}

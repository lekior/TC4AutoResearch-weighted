package com.Emil.TCAutoResearch;

import java.util.Map;

import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;

/** Copies mutable Thaumcraft research data before worker-thread solving or repair simulation. */
final class ResearchNoteSnapshot {

    private ResearchNoteSnapshot() {}

    static ResearchNoteData copyOf(ResearchNoteData source) {
        ResearchNoteData copy = new ResearchNoteData();
        copy.key = source.key;
        copy.color = source.color;
        copy.complete = source.complete;
        copy.copies = source.copies;
        copy.hexes.putAll(source.hexes);
        for (Map.Entry<String, ResearchManager.HexEntry> entry : source.hexEntries.entrySet()) {
            ResearchManager.HexEntry hex = entry.getValue();
            copy.hexEntries.put(entry.getKey(), new ResearchManager.HexEntry(hex.aspect, hex.type));
        }
        return copy;
    }
}

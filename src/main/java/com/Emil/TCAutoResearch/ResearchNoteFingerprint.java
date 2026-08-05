package com.Emil.TCAutoResearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;

public final class ResearchNoteFingerprint {

    private ResearchNoteFingerprint() {}

    public static String topology(ResearchNoteData note) {
        if (note == null) return "";
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, ResearchManager.HexEntry> entry : note.hexEntries.entrySet()) {
            ResearchManager.HexEntry hex = entry.getValue();
            String anchor = hex.type == 1 && hex.aspect != null ? hex.aspect.getTag() : "";
            entries.add(entry.getKey() + ':' + (hex.type == 1 ? "1=" + anchor : "0"));
        }
        Collections.sort(entries);
        StringBuilder result = new StringBuilder(identity(note));
        result.append("|grid:");
        for (String entry : entries) result.append(entry)
            .append(';');
        return result.toString();
    }

    public static String identity(ResearchNoteData note) {
        if (note == null) return "";
        List<String> anchors = new ArrayList<>();
        for (Map.Entry<String, ResearchManager.HexEntry> entry : note.hexEntries.entrySet()) {
            ResearchManager.HexEntry hex = entry.getValue();
            if (hex.type == 1) {
                anchors.add(entry.getKey() + '=' + (hex.aspect == null ? "" : hex.aspect.getTag()));
            }
        }
        Collections.sort(anchors);
        StringBuilder result = new StringBuilder(note.key == null ? "" : note.key);
        result.append('|')
            .append(note.color)
            .append("|anchors:");
        for (String anchor : anchors) result.append(anchor)
            .append(';');
        return result.toString();
    }

    public static String state(ResearchNoteData note) {
        if (note == null) return "";
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, ResearchManager.HexEntry> entry : note.hexEntries.entrySet()) {
            entries.add(
                entry.getKey() + ':'
                    + entry.getValue().type
                    + '='
                    + (entry.getValue().aspect == null ? "" : entry.getValue().aspect.getTag()));
        }
        Collections.sort(entries);
        StringBuilder result = new StringBuilder(topology(note));
        result.append("|state:");
        for (String entry : entries) result.append(entry)
            .append(';');
        return result.toString();
    }
}

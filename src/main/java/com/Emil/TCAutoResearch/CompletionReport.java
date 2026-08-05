package com.Emil.TCAutoResearch;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.common.lib.research.ResearchNoteData;

/** Immutable summary of the latest solve and execution attempt. */
public final class CompletionReport {

    public final String noteKey;
    public final String stateKey;
    public final String detail;
    public final boolean serverConfirmed;
    public final long solveTimeMs;
    public final long executionTimeMs;
    public final long totalCost;
    public final int placements;
    public final int synthesisOperations;
    public final long expandedStates;
    public final int peakStates;
    public final int peakQueue;
    public final int peakPlans;
    public final int placementRounds;
    public final int placementPackets;
    public final int combinationPackets;
    public final boolean fallbackUsed;
    public final boolean resultCacheHit;
    public final boolean graphCacheHit;
    public final ResearchNoteData note;
    public final Map<String, Aspect> plannedPlacements;

    CompletionReport(String noteKey, String stateKey, String detail, boolean serverConfirmed, long solveTimeMs,
        long executionTimeMs, long totalCost, int placements, int synthesisOperations, long expandedStates,
        int peakStates, int peakQueue, int peakPlans, int placementRounds, int placementPackets, int combinationPackets,
        boolean fallbackUsed, boolean resultCacheHit, boolean graphCacheHit, ResearchNoteData note,
        Map<String, Aspect> plannedPlacements) {
        this.noteKey = noteKey == null ? "" : noteKey;
        this.stateKey = stateKey;
        this.detail = detail == null ? "" : detail;
        this.serverConfirmed = serverConfirmed;
        this.solveTimeMs = solveTimeMs;
        this.executionTimeMs = executionTimeMs;
        this.totalCost = totalCost;
        this.placements = placements;
        this.synthesisOperations = synthesisOperations;
        this.expandedStates = expandedStates;
        this.peakStates = peakStates;
        this.peakQueue = peakQueue;
        this.peakPlans = peakPlans;
        this.placementRounds = placementRounds;
        this.placementPackets = placementPackets;
        this.combinationPackets = combinationPackets;
        this.fallbackUsed = fallbackUsed;
        this.resultCacheHit = resultCacheHit;
        this.graphCacheHit = graphCacheHit;
        this.note = note;
        this.plannedPlacements = Collections.unmodifiableMap(new LinkedHashMap<>(plannedPlacements));
    }

    public static CompletionReport empty(String noteKey, String stateKey, boolean serverConfirmed) {
        return new CompletionReport(
            noteKey,
            stateKey,
            "",
            serverConfirmed,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            false,
            false,
            null,
            Collections.emptyMap());
    }

    static CompletionReport fromResult(String noteKey, String stateKey, String detail, boolean serverConfirmed,
        WeightedResearchSolver.Result result, long executionTimeMs, int placementRounds, int placementPackets,
        int combinationPackets) {
        if (result == null) return empty(noteKey, stateKey, serverConfirmed);
        return new CompletionReport(
            noteKey,
            stateKey,
            detail,
            serverConfirmed,
            result.solveTimeMs,
            executionTimeMs,
            result.totalCost,
            result.placements.size(),
            result.synthesisOperations,
            result.expandedStates,
            result.peakStates,
            result.peakQueue,
            result.peakPlans,
            placementRounds,
            placementPackets,
            combinationPackets,
            result.fallbackUsed,
            result.resultCacheHit,
            result.graphCacheHit,
            null,
            Collections.emptyMap());
    }

    public CompletionReport withBoard(ResearchNoteData board, Map<String, Aspect> planned) {
        return new CompletionReport(
            noteKey,
            stateKey,
            detail,
            serverConfirmed,
            solveTimeMs,
            executionTimeMs,
            totalCost,
            placements,
            synthesisOperations,
            expandedStates,
            peakStates,
            peakQueue,
            peakPlans,
            placementRounds,
            placementPackets,
            combinationPackets,
            fallbackUsed,
            resultCacheHit,
            graphCacheHit,
            board == null ? null : ResearchNoteSnapshot.copyOf(board),
            planned);
    }
}

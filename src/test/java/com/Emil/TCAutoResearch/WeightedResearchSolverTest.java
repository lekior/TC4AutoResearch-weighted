package com.Emil.TCAutoResearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;

class WeightedResearchSolverTest {

    // GTNH adds electrum as Potentia + Machina. The test classpath contains only TC4.
    private static final Aspect ELECTRUM = createElectrum();
    private static final Aspect INFERNUS = createInfernus();

    static {
        registerRuntimeAspects();
    }

    @AfterEach
    void restoreConfig() {
        Config.setAspectDisabled(Aspect.LIGHT.getTag(), false);
        Config.setAspectCost(Aspect.LIGHT.getTag(), AspectWeights.wikiCost(Aspect.LIGHT.getTag()));
    }

    @Test
    void solvesThreeCellBridgeAndCachesResult() {
        ResearchNoteData note = lineNote();
        WeightedResearchSolver.Result first = solve(note);

        assertTrue(first.success);
        assertEquals(1, first.placements.size());
        Aspect bridge = first.placements.get("1:0");
        assertTrue(compatible(Aspect.AIR, bridge));
        assertTrue(compatible(Aspect.FIRE, bridge));
        assertTrue(WeightedResearchSolver.validateSolution(note, first.placements));
        assertFalse(first.resultCacheHit);

        WeightedResearchSolver.Result cached = solve(note);
        assertTrue(cached.success);
        assertTrue(cached.resultCacheHit);
        assertEquals(0, cached.expandedStates);
    }

    @Test
    void validatesFinalConnectivityOnAHexDisc() {
        ResearchNoteData note = new ResearchNoteData();
        note.key = "solver-disc-validation";
        for (String cell : new String[] { "0:0", "1:0", "1:-1", "0:-1", "-1:0", "-1:1", "0:1" }) {
            note.hexEntries.put(cell, new ResearchManager.HexEntry(null, 0));
        }
        note.hexEntries.put("-1:0", new ResearchManager.HexEntry(Aspect.AIR, 1));
        note.hexEntries.put("1:0", new ResearchManager.HexEntry(Aspect.FIRE, 1));
        Map<String, Aspect> placements = new LinkedHashMap<>();
        placements.put("0:0", Aspect.LIGHT);

        assertTrue(WeightedResearchSolver.validateSolution(note, placements));

        placements.put("0:0", Aspect.MOTION);
        assertFalse(WeightedResearchSolver.validateSolution(note, placements));
    }

    @Test
    void respectsDisabledIntermediateAspect() {
        List<Aspect> disabled = new ArrayList<>();
        try {
            for (Object value : Aspect.aspects.values()) {
                Aspect aspect = (Aspect) value;
                if (compatible(Aspect.AIR, aspect) && compatible(Aspect.FIRE, aspect)) {
                    disabled.add(aspect);
                    Config.setAspectDisabled(aspect.getTag(), true);
                }
            }

            WeightedResearchSolver.Result result = solve(lineNote());

            assertFalse(result.success);
        } finally {
            for (Aspect aspect : disabled) Config.setAspectDisabled(aspect.getTag(), false);
        }
    }

    @Test
    void invalidatesResultCacheWhenTheAspectGraphChanges() {
        ResearchNoteData note = new ResearchNoteData();
        note.key = "solver-late-aspect-cache";
        note.hexEntries.put("100:0", new ResearchManager.HexEntry(Aspect.AIR, 1));
        note.hexEntries.put("101:0", new ResearchManager.HexEntry(null, 0));
        note.hexEntries.put("102:0", new ResearchManager.HexEntry(Aspect.FIRE, 1));

        assertTrue(solve(note).success);
        assertTrue(solve(note).resultCacheHit);
        if (Aspect.getAspect("tcautores_late_cache_test") == null) {
            new Aspect("tcautores_late_cache_test", 0x777777, new Aspect[] { Aspect.WATER, Aspect.FIRE });
        }

        WeightedResearchSolver.Result rebuilt = solve(note);

        assertFalse(rebuilt.resultCacheHit);
        assertTrue(rebuilt.success);
        assertTrue(WeightedResearchSolver.validateSolution(note, rebuilt.placements));
    }

    @Test
    void geometryCacheDoesNotReuseAnchorsFromAnotherNote() {
        ResearchNoteData first = lineNote();
        ResearchNoteData second = lineNote();
        second.key = "solver-shared-geometry";
        second.hexEntries.put("0:0", new ResearchManager.HexEntry(Aspect.WATER, 1));
        second.hexEntries.put("2:0", new ResearchManager.HexEntry(Aspect.EARTH, 1));

        assertTrue(solve(first).success);
        WeightedResearchSolver.Result result = solve(second);

        assertTrue(result.success);
        assertTrue(WeightedResearchSolver.validateSolution(second, result.placements));
    }

    @Test
    void solvesLongRouteWithOnlyCompatibleTransitions() {
        ResearchNoteData note = new ResearchNoteData();
        note.key = "solver-long-route";
        for (int q = 0; q <= 30; q++) {
            Aspect aspect = q == 0 ? Aspect.AIR : q == 30 ? Aspect.FIRE : null;
            note.hexEntries.put(q + ":0", new ResearchManager.HexEntry(aspect, aspect == null ? 0 : 1));
        }

        WeightedResearchSolver.Result result = solve(note);

        assertTrue(result.success);
        assertEquals(29, result.placements.size());
        Aspect previous = Aspect.AIR;
        for (int q = 1; q < 30; q++) {
            Aspect current = result.placements.get(q + ":0");
            assertTrue(compatible(previous, current));
            previous = current;
        }
        assertTrue(compatible(previous, Aspect.FIRE));
        System.out.printf(
            "long-route solve=%dms expanded=%d peak=%d memory=%d%n",
            result.solveTimeMs,
            result.expandedStates,
            result.peakStates,
            result.estimatedWorkingBytes);
    }

    @Test
    void solvesAnIrregularMultiAnchorBranch() {
        ResearchNoteData note = new ResearchNoteData();
        note.key = "solver-irregular-branch";
        note.hexEntries.put("0:0", new ResearchManager.HexEntry(Aspect.AIR, 1));
        note.hexEntries.put("1:0", new ResearchManager.HexEntry(null, 0));
        note.hexEntries.put("2:0", new ResearchManager.HexEntry(Aspect.FIRE, 1));
        note.hexEntries.put("0:1", new ResearchManager.HexEntry(null, 0));
        note.hexEntries.put("0:2", new ResearchManager.HexEntry(Aspect.ORDER, 1));

        WeightedResearchSolver.Result result = solve(note);

        assertTrue(result.success);
        assertEquals(2, result.placements.size());
        assertTrue(WeightedResearchSolver.validateSolution(note, result.placements));
    }

    @Test
    void solvesAUniqueThreeBranchJunction() {
        ResearchNoteData note = new ResearchNoteData();
        note.key = "solver-unique-three-branch";
        note.hexEntries.put("0:0", new ResearchManager.HexEntry(null, 0));
        note.hexEntries.put("1:0", new ResearchManager.HexEntry(Aspect.AIR, 1));
        note.hexEntries.put("0:-1", new ResearchManager.HexEntry(Aspect.AIR, 1));
        note.hexEntries.put("-1:1", new ResearchManager.HexEntry(Aspect.AIR, 1));

        WeightedResearchSolver.Result result = WeightedResearchSolver.solve(
            note,
            new AspectList(),
            Config.snapshot()
                .withBeamWidth(0),
            () -> false);

        assertTrue(result.success);
        assertTrue(result.fallbackUsed);
        assertEquals(1, result.placements.size());
        Aspect junction = result.placements.get("0:0");
        assertTrue(compatible(Aspect.AIR, junction));
        assertTrue(WeightedResearchSolver.validateSolution(note, result.placements));
    }

    @Test
    void reproducesQuantumGogglesOfRevealingFailure() {
        ResearchNoteData note = quantumGogglesNote();
        assertRepairsAndSolves(note, "4:-2");
    }

    @Test
    void repairsAndSolvesSolarHelmetOfRevealingFromRuntimeLog() {
        ResearchNoteData note = noteFromRuntimeLog(
            "SolarHelmetofRevealing",
            "-1:-1 -1:-2 -1:-3 -1:0 -1:1 -1:2 -1:3 -1:4 " + "-2:-1 -2:-2 -2:0 -2:1 -2:2 -2:3 -2:4 -3:-1 -3:1 -3:2 "
                + "-4:0 -4:1 -4:2 -4:3 -4:4 0:-1 0:-2 0:-3 0:-4 0:0 0:1 0:2 0:3 0:4 "
                + "1:-1 1:-2 1:-3 1:-4 1:0 1:1 1:2 1:3 2:-1 2:-2 2:-3 2:-4 2:0 2:1 2:2 "
                + "3:-1 3:-3 3:-4 3:1 4:-2 4:-3 4:-4 4:0",
            "-1:4=potentia -3:-1=aer -4:1=lux -4:4=tutamen 0:-4=lucrum "
                + "1:3=sensus 3:-4=electrum 4:-3=auram 4:0=praecantatio");

        assertRepairsAndSolves(note, null);
    }

    @Test
    void repairsAndSolvesGhostAmuletFromRuntimeLog() {
        ResearchNoteData note = noteFromRuntimeLog(
            "GHOSTAMULET",
            "-1:-1 -1:-2 -1:-3 -1:0 -1:1 -1:2 -1:3 -1:4 " + "-2:-2 -2:0 -2:1 -2:2 -2:3 -2:4 -3:-1 -3:1 -3:2 -3:3 -3:4 "
                + "-4:0 -4:1 -4:2 -4:3 -4:4 0:-1 0:-3 0:-4 0:0 0:2 0:3 0:4 "
                + "1:-1 1:-2 1:-3 1:-4 1:1 1:3 2:-1 2:-2 2:-3 2:-4 2:0 2:1 2:2 "
                + "3:-1 3:-2 3:-3 3:-4 3:0 3:1 4:-1 4:-2 4:-3 4:-4 4:0",
            "-1:4=auram -2:-2=alienis -4:1=corpus -4:4=potentia 1:-4=spiritus "
                + "2:2=infernus 4:-1=lucrum 4:-4=praecantatio");

        assertRepairsAndSolves(note, null);
    }

    @Test
    void solvesQuantumWingsFromRuntimeLogWithTheFullAspectGraph() {
        ResearchNoteData note = noteFromRuntimeLog(
            "QuantumWings",
            "-1:-1 -1:-2 -1:-3 -1:0 -1:2 -1:3 -1:4 -2:-1 -2:-2 -2:0 -2:1 -2:2 -2:3 -2:4 "
                + "-3:-1 -3:0 -3:1 -3:2 -3:3 -3:4 -4:0 -4:1 -4:2 -4:3 -4:4 "
                + "0:-2 0:-4 0:0 0:1 0:2 0:3 0:4 1:-1 1:-2 1:-4 1:0 1:1 1:2 1:3 "
                + "2:-1 2:-3 2:-4 2:0 2:1 3:-1 3:-2 3:-3 3:-4 3:0 3:1 "
                + "4:-1 4:-2 4:-3 4:-4 4:0",
            "-1:4=potentia -3:-1=terra -4:1=lucrum -4:4=aer 0:-4=machina "
                + "1:3=praecantatio 3:-4=vitium 4:-3=metallum 4:0=volatus");

        WeightedResearchSolver.Result result = solve(note);
        System.out.printf(
            "QuantumWings solve=%dms expanded=%d placements=%d%n",
            result.solveTimeMs,
            result.expandedStates,
            result.placements.size());
        assertTrue(result.success);
        assertTrue(WeightedResearchSolver.validateSolution(note, result.placements));

        AspectList stockedInventory = new AspectList();
        for (Object value : Aspect.aspects.values()) stockedInventory.add((Aspect) value, 64);
        WeightedResearchSolver.Result stockedResult = solve(note, stockedInventory);
        System.out.printf(
            "QuantumWings stocked solve=%dms fallback=%s expanded=%d placements=%d%n",
            stockedResult.solveTimeMs,
            stockedResult.fallbackUsed,
            stockedResult.expandedStates,
            stockedResult.placements.size());
        assertTrue(stockedResult.success);
        assertTrue(WeightedResearchSolver.validateSolution(note, stockedResult.placements));
    }

    @Test
    void repairsAndSolvesTbSmbFromRuntimeLog() {
        ResearchNoteData note = noteFromRuntimeLog(
            "TB.SMB",
            "-1:-1 -1:0 -1:1 -1:2 -2:0 -2:1 -2:2 0:-1 0:-2 0:0 0:1 0:2 " + "1:-1 1:-2 1:0 1:1 2:-1 2:-2 2:0",
            "-1:-1=perditio -2:0=fabrico -2:2=aer 0:2=aqua 1:-2=ordo 1:1=terra 2:-1=ignis");

        assertRepairsAndSolves(note, null);
    }

    @Test
    void repairsAnInterruptedTypeTwoPlacement() {
        ResearchNoteData note = quantumGogglesNote();
        ResearchManager.HexEntry interrupted = note.hexEntries.get("4:-2");
        note.hexEntries.put("4:-2", new ResearchManager.HexEntry(interrupted.aspect, 2));

        WeightedResearchSolver.Result initial = solve(note);
        assertFalse(initial.success);
        assertEquals("incompatible_corridor", initial.failureReason);

        WeightedResearchSolver.RepairPlan plan = WeightedResearchSolver
            .findRepairPlan(note, new AspectList(), Config.snapshot(), () -> false, initial, 3);

        assertTrue(plan != null && !plan.repairCells.isEmpty());
        assertEquals("4:-2", plan.repairCells.get(0));
        assertEquals(2, note.hexEntries.get("4:-2").type);
        for (String repairCell : plan.repairCells) {
            note.hexEntries.put(repairCell, new ResearchManager.HexEntry(null, 0));
        }
        assertTrue(plan.result.success);
        assertTrue(WeightedResearchSolver.validateSolution(note, plan.result.placements));
    }

    @Test
    void refusesRepairWhenRemovingEitherAnchorCannotProduceASolution() {
        ResearchNoteData note = new ResearchNoteData();
        note.key = "solver-no-destructive-dead-end";
        note.hexEntries.put("0:0", new ResearchManager.HexEntry(Aspect.LIGHT, 1));
        note.hexEntries.put("1:0", new ResearchManager.HexEntry(null, 0));
        note.hexEntries.put("2:0", new ResearchManager.HexEntry(Aspect.AIR, 1));

        WeightedResearchSolver.Result result = solve(note);
        assertFalse(result.success);
        assertTrue(
            "incompatible_corridor".equals(result.failureReason) || "no_path".equals(result.failureReason),
            "Unexpected initial failure " + result.failureReason);
        assertNull(
            WeightedResearchSolver.findRepairPlan(note, new AspectList(), Config.snapshot(), () -> false, result, 3));
        assertEquals(1, note.hexEntries.get("0:0").type);
        assertEquals(1, note.hexEntries.get("2:0").type);
    }

    @Test
    void neverRepairsAGenericNoPathFailure() {
        ResearchNoteData note = quantumGogglesNote();
        WeightedResearchSolver.Result noPath = WeightedResearchSolver.Result.failure("no_path", false);

        assertNull(
            WeightedResearchSolver.findRepairPlan(note, new AspectList(), Config.snapshot(), () -> false, noPath, 3));
    }

    private static void assertRepairsAndSolves(ResearchNoteData note, String expectedFirstRepair) {
        WeightedResearchSolver.Result result = solve(note);

        System.out.printf(
            "%s initial solve=%dms expanded=%d routes=%d peak=%d/%d/%d%n",
            note.key,
            result.solveTimeMs,
            result.expandedStates,
            result.routeSearches,
            result.peakStates,
            result.peakQueue,
            result.peakPlans);
        assertFalse(result.success);
        assertEquals("incompatible_corridor", result.failureReason);
        WeightedResearchSolver.RepairPlan plan = WeightedResearchSolver
            .findRepairPlan(note, new AspectList(), Config.snapshot(), () -> false, result, 3);
        assertTrue(plan != null && !plan.repairCells.isEmpty());
        if (expectedFirstRepair != null) assertEquals(expectedFirstRepair, plan.repairCells.get(0));
        for (String repairCell : plan.repairCells) {
            ResearchManager.HexEntry repaired = note.hexEntries.get(repairCell);
            assertTrue(repaired != null && repaired.type == 1 && repaired.aspect != null);
            note.hexEntries.put(repairCell, new ResearchManager.HexEntry(null, 0));
        }
        result = plan.result;
        System.out.printf(
            "%s repaired=%d solve=%dms expanded=%d placements=%d reason=%s%n",
            note.key,
            plan.repairCells.size(),
            result.solveTimeMs,
            result.expandedStates,
            result.placements.size(),
            result.failureReason);
        assertTrue(result.success);
        assertTrue(WeightedResearchSolver.validateSolution(note, result.placements));
    }

    private static WeightedResearchSolver.Result solve(ResearchNoteData note) {
        return solve(note, new AspectList());
    }

    private static WeightedResearchSolver.Result solve(ResearchNoteData note, AspectList inventory) {
        return WeightedResearchSolver.solve(note, inventory, Config.snapshot(), () -> false);
    }

    private static ResearchNoteData lineNote() {
        ResearchNoteData note = new ResearchNoteData();
        note.key = "solver-test";
        note.hexEntries.put("0:0", new ResearchManager.HexEntry(Aspect.AIR, 1));
        note.hexEntries.put("1:0", new ResearchManager.HexEntry(null, 0));
        note.hexEntries.put("2:0", new ResearchManager.HexEntry(Aspect.FIRE, 1));
        return note;
    }

    private static ResearchNoteData quantumGogglesNote() {
        String cells = "-1:-1 -1:-2 -1:-3 -1:0 -1:1 -1:2 -1:3 -1:4 "
            + "-2:-1 -2:-2 -2:0 -2:1 -2:2 -2:4 -3:-1 -3:0 -3:1 -3:2 -3:3 -3:4 "
            + "-4:0 -4:1 -4:2 -4:3 -4:4 0:-1 0:-2 0:-3 0:-4 0:0 0:1 0:2 0:3 0:4 "
            + "1:-1 1:-2 1:-3 1:-4 1:0 1:1 1:2 1:3 2:-1 2:-2 2:-4 2:0 2:1 2:2 "
            + "3:-4 3:0 3:1 4:-2 4:-3 4:-4 4:0";
        return noteFromRuntimeLog(
            "QuantumGogglesofRevealing",
            cells,
            "-1:-3=lucrum -1:4=potentia -4:1=electrum -4:4=tutamen " + "2:-4=auram 3:1=sensus 4:-2=praecantatio");
    }

    private static ResearchNoteData noteFromRuntimeLog(String key, String cells, String anchors) {
        ResearchNoteData note = new ResearchNoteData();
        note.key = key;
        for (String cell : cells.split(" ")) note.hexEntries.put(cell, new ResearchManager.HexEntry(null, 0));
        for (String anchor : anchors.split(" ")) {
            String[] parts = anchor.split("=");
            Aspect aspect = aspect(parts[1]);
            assertTrue(aspect != null, "Missing test aspect " + parts[1]);
            note.hexEntries.put(parts[0], new ResearchManager.HexEntry(aspect, 1));
        }
        return note;
    }

    private static Aspect aspect(String tag) {
        if ("electrum".equals(tag)) return ELECTRUM;
        if ("infernus".equals(tag)) return INFERNUS;
        return Aspect.getAspect(tag);
    }

    private static Aspect createElectrum() {
        Aspect existing = Aspect.getAspect("electrum");
        return existing == null ? new Aspect("electrum", 0xD8C348, new Aspect[] { Aspect.ENERGY, Aspect.MECHANISM })
            : existing;
    }

    private static Aspect createInfernus() {
        Aspect existing = Aspect.getAspect("infernus");
        return existing == null ? new Aspect("infernus", 0x9A1600, new Aspect[] { Aspect.FIRE, Aspect.MAGIC })
            : existing;
    }

    private static void registerRuntimeAspects() {
        register("caelum", "vitreus", "metallum");
        register("custom1", "cognitio", "ordo");
        register("custom2", "cognitio", "vitium");
        register("custom3", "vacuos", "motus");
        register("custom4", "lux", "custom3");
        register("custom5", "humanus", "iter");
        register("desidia", "vinculum", "spiritus");
        register("gula", "fames", "vacuos");
        register("invidia", "sensus", "fames");
        register("ira", "telum", "ignis");
        register("luxuria", "corpus", "fames");
        register("magneto", "metallum", "iter");
        register("nebrisum", "perfodio", "lucrum");
        register("radio", "lux", "potentia");
        register("strontio", "cognitio", "perditio");
        register("superbia", "volatus", "vacuos");
        register("tabernus", "tutamen", "iter");
        register("tempus", "vacuos", "ordo");
        register("terminus", "lucrum", "alienis");
    }

    private static void register(String tag, String first, String second) {
        if (Aspect.getAspect(tag) != null) return;
        Aspect firstAspect = Aspect.getAspect(first);
        Aspect secondAspect = Aspect.getAspect(second);
        assertTrue(firstAspect != null && secondAspect != null, "Missing components for " + tag);
        new Aspect(tag, 0x777777, new Aspect[] { firstAspect, secondAspect });
    }

    private static boolean compatible(Aspect left, Aspect right) {
        Aspect[] components = left.getComponents();
        if (components != null && (components[0] == right || components[1] == right)) return true;
        components = right.getComponents();
        return components != null && (components[0] == left || components[1] == left);
    }
}

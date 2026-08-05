package com.Emil.TCAutoResearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.client.gui.GuiResearchTable;
import thaumcraft.client.lib.PlayerNotifications;
import thaumcraft.common.Thaumcraft;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;
import thaumcraft.common.lib.utils.HexUtils;

public final class ResearchSolveController {

    private static final int ACK_TIMEOUT_TICKS = 40;
    private static final int COMPLETE_TIMEOUT_TICKS = 100;
    private static final int MAX_RETRIES = 3;
    private static final int MAX_REPAIRS = 3;
    private static final AtomicLong GENERATION = new AtomicLong();

    private static volatile WeightedResearchSolver.Result lastResult;
    private static volatile CompletionReport lastReport;
    private static volatile String lastPuzzle;
    private static volatile long lastSettingsRevision;
    private static volatile boolean childScreenTransition;
    private static volatile String activePuzzle;
    private static volatile String suppressedAutomaticPuzzle;
    private static ExecutionState execution;
    private static RepairState repair;
    private static SolveListener completionListener = SolveListener.NONE;

    private ResearchSolveController() {}

    public static void request(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc,
        boolean automatic) {
        request(helper, player, mc, automatic, null);
    }

    public static void request(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc,
        boolean automatic, SolveListener listener) {
        AspectSynthesisController.cancel();
        completionListener = listener == null ? SolveListener.NONE : listener;
        GuiResearchTable gui = (GuiResearchTable) helper;
        ResearchNoteData note = gui.note;
        if (note == null) {
            PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.no_note"));
            notifyFailure("tcautores.no_note");
            return;
        }
        if (note.complete) {
            lastReport = CompletionReport.empty(note.key, "tcautores.report_already_complete", true)
                .withBoard(note, Collections.emptyMap());
            PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.note_complete"));
            notifySuccess();
            return;
        }

        submitSolve(helper, player, mc, automatic, new LinkedHashSet<>(), true);
    }

    private static void submitSolve(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc,
        boolean automatic, Set<String> repairedCells, boolean notify) {
        GuiResearchTable gui = (GuiResearchTable) helper;
        ResearchNoteData note = gui.note;
        if (note == null || note.complete) return;
        long generation = GENERATION.incrementAndGet();
        execution = null;
        repair = null;
        String puzzle = ResearchNoteFingerprint.topology(note);
        if (automatic && completionListener == SolveListener.NONE && puzzle.equals(suppressedAutomaticPuzzle)) return;
        if (!automatic) suppressedAutomaticPuzzle = null;
        activePuzzle = puzzle;
        ResearchNoteData noteSnapshot = ResearchNoteSnapshot.copyOf(note);
        Config.SolverSettings settings = Config.snapshot();
        AspectList inventory = snapshotInventory(helper.availableAspects());
        lastReport = CompletionReport.empty(note.key, "tcautores.report_calculating", false)
            .withBoard(note, Collections.emptyMap());
        if (notify) PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.solving"));

        ResearchTaskExecutor.submitReplacing(() -> {
            WeightedResearchSolver.Result result = WeightedResearchSolver.solve(
                noteSnapshot,
                inventory,
                settings,
                () -> generation != GENERATION.get() || Thread.currentThread()
                    .isInterrupted());
            WeightedResearchSolver.RepairPlan repairPlan = !result.success
                && "incompatible_corridor".equals(result.failureReason)
                    ? WeightedResearchSolver.findRepairPlan(
                        noteSnapshot,
                        inventory,
                        settings,
                        () -> generation != GENERATION.get() || Thread.currentThread()
                            .isInterrupted(),
                        result,
                        MAX_REPAIRS - repairedCells.size())
                    : null;
            if (generation != GENERATION.get() || Thread.currentThread()
                .isInterrupted()) return;
            runOnClient(
                mc,
                () -> handleSolved(
                    result,
                    puzzle,
                    generation,
                    settings,
                    automatic,
                    new LinkedHashSet<>(repairedCells),
                    repairPlan,
                    helper,
                    player,
                    mc));
        });
    }

    public static void retry(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc) {
        GuiResearchTable gui = (GuiResearchTable) helper;
        if (gui.note == null || lastResult == null) {
            player.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("tcautores.no_previous")));
            return;
        }
        if (!ResearchNoteFingerprint.topology(gui.note)
            .equals(lastPuzzle)) {
            player.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("tcautores.note_changed")));
            return;
        }
        if (Config.snapshot().revision != lastSettingsRevision) {
            player.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("tcautores.weights_changed")));
            return;
        }
        long generation = GENERATION.incrementAndGet();
        ResearchTaskExecutor.cancel();
        startExecution(lastResult, lastPuzzle, generation, helper, player, mc, false);
    }

    public static void cancel() {
        GENERATION.incrementAndGet();
        execution = null;
        repair = null;
        activePuzzle = null;
        completionListener = SolveListener.NONE;
        ResearchTaskExecutor.cancel();
    }

    public static void prepareChildScreen() {
        childScreenTransition = true;
    }

    public static boolean onResearchGuiClosed() {
        if (childScreenTransition) {
            childScreenTransition = false;
            return false;
        }
        cancel();
        return true;
    }

    public static void clientTick() {
        RepairState currentRepair = repair;
        if (currentRepair != null) {
            currentRepair.tick();
            return;
        }
        ExecutionState current = execution;
        if (current == null) return;
        if (!current.valid()) {
            execution = null;
            activePuzzle = null;
            notifyFailure("tcautores.note_changed");
            return;
        }
        current.tick();
    }

    public static boolean isProcessing(ResearchNoteData note) {
        return note != null && (activePuzzle != null || repair != null || execution != null);
    }

    public static boolean isAutomaticSuppressed(ResearchNoteData note) {
        return note != null && ResearchNoteFingerprint.topology(note)
            .equals(suppressedAutomaticPuzzle);
    }

    public static CompletionReport getLastReport() {
        return lastReport;
    }

    private static void handleSolved(WeightedResearchSolver.Result result, String puzzle, long generation,
        Config.SolverSettings settings, boolean automatic, Set<String> repairedCells,
        WeightedResearchSolver.RepairPlan repairPlan, GuiResearchTableHelperInterface helper, EntityPlayer player,
        Minecraft mc) {
        GuiResearchTable gui = (GuiResearchTable) helper;
        if (generation != GENERATION.get() || gui.note == null
            || !puzzle.equals(ResearchNoteFingerprint.topology(gui.note))) {
            if (puzzle.equals(activePuzzle)) activePuzzle = null;
            if (generation == GENERATION.get()) notifyFailure("tcautores.note_changed");
            return;
        }
        if (!result.success) {
            if (repairPlan != null && !repairPlan.repairCells.isEmpty()
                && repairPlan.repairCells.size() <= MAX_REPAIRS - repairedCells.size()) {
                startRepair(
                    result,
                    puzzle,
                    generation,
                    automatic,
                    repairedCells,
                    repairPlan.repairCells,
                    helper,
                    player,
                    mc);
                return;
            }
            activePuzzle = null;
            if (automatic) suppressedAutomaticPuzzle = puzzle;
            String failure = SolverLocalization.failure(result.failureReason);
            lastReport = CompletionReport
                .fromResult(gui.note.key, "tcautores.report_failed", failure, false, result, 0, 0, 0, 0)
                .withBoard(gui.note, result.placements);
            notifyFailure("tcautores.solve_failed");
            if (automatic) {
                showExceptionPreview(
                    gui,
                    gui.note,
                    result,
                    StatCollector.translateToLocal("tcautores.solve_failed") + ": " + failure,
                    mc);
                return;
            }
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("tcautores.solve_failed") + ": " + failure));
            return;
        }
        lastResult = result;
        lastPuzzle = puzzle;
        lastSettingsRevision = settings.revision;
        lastReport = CompletionReport.fromResult(gui.note.key, "tcautores.report_ready", "", false, result, 0, 0, 0, 0)
            .withBoard(gui.note, result.placements);
        Runnable execute = () -> startExecution(result, puzzle, generation, helper, player, mc, automatic);
        if (settings.preview && !automatic) {
            prepareChildScreen();
            mc.displayGuiScreen(new GuiSolvePreview((GuiScreen) gui, gui.note, result, execute, true, ""));
        } else {
            execute.run();
        }
    }

    private static void startRepair(WeightedResearchSolver.Result result, String puzzle, long generation,
        boolean automatic, Set<String> repairedCells, List<String> repairCells, GuiResearchTableHelperInterface helper,
        EntityPlayer player, Minecraft mc) {
        if (generation != GENERATION.get()) return;
        GuiResearchTable gui = (GuiResearchTable) helper;
        String firstCell = repairCells.get(0);
        ResearchManager.HexEntry entry = gui.note.hexEntries.get(firstCell);
        if (entry == null || entry.type < 1 || entry.aspect == null) {
            handleRepairFailure(result, puzzle, automatic, helper, player, mc, "tcautores.note_changed");
            return;
        }
        activePuzzle = puzzle;
        lastReport = CompletionReport
            .fromResult(
                gui.note.key,
                "tcautores.report_repairing",
                AspectLocalization.name(entry.aspect),
                false,
                result,
                0,
                0,
                0,
                0)
            .withBoard(gui.note, result.placements);
        repair = new RepairState(result, puzzle, generation, automatic, repairedCells, repairCells, helper, player, mc);
        PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.repairing"));
    }

    private static void handleRepairFailure(WeightedResearchSolver.Result result, String puzzle, boolean automatic,
        GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc, String failureKey) {
        GuiResearchTable gui = (GuiResearchTable) helper;
        repair = null;
        activePuzzle = null;
        if (automatic) suppressedAutomaticPuzzle = puzzle;
        String failure = StatCollector.translateToLocal(failureKey);
        lastReport = CompletionReport
            .fromResult(
                gui.note == null ? "" : gui.note.key,
                "tcautores.report_failed",
                failure,
                false,
                result,
                0,
                0,
                0,
                0)
            .withBoard(gui.note, result.placements);
        player.addChatMessage(new ChatComponentText(failure));
        notifyFailure(failureKey);
        if (automatic) showExceptionPreview(gui, gui.note, result, failure, mc);
    }

    private static void startExecution(WeightedResearchSolver.Result result, String puzzle, long generation,
        GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc, boolean automatic) {
        if (generation != GENERATION.get()) return;
        GuiResearchTable gui = (GuiResearchTable) helper;
        if (!WeightedResearchSolver.validateSolution(gui.note, result.placements)) {
            activePuzzle = null;
            if (automatic) suppressedAutomaticPuzzle = puzzle;
            lastReport = CompletionReport
                .fromResult(
                    gui.note.key,
                    "tcautores.report_failed",
                    StatCollector.translateToLocal("tcautores.invalid_solution"),
                    false,
                    result,
                    0,
                    0,
                    0,
                    0)
                .withBoard(gui.note, result.placements);
            notifyFailure("tcautores.invalid_solution");
            if (automatic) {
                showExceptionPreview(
                    gui,
                    gui.note,
                    result,
                    StatCollector.translateToLocal("tcautores.invalid_solution"),
                    mc);
                return;
            }
            player.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("tcautores.invalid_solution")));
            return;
        }
        if (!result.missingPrimals.isEmpty()) {
            activePuzzle = null;
            if (automatic) suppressedAutomaticPuzzle = puzzle;
            lastReport = CompletionReport
                .fromResult(
                    gui.note.key,
                    "tcautores.report_failed",
                    StatCollector.translateToLocal("tcautores.insufficient"),
                    false,
                    result,
                    0,
                    0,
                    0,
                    0)
                .withBoard(gui.note, result.placements);
            notifyFailure("tcautores.insufficient");
            if (automatic) {
                showExceptionPreview(
                    gui,
                    gui.note,
                    result,
                    StatCollector.translateToLocal("tcautores.insufficient"),
                    mc);
                return;
            }
            player.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("tcautores.insufficient")));
            return;
        }
        activePuzzle = puzzle;
        execution = new ExecutionState(result, puzzle, generation, helper, player, mc, automatic);
    }

    private static void showExceptionPreview(GuiResearchTable gui, ResearchNoteData note,
        WeightedResearchSolver.Result result, String warning, Minecraft mc) {
        if (mc.currentScreen != gui) return;
        prepareChildScreen();
        mc.displayGuiScreen(new GuiSolvePreview(gui, note, result, () -> {}, false, warning, true));
    }

    private static AspectList snapshotInventory(AspectList source) {
        AspectList snapshot = new AspectList();
        for (Object value : Aspect.aspects.values()) {
            Aspect aspect = (Aspect) value;
            int amount = source.getAmount(aspect);
            if (amount > 0) snapshot.add(aspect, amount);
        }
        return snapshot;
    }

    private static void runOnClient(Minecraft mc, Runnable action) {
        mc.func_152344_a(action);
    }

    private static final class RepairState {

        final WeightedResearchSolver.Result result;
        final String puzzle;
        final long generation;
        final boolean automatic;
        final Set<String> repairedCells;
        final List<String> pendingCells;
        final GuiResearchTableHelperInterface helper;
        final EntityPlayer player;
        final Minecraft mc;
        final GuiResearchTable gui;
        String cell;
        Aspect originalAspect;
        int originalType;
        int tick;
        int deadline;
        int retries;
        boolean waitingForInk;

        private RepairState(WeightedResearchSolver.Result result, String puzzle, long generation, boolean automatic,
            Set<String> repairedCells, List<String> repairCells, GuiResearchTableHelperInterface helper,
            EntityPlayer player, Minecraft mc) {
            this.result = result;
            this.puzzle = puzzle;
            this.generation = generation;
            this.automatic = automatic;
            this.repairedCells = new LinkedHashSet<>(repairedCells);
            this.pendingCells = new ArrayList<>(repairCells);
            this.helper = helper;
            this.player = player;
            this.mc = mc;
            this.gui = (GuiResearchTable) helper;
            selectCurrentCell(gui.note);
        }

        void tick() {
            tick++;
            ResearchNoteData note = gui.note;
            if (generation != GENERATION.get() || note == null) {
                repair = null;
                activePuzzle = null;
                return;
            }
            ResearchManager.HexEntry entry = note.hexEntries.get(cell);
            if (entry == null) {
                fail("tcautores.note_changed");
                return;
            }
            if (entry.type == 0 && entry.aspect == null) {
                TCAutoResearch.LOG.info("Server confirmed research repair key={} cell={}", note.key, cell);
                repairedCells.add(cell);
                pendingCells.remove(0);
                retries = 0;
                deadline = 0;
                if (pendingCells.isEmpty()) {
                    repair = null;
                    submitSolve(helper, player, mc, automatic, repairedCells, false);
                } else if (!selectCurrentCell(note)) {
                    fail("tcautores.note_changed");
                }
                return;
            }
            if (entry.type != originalType || entry.aspect != originalAspect) {
                fail("tcautores.note_changed");
                return;
            }
            if (awaitInk()) return;
            if (retries > 0 && tick < deadline) return;
            if (retries >= MAX_RETRIES) {
                fail("tcautores.repair_timeout");
                return;
            }
            HexUtils.Hex hex = note.hexes.get(cell);
            if (hex == null) {
                fail("tcautores.note_changed");
                return;
            }
            retries++;
            deadline = tick + ACK_TIMEOUT_TICKS;
            if (retries == 1) {
                TCAutoResearch.LOG.info(
                    "Applying verified research repair key={} cell={} aspect={}",
                    note.key,
                    cell,
                    originalAspect.getTag());
            }
            helper.place(hex, null);
        }

        private boolean awaitInk() {
            if (helper.hasInk()) {
                if (waitingForInk) {
                    waitingForInk = false;
                    retries = 0;
                    deadline = 0;
                    PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.ink_resumed"));
                    lastReport = CompletionReport
                        .fromResult(
                            gui.note.key,
                            "tcautores.report_repairing",
                            AspectLocalization.name(originalAspect),
                            false,
                            result,
                            0,
                            0,
                            0,
                            0)
                        .withBoard(gui.note, result.placements);
                }
                return false;
            }
            if (!waitingForInk) {
                waitingForInk = true;
                retries = 0;
                deadline = 0;
                notifyInkPause(player);
                lastReport = CompletionReport
                    .fromResult(
                        gui.note.key,
                        "tcautores.report_waiting_ink",
                        StatCollector.translateToLocal("tcautores.ink_solution"),
                        false,
                        result,
                        0,
                        0,
                        0,
                        0)
                    .withBoard(gui.note, result.placements);
            }
            return true;
        }

        private boolean selectCurrentCell(ResearchNoteData note) {
            if (note == null || pendingCells.isEmpty()) return false;
            cell = pendingCells.get(0);
            ResearchManager.HexEntry entry = note.hexEntries.get(cell);
            if (entry == null || entry.type < 1 || entry.aspect == null) return false;
            originalType = entry.type;
            originalAspect = entry.aspect;
            return true;
        }

        private void fail(String failureKey) {
            handleRepairFailure(result, puzzle, automatic, helper, player, mc, failureKey);
        }
    }

    private static final class ExecutionState {

        private enum Phase {
            SYNTHESIS,
            PLACEMENT,
            COMPLETION
        }

        final String puzzle;
        final String identity;
        final long generation;
        final GuiResearchTableHelperInterface helper;
        final EntityPlayer player;
        final Minecraft mc;
        final GuiResearchTable gui;
        final WeightedResearchSolver.Result result;
        final boolean automatic;
        final Map<Aspect, Integer> required = new LinkedHashMap<>();
        final Map<String, Aspect> placementMap;
        final List<Map.Entry<String, Aspect>> placements;
        final long startedAtMs;
        Phase phase = Phase.SYNTHESIS;
        Aspect pendingAspect;
        int pendingAmount;
        int tick;
        int deadline;
        int retries;
        int placementRounds;
        int placementPackets;
        int combinationPackets;
        boolean waitingForInk;

        private ExecutionState(WeightedResearchSolver.Result result, String puzzle, long generation,
            GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc, boolean automatic) {
            this.puzzle = puzzle;
            this.generation = generation;
            this.helper = helper;
            this.player = player;
            this.mc = mc;
            this.gui = (GuiResearchTable) helper;
            this.result = result;
            this.automatic = automatic;
            this.startedAtMs = System.currentTimeMillis();
            this.identity = ResearchNoteFingerprint.identity(gui.note);
            this.placementMap = result.placements;
            this.placements = new ArrayList<>(placementMap.entrySet());
            for (Map.Entry<String, Aspect> placement : result.placements.entrySet()) {
                ResearchManager.HexEntry current = gui.note.hexEntries.get(placement.getKey());
                if (current == null || current.aspect != placement.getValue()) {
                    Aspect aspect = placement.getValue();
                    required.put(aspect, required.getOrDefault(aspect, 0) + 1);
                }
            }
            addUndiscoveredAnchors(
                required,
                gui.note,
                aspect -> Thaumcraft.proxy.getPlayerKnowledge()
                    .hasDiscoveredAspect(player.getCommandSenderName(), aspect));
            publishReport("tcautores.report_running", "", false);
        }

        boolean valid() {
            ResearchNoteData current = gui.note;
            return generation == GENERATION.get() && current != null
                && (current.complete ? identity.equals(ResearchNoteFingerprint.identity(current))
                    : puzzle.equals(ResearchNoteFingerprint.topology(current)));
        }

        void tick() {
            tick++;
            ResearchNoteData current = gui.note;
            if (current != null && current.complete) {
                succeed();
                return;
            }
            if (phase == Phase.SYNTHESIS) tickSynthesis();
            else if (phase == Phase.PLACEMENT) tickPlacement();
            else if (tick >= deadline) fail("tcautores.completion_unconfirmed");
        }

        private void tickSynthesis() {
            AspectList inventory = helper.availableAspects();
            if (pendingAspect != null) {
                if (inventory.getAmount(pendingAspect) > pendingAmount) {
                    pendingAspect = null;
                    retries = 0;
                } else if (tick >= deadline) {
                    if (retries >= MAX_RETRIES) {
                        fail("tcautores.execution_timeout");
                        return;
                    }
                    sendCombination(pendingAspect, inventory);
                    return;
                } else {
                    return;
                }
            }

            Aspect shortage = null;
            for (Map.Entry<Aspect, Integer> entry : required.entrySet()) {
                if (inventory.getAmount(entry.getKey()) < entry.getValue()) {
                    shortage = entry.getKey();
                    break;
                }
            }
            if (shortage == null) {
                phase = Phase.PLACEMENT;
                tickPlacement();
                return;
            }
            AspectSynthesisPlanner.Step synthesis = AspectSynthesisPlanner.next(shortage, inventory);
            if (synthesis.craft == null) {
                fail("tcautores.insufficient");
                return;
            }
            pendingAspect = synthesis.craft;
            pendingAmount = inventory.getAmount(synthesis.craft);
            retries = 0;
            sendCombination(synthesis.craft, inventory);
        }

        private void sendCombination(Aspect aspect, AspectList inventory) {
            Aspect[] components = aspect.getComponents();
            if (components == null) {
                fail("tcautores.insufficient");
                return;
            }
            pendingAmount = inventory.getAmount(aspect);
            retries++;
            deadline = tick + ACK_TIMEOUT_TICKS;
            combinationPackets++;
            publishReport("tcautores.report_running", "", false);
            helper.combine(components[0], components[1]);
        }

        private void tickPlacement() {
            ResearchNoteData note = gui.note;
            if (note == null) {
                fail("tcautores.note_changed");
                return;
            }
            PlacementBatch batch = scanPlacements(placements, note);
            if (batch.conflict) {
                fail("tcautores.note_changed");
                return;
            }
            if (batch.missing.isEmpty()) {
                phase = Phase.COMPLETION;
                deadline = tick + COMPLETE_TIMEOUT_TICKS;
                return;
            }
            if (awaitInk()) return;
            if (retries > 0 && tick < deadline) return;
            if (retries >= MAX_RETRIES) {
                fail("tcautores.execution_timeout");
                return;
            }
            if (!WeightedResearchSolver.validateSolution(note, placementMap)) {
                fail("tcautores.invalid_solution");
                return;
            }
            sendPlacementBatch(note, batch.missing);
        }

        private void sendPlacementBatch(ResearchNoteData note, List<Map.Entry<String, Aspect>> missing) {
            retries++;
            deadline = tick + ACK_TIMEOUT_TICKS;
            placementRounds++;
            placementPackets += missing.size();
            publishReport("tcautores.report_running", "", false);
            for (Map.Entry<String, Aspect> placement : missing) {
                HexUtils.Hex hex = note.hexes.get(placement.getKey());
                if (hex == null) {
                    fail("tcautores.note_changed");
                    return;
                }
                helper.place(hex, placement.getValue());
            }
        }

        private boolean awaitInk() {
            if (helper.hasInk()) {
                if (waitingForInk) {
                    waitingForInk = false;
                    retries = 0;
                    deadline = 0;
                    PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.ink_resumed"));
                    publishReport("tcautores.report_running", "", false);
                }
                return false;
            }
            if (!waitingForInk) {
                waitingForInk = true;
                retries = 0;
                deadline = 0;
                notifyInkPause(player);
                publishReport(
                    "tcautores.report_waiting_ink",
                    StatCollector.translateToLocal("tcautores.ink_solution"),
                    false);
            }
            return true;
        }

        private void succeed() {
            execution = null;
            activePuzzle = null;
            suppressedAutomaticPuzzle = null;
            publishReport("tcautores.report_completed", "", true);
            PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.success"));
            notifySuccess();
        }

        private void fail(String key) {
            execution = null;
            activePuzzle = null;
            if (automatic) suppressedAutomaticPuzzle = puzzle;
            publishReport("tcautores.report_failed", reportDetail(key, pendingAspect), false);
            player.addChatMessage(
                new ChatComponentText(
                    StatCollector.translateToLocal(key)
                        + (pendingAspect == null ? "" : " " + AspectLocalization.name(pendingAspect))));
            notifyFailure(key);
            showExceptionPreview(gui, gui.note, result, reportDetail(key, pendingAspect), mc);
        }

        private void publishReport(String stateKey, String detail, boolean serverConfirmed) {
            lastReport = CompletionReport
                .fromResult(
                    gui.note == null ? "" : gui.note.key,
                    stateKey,
                    detail,
                    serverConfirmed,
                    result,
                    System.currentTimeMillis() - startedAtMs,
                    placementRounds,
                    placementPackets,
                    combinationPackets)
                .withBoard(gui.note, result.placements);
        }
    }

    private static String reportDetail(String key, Aspect pending) {
        String detail = StatCollector.translateToLocal(key);
        return pending == null ? detail : detail + " " + AspectLocalization.name(pending);
    }

    private static void notifyInkPause(EntityPlayer player) {
        PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.ink_empty"));
        player.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("tcautores.ink_solution")));
    }

    private static void notifySuccess() {
        SolveListener listener = completionListener;
        completionListener = SolveListener.NONE;
        listener.onSuccess();
    }

    private static void notifyFailure(String key) {
        SolveListener listener = completionListener;
        completionListener = SolveListener.NONE;
        listener.onFailure(key);
    }

    public interface SolveListener {

        SolveListener NONE = new SolveListener() {

            @Override
            public void onSuccess() {}

            @Override
            public void onFailure(String key) {}
        };

        void onSuccess();

        void onFailure(String key);
    }

    static PlacementBatch scanPlacements(List<Map.Entry<String, Aspect>> placements, ResearchNoteData note) {
        List<Map.Entry<String, Aspect>> missing = new ArrayList<>();
        for (Map.Entry<String, Aspect> placement : placements) {
            ResearchManager.HexEntry current = note.hexEntries.get(placement.getKey());
            if (current == null || current.aspect != null && current.aspect != placement.getValue()) {
                return new PlacementBatch(Collections.emptyList(), true);
            }
            if (current.aspect == null) missing.add(placement);
        }
        return new PlacementBatch(missing, false);
    }

    static void addUndiscoveredAnchors(Map<Aspect, Integer> required, ResearchNoteData note,
        Predicate<Aspect> discovered) {
        for (ResearchManager.HexEntry entry : note.hexEntries.values()) {
            if (entry.type != 1 || entry.aspect == null || discovered.test(entry.aspect)) continue;
            required.put(entry.aspect, Math.max(1, required.getOrDefault(entry.aspect, 0)));
        }
    }

    static final class PlacementBatch {

        final List<Map.Entry<String, Aspect>> missing;
        final boolean conflict;

        private PlacementBatch(List<Map.Entry<String, Aspect>> missing, boolean conflict) {
            this.missing = missing;
            this.conflict = conflict;
        }
    }
}

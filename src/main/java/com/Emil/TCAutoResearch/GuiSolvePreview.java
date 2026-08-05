package com.Emil.TCAutoResearch;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import thaumcraft.common.lib.research.ResearchNoteData;

public class GuiSolvePreview extends GuiResearchBoardScreen {

    private final GuiScreen parent;
    private final ResearchNoteData note;
    private final WeightedResearchSolver.Result result;
    private final Runnable confirm;
    private final boolean allowExecute;
    private final String warning;
    private final boolean showBoard;

    public GuiSolvePreview(GuiScreen parent, WeightedResearchSolver.Result result, Runnable confirm) {
        this(parent, null, result, confirm, true, "", false);
    }

    public GuiSolvePreview(GuiScreen parent, ResearchNoteData note, WeightedResearchSolver.Result result,
        Runnable confirm, boolean allowExecute, String warning) {
        this(parent, note, result, confirm, allowExecute, warning, false);
    }

    public GuiSolvePreview(GuiScreen parent, ResearchNoteData note, WeightedResearchSolver.Result result,
        Runnable confirm, boolean allowExecute, String warning, boolean showBoard) {
        this.parent = parent;
        this.note = note;
        this.result = result;
        this.confirm = confirm;
        this.allowExecute = allowExecute;
        this.warning = warning == null ? "" : warning;
        this.showBoard = showBoard;
    }

    @Override
    public void initGui() {
        int center = width / 2;
        buttonList.clear();
        GuiButton execute = researchButton(
            0,
            center - 105,
            height - 30,
            100,
            20,
            StatCollector.translateToLocal("tcautores.execute"));
        execute.enabled = allowExecute && result.success
            && result.missingPrimals.isEmpty()
            && (note == null || WeightedResearchSolver.validateSolution(note, result.placements));
        buttonList.add(execute);
        buttonList
            .add(researchButton(1, center + 5, height - 30, 100, 20, StatCollector.translateToLocal("gui.cancel")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0 && button.enabled) {
            confirm.run();
            mc.displayGuiScreen(parent);
        } else {
            ResearchSolveController.cancel();
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            ResearchSolveController.cancel();
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawResearchBackground();
        int center = width / 2;
        if (showBoard) drawResearchPanel(center - 205, 34, center + 205, height - 36);
        else drawResearchPanel(center - 145, 34, center + 145, height - 36);
        drawCenteredString(fontRendererObj, StatCollector.translateToLocal("tcautores.preview"), center, 18, 0xFFFFFF);
        if (showBoard) {
            int boardCenterY = (height + 65) / 2;
            drawResearchBoard(note, result.placements, center - 100, boardCenterY, 185, height - 115, 44);
            drawStats(center + 8);
        } else {
            drawStats(center - 90);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (showBoard) drawResearchBoardTooltip(mouseX, mouseY);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawStats(int x) {
        int y = 45;
        List<String> lines = new ArrayList<>();
        if (!warning.isEmpty()) lines.add(warning);
        lines.add(StatCollector.translateToLocal("tcautores.mode") + ": " + SolverLocalization.mode(result.mode));
        lines.add(StatCollector.translateToLocal("tcautores.total_cost") + ": " + result.totalCost);
        lines.add(StatCollector.translateToLocal("tcautores.placements") + ": " + result.placements.size());
        lines.add(StatCollector.translateToLocal("tcautores.synthesis") + ": " + result.synthesisOperations);
        lines.add(String.format(StatCollector.translateToLocal("tcautores.solve_time"), result.solveTimeMs));
        lines.add(String.format(StatCollector.translateToLocal("tcautores.expanded_states"), result.expandedStates));
        lines.add(
            String.format(
                StatCollector.translateToLocal("tcautores.peak_search"),
                result.peakStates,
                result.peakQueue,
                result.peakPlans));
        lines.add(
            String.format(
                StatCollector.translateToLocal("tcautores.cache_status"),
                cacheText(result.resultCacheHit),
                cacheText(result.graphCacheHit)));
        if (result.fallbackUsed) lines.add(StatCollector.translateToLocal("tcautores.fallback"));
        if (!result.success) {
            lines.add(
                StatCollector.translateToLocal("tcautores.solve_failed") + ": "
                    + SolverLocalization.failure(result.failureReason));
        } else if (result.missingPrimals.isEmpty()) {
            lines.add(StatCollector.translateToLocal("tcautores.inventory_ready"));
        } else {
            lines.add(StatCollector.translateToLocal("tcautores.missing") + ": " + result.missingPrimals);
        }
        for (String line : lines) {
            int color = warning.equals(line) || line.startsWith(StatCollector.translateToLocal("tcautores.missing"))
                || line.startsWith(StatCollector.translateToLocal("tcautores.solve_failed")) ? 0xEF8585 : 0xD7E7DF;
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(line, 185), x, y, color);
            y += 13;
        }
    }

}

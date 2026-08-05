package com.Emil.TCAutoResearch;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

public class GuiCompletionReport extends GuiResearchBoardScreen {

    private final GuiScreen parent;
    private final CompletionReport report;
    private boolean boardEnabled;

    public GuiCompletionReport(GuiScreen parent, CompletionReport report) {
        this.parent = parent;
        this.report = report;
        this.boardEnabled = Config.reportBoardPreview();
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int toggleX = Math.max(width / 2 + 20, width - 120);
        buttonList.add(researchButton(1, toggleX, 36, 105, 20, boardText()));
        buttonList
            .add(researchButton(0, width / 2 - 50, height - 24, 100, 20, StatCollector.translateToLocal("gui.done")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
        } else if (button.id == 1) {
            boardEnabled = !boardEnabled;
            Config.setReportBoardPreview(boardEnabled);
            Config.saveSolverConfiguration();
            button.displayString = boardText();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawResearchBackground();
        int center = width / 2;
        boolean showBoard = boardEnabled && report != null && report.note != null;
        if (showBoard) {
            drawResearchPanel(8, 34, Math.max(12, center - 8), height - 30);
            drawResearchPanel(Math.min(width - 12, center + 8), 34, width - 8, height - 30);
            drawResearchBoard(
                report.note,
                report.plannedPlacements,
                center / 2,
                (height + 34) / 2,
                Math.max(50, center - 35),
                Math.max(50, height - 115),
                44);
            drawStats(center + 18, Math.max(80, width / 2 - 28));
        } else {
            drawResearchPanel(center - 155, 34, center + 155, height - 30);
            drawStats(center - 140, 280);
        }
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("tcautores.report_title"),
            center,
            18,
            0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (showBoard) drawResearchBoardTooltip(mouseX, mouseY);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawStats(int x, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (report == null) {
            lines.add(StatCollector.translateToLocal("tcautores.report_empty"));
        } else {
            lines.add(StatCollector.translateToLocal("tcautores.report_note") + ": " + report.noteKey);
            lines.add(
                StatCollector.translateToLocal("tcautores.report_state") + ": "
                    + StatCollector.translateToLocal(report.stateKey));
            lines.add(
                StatCollector.translateToLocal("tcautores.report_server") + ": "
                    + StatCollector
                        .translateToLocal(report.serverConfirmed ? "tcautores.report_yes" : "tcautores.report_no"));
            if (!report.detail.isEmpty()) {
                lines.add(StatCollector.translateToLocal("tcautores.report_detail") + ": " + report.detail);
            }
            lines.add(String.format(StatCollector.translateToLocal("tcautores.solve_time"), report.solveTimeMs));
            lines.add(
                StatCollector.translateToLocal("tcautores.report_execution_time") + ": "
                    + report.executionTimeMs
                    + " ms");
            lines.add(
                StatCollector.translateToLocal("tcautores.placements") + ": "
                    + report.placements
                    + " / "
                    + StatCollector.translateToLocal("tcautores.report_packets")
                    + ": "
                    + report.placementPackets);
            lines.add(
                StatCollector.translateToLocal("tcautores.report_rounds") + ": "
                    + report.placementRounds
                    + " / "
                    + StatCollector.translateToLocal("tcautores.report_combinations")
                    + ": "
                    + report.combinationPackets);
            lines.add(StatCollector.translateToLocal("tcautores.total_cost") + ": " + report.totalCost);
            lines.add(StatCollector.translateToLocal("tcautores.synthesis") + ": " + report.synthesisOperations);
            lines
                .add(String.format(StatCollector.translateToLocal("tcautores.expanded_states"), report.expandedStates));
            lines.add(
                String.format(
                    StatCollector.translateToLocal("tcautores.peak_search"),
                    report.peakStates,
                    report.peakQueue,
                    report.peakPlans));
            lines.add(
                String.format(
                    StatCollector.translateToLocal("tcautores.cache_status"),
                    cacheText(report.resultCacheHit),
                    cacheText(report.graphCacheHit)));
            if (report.fallbackUsed) lines.add(StatCollector.translateToLocal("tcautores.fallback"));
        }
        int y = 48;
        for (String line : lines) {
            int color = report != null && report.serverConfirmed ? 0xA8E0B2 : 0xD7E7DF;
            if (report != null && !report.detail.isEmpty() && line.contains(report.detail)) color = 0xEF8585;
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(line, maxWidth), x, y, color);
            y += 13;
        }
    }

    private String boardText() {
        return StatCollector
            .translateToLocal(boardEnabled ? "tcautores.report_board_on" : "tcautores.report_board_off");
    }

}

package com.Emil.TCAutoResearch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.client.config.GuiButtonExt;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.client.lib.UtilsFX;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;
import thaumcraft.common.lib.utils.HexUtils;

/** Shared adaptive board renderer for previews and completion reports. */
public abstract class GuiResearchBoardScreen extends GuiScreen {

    private BoardView boardView;

    protected final void drawResearchBackground() {
        drawDefaultBackground();
    }

    protected final void drawResearchPanel(int left, int top, int right, int bottom) {
        drawGradientRect(left, top, right, bottom, 0xE0222222, 0xE0101010);
    }

    protected final GuiButton researchButton(int id, int x, int y, int width, int height, String text) {
        return new GuiButtonExt(id, x, y, width, height, text);
    }

    protected final void drawResearchBoard(ResearchNoteData note, Map<String, Aspect> planned, int centerX, int centerY,
        int availableWidth, int availableHeight, int titleY) {
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("tcautores.preview_board"),
            centerX,
            titleY,
            0xD7E7DF);
        if (note == null) {
            boardView = null;
            drawCenteredString(
                fontRendererObj,
                StatCollector.translateToLocal("tcautores.preview_no_board"),
                centerX,
                centerY,
                0xAAAAAA);
            return;
        }

        Map<String, Aspect> placements = planned == null ? java.util.Collections.emptyMap() : planned;
        Bounds bounds = bounds(note);
        int scale = boardScale(bounds, availableWidth, availableHeight);
        int originX = centerX - (int) Math.round((bounds.minX + bounds.maxX) * scale / 2.0D);
        int originY = centerY - (int) Math.round((bounds.minY + bounds.maxY) * scale / 2.0D);
        Set<String> conflicts = conflicts(note, placements);
        boardView = new BoardView(note, placements, conflicts, scale, originX, originY);

        for (Map.Entry<String, HexUtils.Hex> entry : note.hexes.entrySet()) {
            String key = entry.getKey();
            HexUtils.Pixel pixel = entry.getValue()
                .toPixel(scale);
            int x = originX + (int) Math.round(pixel.x);
            int y = originY + (int) Math.round(pixel.y);
            ResearchManager.HexEntry current = note.hexEntries.get(key);
            Aspect plannedAspect = placements.get(key);
            Aspect aspect = plannedAspect != null ? plannedAspect : current == null ? null : current.aspect;
            boolean anchor = current != null && current.type == 1;
            boolean conflict = conflicts.contains(key);
            int border = conflict ? 0xFFE04D4D : anchor ? 0xFFE0B85C : aspect == null ? 0xFF53615D : 0xFF6E988B;
            int fill = conflict ? 0xCC5A2020 : aspect == null ? 0x99363E3B : 0xCC1F302C;
            drawHexPolygon(x, y, Math.max(6.0F, scale + 2.0F), border);
            drawHexPolygon(x, y, Math.max(4.0F, scale), fill);
        }
        GuiThemeRenderer.isolated(() -> drawBoardAspects(note, placements, conflicts, scale, originX, originY));
    }

    private void drawBoardAspects(ResearchNoteData note, Map<String, Aspect> placements, Set<String> conflicts,
        int scale, int originX, int originY) {
        for (Map.Entry<String, HexUtils.Hex> entry : note.hexes.entrySet()) {
            String key = entry.getKey();
            ResearchManager.HexEntry current = note.hexEntries.get(key);
            Aspect plannedAspect = placements.get(key);
            Aspect aspect = plannedAspect != null ? plannedAspect : current == null ? null : current.aspect;
            if (aspect == null) continue;
            HexUtils.Pixel pixel = entry.getValue()
                .toPixel(scale);
            int x = originX + (int) Math.round(pixel.x);
            int y = originY + (int) Math.round(pixel.y);
            UtilsFX.drawTag(x - 8, y - 8, aspect, 0.0F, 0, zLevel, 771, conflicts.contains(key) ? 0.55F : 0.9F);
        }
    }

    protected final void drawResearchBoardTooltip(int mouseX, int mouseY) {
        BoardView view = boardView;
        if (view == null) return;
        HexUtils.Hex hovered = new HexUtils.Pixel(mouseX - view.originX, mouseY - view.originY).toHex(view.scale);
        String key = hovered.toString();
        ResearchManager.HexEntry current = view.note.hexEntries.get(key);
        if (current == null) return;
        Aspect planned = view.planned.get(key);
        Aspect displayed = planned != null ? planned : current.aspect;
        List<String> tooltip = new ArrayList<>();
        tooltip.add(key);
        tooltip.add(StatCollector.translateToLocal("tcautores.preview_current") + ": " + aspectName(current.aspect));
        tooltip.add(StatCollector.translateToLocal("tcautores.preview_planned") + ": " + aspectName(planned));
        if (view.conflicts.contains(key)) tooltip.add(StatCollector.translateToLocal("tcautores.preview_conflict"));
        if (displayed != null) tooltip.add(displayed.getTag());
        drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
    }

    protected final String cacheText(boolean hit) {
        return StatCollector.translateToLocal(hit ? "tcautores.cache_hit" : "tcautores.cache_miss");
    }

    private static Bounds bounds(ResearchNoteData note) {
        Bounds bounds = new Bounds();
        for (HexUtils.Hex hex : note.hexes.values()) {
            HexUtils.Pixel pixel = hex.toPixel(1);
            bounds.include(pixel.x, pixel.y);
        }
        return bounds;
    }

    private static int boardScale(Bounds bounds, int availableWidth, int availableHeight) {
        double spanX = Math.max(1.0D, bounds.maxX - bounds.minX + 3.0D);
        double spanY = Math.max(1.0D, bounds.maxY - bounds.minY + 3.0D);
        int fit = (int) Math
            .floor(Math.min(Math.max(30, availableWidth) / spanX, Math.max(30, availableHeight) / spanY));
        return Math.max(3, Math.min(9, fit));
    }

    private static Set<String> conflicts(ResearchNoteData note, Map<String, Aspect> planned) {
        Set<String> conflicts = new HashSet<>();
        Map<String, Aspect> board = new HashMap<>();
        for (Map.Entry<String, ResearchManager.HexEntry> entry : note.hexEntries.entrySet()) {
            ResearchManager.HexEntry hex = entry.getValue();
            if (hex.type >= 1 && hex.aspect != null) board.put(entry.getKey(), hex.aspect);
        }
        for (Map.Entry<String, Aspect> placement : planned.entrySet()) {
            ResearchManager.HexEntry current = note.hexEntries.get(placement.getKey());
            if (current == null || current.aspect != null && current.aspect != placement.getValue()) {
                conflicts.add(placement.getKey());
            } else {
                board.put(placement.getKey(), placement.getValue());
            }
        }
        for (String key : new ArrayList<>(board.keySet())) {
            HexUtils.Hex cell = parse(key);
            for (int direction = 0; direction < 6; direction++) {
                String neighbor = cell.getNeighbour(direction)
                    .toString();
                if (board.containsKey(neighbor) && !compatible(board.get(key), board.get(neighbor))) {
                    conflicts.add(key);
                    conflicts.add(neighbor);
                }
            }
        }
        if (board.isEmpty()) return conflicts;

        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        String start = board.keySet()
            .iterator()
            .next();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            String key = queue.removeFirst();
            HexUtils.Hex cell = parse(key);
            for (int direction = 0; direction < 6; direction++) {
                String neighbor = cell.getNeighbour(direction)
                    .toString();
                if (board.containsKey(neighbor) && !visited.contains(neighbor)
                    && compatible(board.get(key), board.get(neighbor))) {
                    visited.add(neighbor);
                    queue.addLast(neighbor);
                }
            }
        }
        for (String key : board.keySet()) if (!visited.contains(key)) conflicts.add(key);
        return conflicts;
    }

    private static HexUtils.Hex parse(String key) {
        String[] values = key.split(":", 2);
        return new HexUtils.Hex(Integer.parseInt(values[0]), Integer.parseInt(values[1]));
    }

    private static boolean compatible(Aspect left, Aspect right) {
        if (left == null || right == null) return false;
        Aspect[] components = left.getComponents();
        if (components != null && (components[0] == right || components[1] == right)) return true;
        components = right.getComponents();
        return components != null && (components[0] == left || components[1] == left);
    }

    private static String aspectName(Aspect aspect) {
        return aspect == null ? StatCollector.translateToLocal("tcautores.preview_empty")
            : AspectLocalization.name(aspect);
    }

    private void drawHexPolygon(int centerX, int centerY, float radius, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_POLYGON);
        tessellator.setColorRGBA((color >> 16) & 255, (color >> 8) & 255, color & 255, (color >>> 24) & 255);
        for (int index = 0; index < 6; index++) {
            double angle = Math.PI / 6.0D + index * Math.PI / 3.0D;
            tessellator.addVertex(centerX + Math.cos(angle) * radius, centerY + Math.sin(angle) * radius, zLevel);
        }
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static final class Bounds {

        double minX;
        double maxX;
        double minY;
        double maxY;
        boolean empty = true;

        void include(double x, double y) {
            if (empty) {
                minX = maxX = x;
                minY = maxY = y;
                empty = false;
                return;
            }
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
    }

    private static final class BoardView {

        final ResearchNoteData note;
        final Map<String, Aspect> planned;
        final Set<String> conflicts;
        final int scale;
        final int originX;
        final int originY;

        BoardView(ResearchNoteData note, Map<String, Aspect> planned, Set<String> conflicts, int scale, int originX,
            int originY) {
            this.note = note;
            this.planned = planned;
            this.conflicts = conflicts;
            this.scale = scale;
            this.originX = originX;
            this.originY = originY;
        }
    }
}

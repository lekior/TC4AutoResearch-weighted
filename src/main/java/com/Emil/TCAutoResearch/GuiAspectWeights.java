package com.Emil.TCAutoResearch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.client.lib.UtilsFX;
import thaumcraft.common.Thaumcraft;

public class GuiAspectWeights extends GuiWeightScreen {

    private static final int LIST_WIDTH = 200;
    private static final int LIST_ROW_HEIGHT = 18;
    private static final int SEARCH_Y = 36;
    private static final int LIST_Y = 82;

    private enum SortMode {
        NAME,
        COST,
        INVENTORY
    }

    private final GuiScreen parent;
    private final List<Aspect> aspects = new ArrayList<>();
    private final List<Aspect> filteredAspects = new ArrayList<>();
    private Aspect selected;
    private GuiTextField costField;
    private GuiTextField searchField;
    private AspectList inventory = new AspectList();
    private SortMode sortMode = SortMode.NAME;
    private boolean onlyDisabled;
    private boolean onlyMissing;
    private long nextInventoryRefresh;
    private int inventoryFingerprint;
    private int scroll;

    public GuiAspectWeights(GuiScreen parent) {
        this.parent = parent;
        for (Object value : Aspect.aspects.values()) aspects.add((Aspect) value);
        aspects.sort(Comparator.comparing(Aspect::getTag));
        filteredAspects.addAll(aspects);
        if (!aspects.isEmpty()) selected = aspects.get(0);
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int center = width / 2;
        buttonList.clear();
        buttonList.add(weightButton(0, center + 55, height - 22, 100, 20, StatCollector.translateToLocal("gui.done")));
        buttonList.add(weightButton(1, center + 55, 36, 100, 18, modeText()));
        buttonList.add(
            weightButton(2, center + 55, 56, 49, 18, StatCollector.translateToLocal("tcautores.preset_wiki_short")));
        buttonList.add(
            weightButton(
                3,
                center + 106,
                56,
                49,
                18,
                StatCollector.translateToLocal("tcautores.preset_inventory_short")));
        buttonList.add(weightButton(4, center + 55, 116, 100, 18, enabledText()));
        buttonList.add(weightButton(5, center + 55, 136, 48, 18, "-"));
        buttonList.add(weightButton(6, center + 107, 136, 48, 18, "+"));
        buttonList.add(weightButton(7, center + 55, 156, 100, 18, previewText()));
        buttonList.add(weightButton(8, center + 55, 176, 100, 18, inventoryText()));
        buttonList
            .add(weightButton(9, center + 55, 196, 100, 18, StatCollector.translateToLocal("tcautores.reset_one")));
        costField = new GuiTextField(fontRendererObj, center + 55, 94, 100, 18);
        costField.setMaxStringLength(4);
        searchField = new GuiTextField(fontRendererObj, listX(), SEARCH_Y, LIST_WIDTH, 18);
        searchField.setMaxStringLength(64);
        buttonList.add(weightButton(10, listX(), 60, 88, 20, sortText()));
        buttonList.add(weightButton(11, listX() + 90, 60, 54, 20, disabledFilterText()));
        buttonList.add(weightButton(12, listX() + 146, 60, 54, 20, missingFilterText()));
        buttonList.add(
            weightButton(
                13,
                listX(),
                height - 22,
                100,
                20,
                StatCollector.translateToLocal("tcautores.profile_manage")));
        refreshInventory(true);
        updateFilter();
        syncField();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        commitField();
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
        } else if (button.id == 1) {
            Config.setSolveMode(
                Config.solveMode() == Config.SolveMode.WEIGHTED ? Config.SolveMode.NORMAL : Config.SolveMode.WEIGHTED);
            button.displayString = modeText();
        } else if (button.id == 2) {
            Config.applyWikiDefaults();
            syncField();
            updateFilter();
        } else if (button.id == 3) {
            refreshInventory(true);
            Config.applyInventoryWeights(inventory);
            syncField();
            updateFilter();
        } else if (button.id == 4 && selected != null) {
            Config.setAspectDisabled(selected.getTag(), !Config.isAspectDisabled(selected.getTag()));
            button.displayString = enabledText();
            updateFilter();
        } else if (button.id == 5) {
            adjustCost(isShiftKeyDown() ? -16 : -1);
        } else if (button.id == 6) {
            adjustCost(isShiftKeyDown() ? 16 : 1);
        } else if (button.id == 7) {
            Config.setPreviewResult(!Config.previewResult());
            button.displayString = previewText();
        } else if (button.id == 8) {
            Config.setInventoryAware(!Config.inventoryAware());
            button.displayString = inventoryText();
        } else if (button.id == 9 && selected != null) {
            Config.setAspectCost(selected.getTag(), AspectWeights.wikiCost(selected.getTag()));
            Config.setAspectDisabled(selected.getTag(), false);
            syncField();
            updateEnabledButton();
            updateFilter();
        } else if (button.id == 10) {
            sortMode = SortMode.values()[(sortMode.ordinal() + 1) % SortMode.values().length];
            button.displayString = sortText();
            updateFilter();
        } else if (button.id == 11) {
            onlyDisabled = !onlyDisabled;
            button.displayString = disabledFilterText();
            updateFilter();
        } else if (button.id == 12) {
            onlyMissing = !onlyMissing;
            button.displayString = missingFilterText();
            updateFilter();
        } else if (button.id == 13) {
            mc.displayGuiScreen(new GuiWeightProfiles(this));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (searchField.textboxKeyTyped(typedChar, keyCode)) {
            updateFilter();
            return;
        }
        if (costField.textboxKeyTyped(typedChar, keyCode)) return;
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            commitField();
            if (sortMode == SortMode.COST) updateFilter();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        costField.mouseClicked(mouseX, mouseY, mouseButton);
        searchField.mouseClicked(mouseX, mouseY, mouseButton);
        int listX = listX();
        int rows = visibleRows();
        if (mouseX >= listX && mouseX < listX + LIST_WIDTH
            && mouseY >= LIST_Y
            && mouseY < LIST_Y + rows * LIST_ROW_HEIGHT) {
            int index = scroll + (mouseY - LIST_Y) / LIST_ROW_HEIGHT;
            if (index >= 0 && index < filteredAspects.size()) {
                commitField();
                selected = filteredAspects.get(index);
                syncField();
                updateEnabledButton();
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll += wheel < 0 ? 1 : -1;
            clampScroll();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawWeightBackground();
        int x = listX();
        int rows = visibleRows();
        drawWeightPanel(x - 4, SEARCH_Y - 6, x + LIST_WIDTH + 4, LIST_Y + rows * LIST_ROW_HEIGHT + 3);
        drawWeightPanel(width / 2 + 50, 30, width / 2 + 160, height - 26);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("tcautores.weights"),
            width / 2,
            14,
            0xFFFFFF);
        searchField.drawTextBox();
        if (searchField.getText()
            .isEmpty() && !searchField.isFocused()) {
            fontRendererObj
                .drawString(StatCollector.translateToLocal("tcautores.search"), listX() + 4, SEARCH_Y + 5, 0x777777);
        }
        for (int row = 0; row < rows && scroll + row < filteredAspects.size(); row++) {
            Aspect aspect = filteredAspects.get(scroll + row);
            int rowY = LIST_Y + row * LIST_ROW_HEIGHT;
            boolean active = aspect == selected;
            boolean disabled = Config.isAspectDisabled(aspect.getTag());
            int aspectColor = active ? 0xFF7B8790 : 0xFF3A3A3A;
            int rowColor = active ? 0xCC52606A : 0xAA242424;
            GuiThemeRenderer.rect(x, rowY, x + LIST_WIDTH, rowY + LIST_ROW_HEIGHT - 1, rowColor);
            GuiThemeRenderer.rect(x, rowY, x + (active ? 3 : 2), rowY + LIST_ROW_HEIGHT - 1, aspectColor);
            int color = disabled ? 0xE58A8A : 0xF2F2F2;
            String name = fontRendererObj.trimStringToWidth(AspectLocalization.name(aspect), 132);
            fontRendererObj.drawString(name, x + 23, rowY + 5, color);
            String value = disabled ? "OFF" : String.valueOf(Config.getAspectCost(aspect.getTag()));
            fontRendererObj
                .drawString(value, x + LIST_WIDTH - 6 - fontRendererObj.getStringWidth(value), rowY + 5, color);
        }
        if (filteredAspects.isEmpty()) {
            drawCenteredString(
                fontRendererObj,
                StatCollector.translateToLocal("tcautores.no_search_results"),
                x + LIST_WIDTH / 2,
                LIST_Y + 8,
                0xAAAAAA);
        }
        if (selected != null) {
            String selectedName = AspectLocalization.name(selected) + " (" + selected.getTag() + ")";
            drawCenteredString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(selectedName, 78),
                width / 2 + 116,
                82,
                0xD7E7DF);
        }
        drawAspectTags(x, rows);
        costField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
        Aspect hovered = hoveredAspect(mouseX, mouseY);
        if (hovered != null) drawAspectTooltip(hovered, mouseX, mouseY);
    }

    private void drawAspectTags(int x, int rows) {
        GuiThemeRenderer.isolated(() -> {
            for (int row = 0; row < rows && scroll + row < filteredAspects.size(); row++) {
                Aspect aspect = filteredAspects.get(scroll + row);
                drawColoredTag(
                    x + 3,
                    LIST_Y + row * LIST_ROW_HEIGHT + 1,
                    aspect,
                    Config.isAspectDisabled(aspect.getTag()) ? 0.45F : 1.0F);
            }
            if (selected != null) {
                drawColoredTag(width / 2 + 57, 78, selected, Config.isAspectDisabled(selected.getTag()) ? 0.45F : 1.0F);
            }
        });
    }

    private void drawColoredTag(int x, int y, Aspect aspect, float alpha) {
        UtilsFX.drawTag(x, y, aspect, 0.0F, 0, zLevel, 771, alpha);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        costField.updateCursorCounter();
        searchField.updateCursorCounter();
        refreshInventory(false);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        commitField();
        Config.saveSolverConfiguration();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private int visibleRows() {
        return Math.max(4, (height - LIST_Y - 40) / LIST_ROW_HEIGHT);
    }

    private int listX() {
        return width / 2 - 155;
    }

    private void updateFilter() {
        String query = searchField.getText()
            .trim()
            .toLowerCase(Locale.ROOT);
        filteredAspects.clear();
        for (Aspect aspect : aspects) {
            if ((!onlyDisabled || Config.isAspectDisabled(aspect.getTag()))
                && (!onlyMissing || inventory.getAmount(aspect) <= 0)
                && (query.isEmpty() || aspect.getTag()
                    .toLowerCase(Locale.ROOT)
                    .contains(query)
                    || AspectLocalization.name(aspect)
                        .toLowerCase(Locale.ROOT)
                        .contains(query))) {
                filteredAspects.add(aspect);
            }
        }
        filteredAspects.sort(aspectComparator());
        scroll = 0;
        if (filteredAspects.isEmpty()) {
            commitField();
            selected = null;
            costField.setText("");
            updateEnabledButton();
        } else if (!filteredAspects.contains(selected)) {
            commitField();
            selected = filteredAspects.get(0);
            syncField();
            updateEnabledButton();
        }
        clampScroll();
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(Math.max(0, filteredAspects.size() - visibleRows()), scroll));
    }

    private Comparator<Aspect> aspectComparator() {
        Comparator<Aspect> byName = Comparator.comparing(AspectLocalization::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Aspect::getTag);
        if (sortMode == SortMode.COST) {
            return Comparator.comparingInt((Aspect aspect) -> Config.getAspectCost(aspect.getTag()))
                .thenComparing(byName);
        }
        if (sortMode == SortMode.INVENTORY) {
            return Comparator.comparingInt((Aspect aspect) -> inventory.getAmount(aspect))
                .reversed()
                .thenComparing(byName);
        }
        return byName;
    }

    private void refreshInventory(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now < nextInventoryRefresh) return;
        nextInventoryRefresh = now + 500;
        if (mc == null || mc.thePlayer == null) return;
        AspectList current = parent instanceof GuiResearchTableHelperInterface
            ? ((GuiResearchTableHelperInterface) parent).availableAspects()
            : currentAspectPool();
        int fingerprint = 1;
        for (Aspect aspect : aspects) fingerprint = 31 * fingerprint + current.getAmount(aspect);
        inventory = current;
        if (fingerprint != inventoryFingerprint) {
            inventoryFingerprint = fingerprint;
            if (searchField != null && (sortMode == SortMode.INVENTORY || onlyMissing)) updateFilter();
        }
    }

    private AspectList currentAspectPool() {
        AspectList result = new AspectList();
        String username = mc.thePlayer.getCommandSenderName();
        for (Aspect aspect : aspects) {
            int amount = Thaumcraft.proxy.getPlayerKnowledge()
                .getAspectPoolFor(username, aspect);
            if (amount > 0) result.add(aspect, amount);
        }
        return result;
    }

    private Aspect hoveredAspect(int mouseX, int mouseY) {
        if (mouseX < listX() || mouseX >= listX() + LIST_WIDTH || mouseY < LIST_Y) return null;
        int row = (mouseY - LIST_Y) / LIST_ROW_HEIGHT;
        if (row < 0 || row >= visibleRows()) return null;
        int index = scroll + row;
        return index < filteredAspects.size() ? filteredAspects.get(index) : null;
    }

    private void drawAspectTooltip(Aspect aspect, int mouseX, int mouseY) {
        List<String> tooltip = new ArrayList<>();
        tooltip.add(
            EnumChatFormatting.AQUA + AspectLocalization
                .name(aspect) + EnumChatFormatting.GRAY + " (" + aspect.getTag() + ")");
        Aspect[] components = aspect.getComponents();
        if (components == null) {
            tooltip.add(StatCollector.translateToLocal("tcautores.tooltip_primal"));
        } else {
            tooltip.add(
                String.format(
                    StatCollector.translateToLocal("tcautores.tooltip_components"),
                    AspectLocalization.name(components[0]),
                    AspectLocalization.name(components[1])));
        }
        tooltip.add(
            String.format(StatCollector.translateToLocal("tcautores.tooltip_inventory"), inventory.getAmount(aspect)));
        tooltip.add(
            String.format(
                StatCollector.translateToLocal("tcautores.tooltip_weight"),
                Config.getAspectCost(aspect.getTag())));
        tooltip.add(
            StatCollector.translateToLocal(
                Config.isAspectDisabled(aspect.getTag()) ? "tcautores.tooltip_disabled" : "tcautores.tooltip_enabled"));
        drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
    }

    private void adjustCost(int amount) {
        if (selected == null) return;
        Config.setAspectCost(selected.getTag(), Config.getAspectCost(selected.getTag()) + amount);
        syncField();
        if (sortMode == SortMode.COST) updateFilter();
    }

    private void commitField() {
        if (selected == null || costField == null) return;
        try {
            Config.setAspectCost(selected.getTag(), Integer.parseInt(costField.getText()));
        } catch (NumberFormatException ignored) {
            syncField();
        }
    }

    private void syncField() {
        if (selected != null && costField != null) {
            costField.setText(String.valueOf(Config.getAspectCost(selected.getTag())));
        }
    }

    private void updateEnabledButton() {
        for (Object value : buttonList) {
            GuiButton button = (GuiButton) value;
            if (button.id == 4) button.displayString = enabledText();
            if (button.id == 4 || button.id == 5 || button.id == 6 || button.id == 9) {
                button.enabled = selected != null;
            }
        }
    }

    private String modeText() {
        return StatCollector.translateToLocal(
            Config.solveMode() == Config.SolveMode.WEIGHTED ? "tcautores.mode_weighted" : "tcautores.mode_normal");
    }

    private String enabledText() {
        return StatCollector.translateToLocal(
            selected != null && Config.isAspectDisabled(selected.getTag()) ? "tcautores.aspect_disabled"
                : "tcautores.aspect_enabled");
    }

    private String previewText() {
        return StatCollector
            .translateToLocal(Config.previewResult() ? "tcautores.preview_on" : "tcautores.preview_off");
    }

    private String inventoryText() {
        return StatCollector
            .translateToLocal(Config.inventoryAware() ? "tcautores.inventory_on" : "tcautores.inventory_off");
    }

    private String sortText() {
        String key = sortMode == SortMode.NAME ? "tcautores.sort_name"
            : sortMode == SortMode.COST ? "tcautores.sort_weight" : "tcautores.sort_inventory";
        return StatCollector.translateToLocal(key);
    }

    private String disabledFilterText() {
        return StatCollector
            .translateToLocal(onlyDisabled ? "tcautores.filter_disabled_on" : "tcautores.filter_disabled_off");
    }

    private String missingFilterText() {
        return StatCollector
            .translateToLocal(onlyMissing ? "tcautores.filter_missing_on" : "tcautores.filter_missing_off");
    }
}

package com.Emil.TCAutoResearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class GuiWeightProfiles extends GuiWeightScreen {

    private static final int LIST_WIDTH = 200;
    private static final int LIST_Y = 38;
    private static final int ROW_HEIGHT = 20;

    private final GuiScreen parent;
    private final List<String> profiles = new ArrayList<>();
    private GuiTextField nameField;
    private String selected;
    private String status = "";
    private int statusColor = 0xAAAAAA;
    private int scroll;

    public GuiWeightProfiles(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int center = width / 2;
        buttonList.clear();
        buttonList.add(weightButton(0, center + 55, height - 22, 100, 20, StatCollector.translateToLocal("gui.done")));
        buttonList
            .add(weightButton(1, center + 55, 58, 100, 18, StatCollector.translateToLocal("tcautores.profile_save")));
        buttonList
            .add(weightButton(2, center + 55, 78, 100, 18, StatCollector.translateToLocal("tcautores.profile_load")));
        buttonList
            .add(weightButton(3, center + 55, 98, 100, 18, StatCollector.translateToLocal("tcautores.profile_delete")));
        buttonList.add(
            weightButton(4, center + 55, 126, 100, 18, StatCollector.translateToLocal("tcautores.profile_export")));
        buttonList.add(
            weightButton(5, center + 55, 146, 100, 18, StatCollector.translateToLocal("tcautores.profile_import")));
        nameField = new GuiTextField(fontRendererObj, center + 55, 36, 100, 18);
        nameField.setMaxStringLength(48);
        if (selected != null) nameField.setText(selected);
        reloadProfiles();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            Config.saveSolverConfiguration();
            mc.displayGuiScreen(parent);
            return;
        }
        String name = nameField.getText()
            .trim();
        try {
            if (button.id == 1) {
                selected = WeightProfileStore.saveCurrent(name);
                setStatus("tcautores.profile_saved", true);
                reloadProfiles();
            } else if (button.id == 2) {
                if (!WeightProfileStore.apply(name)) {
                    setStatus("tcautores.profile_not_found", false);
                } else {
                    selected = name;
                    setStatus("tcautores.profile_loaded", true);
                }
            } else if (button.id == 3) {
                if (!WeightProfileStore.delete(name)) {
                    setStatus("tcautores.profile_not_found", false);
                } else {
                    selected = null;
                    nameField.setText("");
                    setStatus("tcautores.profile_deleted", true);
                    reloadProfiles();
                }
            } else if (button.id == 4) {
                String json = WeightProfileStore.exportJson(name);
                if (json == null) setStatus("tcautores.profile_not_found", false);
                else {
                    setClipboardString(json);
                    setStatus("tcautores.profile_exported", true);
                }
            } else if (button.id == 5) {
                selected = WeightProfileStore.importJson(getClipboardString(), name);
                nameField.setText(selected);
                setStatus("tcautores.profile_imported", true);
                reloadProfiles();
            }
        } catch (IOException | RuntimeException exception) {
            TCAutoResearch.LOG.warn("Weight profile operation failed", exception);
            setStatus("tcautores.profile_error", false);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (nameField.textboxKeyTyped(typedChar, keyCode)) return;
        if (keyCode == Keyboard.KEY_ESCAPE) {
            Config.saveSolverConfiguration();
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        nameField.mouseClicked(mouseX, mouseY, mouseButton);
        int listX = listX();
        int index = scroll + (mouseY - LIST_Y) / ROW_HEIGHT;
        if (mouseX >= listX && mouseX < listX + LIST_WIDTH
            && mouseY >= LIST_Y
            && index >= scroll
            && index < Math.min(profiles.size(), scroll + visibleRows())) {
            selected = profiles.get(index);
            nameField.setText(selected);
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
        int listX = listX();
        int rows = visibleRows();
        drawWeightPanel(listX - 4, LIST_Y - 4, listX + LIST_WIDTH + 4, LIST_Y + rows * ROW_HEIGHT + 3);
        drawWeightPanel(width / 2 + 50, 32, width / 2 + 160, height - 26);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("tcautores.profile_title"),
            width / 2,
            12,
            0xFFFFFF);
        nameField.drawTextBox();
        for (int row = 0; row < rows && scroll + row < profiles.size(); row++) {
            String profile = profiles.get(scroll + row);
            int rowY = LIST_Y + row * ROW_HEIGHT;
            boolean active = profile.equals(selected);
            GuiThemeRenderer
                .rect(listX, rowY, listX + LIST_WIDTH, rowY + ROW_HEIGHT - 1, active ? 0xCC52606A : 0xAA242424);
            GuiThemeRenderer
                .rect(listX, rowY, listX + (active ? 3 : 2), rowY + ROW_HEIGHT - 1, active ? 0xFF7B8790 : 0xFF3A3A3A);
            fontRendererObj
                .drawString(fontRendererObj.trimStringToWidth(profile, LIST_WIDTH - 14), listX + 7, rowY + 6, 0xF0F4F2);
        }
        if (profiles.isEmpty()) {
            drawCenteredString(
                fontRendererObj,
                StatCollector.translateToLocal("tcautores.profile_empty"),
                listX + LIST_WIDTH / 2,
                LIST_Y + 8,
                0xAAAAAA);
        }
        if (!status.isEmpty()) {
            drawCenteredString(fontRendererObj, status, width / 2 + 105, 172, statusColor);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        Config.saveSolverConfiguration();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void reloadProfiles() {
        profiles.clear();
        profiles.addAll(WeightProfileStore.names());
        clampScroll();
    }

    private int listX() {
        return width / 2 - 155;
    }

    private int visibleRows() {
        return Math.max(4, (height - LIST_Y - 36) / ROW_HEIGHT);
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(Math.max(0, profiles.size() - visibleRows()), scroll));
    }

    private void setStatus(String key, boolean success) {
        status = StatCollector.translateToLocal(key);
        statusColor = success ? 0x77DD77 : 0xFF7777;
    }
}

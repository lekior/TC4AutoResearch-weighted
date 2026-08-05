package com.Emil.TCAutoResearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.client.config.GuiButtonExt;
import thaumcraft.api.research.ResearchCategories;
import thaumcraft.api.research.ResearchCategoryList;
import thaumcraft.api.research.ResearchItem;
import thaumcraft.client.gui.GuiResearchBrowser;
import thaumcraft.client.lib.UtilsFX;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.utils.InventoryUtils;

public final class GuiResearchQueue extends GuiScreen {

    private static final int ROW_HEIGHT = 24;
    private static final int CATEGORY_SIZE = 22;
    private static final int CATEGORY_Y = 49;

    private final GuiScreen parent;
    private String category;
    private final boolean researchTableMode;
    private final List<String> categories = new ArrayList<>();
    private final List<ResearchCatalog.Entry> entries = new ArrayList<>();
    private final List<ResearchCatalog.Entry> filtered = new ArrayList<>();
    private ResearchCatalog.Scope scope = ResearchCatalog.Scope.CURRENT_CATEGORY;
    private GuiTextField searchField;
    private GuiButton scopeButton;
    private GuiButton locateButton;
    private GuiButton generateButton;
    private GuiButton generateVisibleButton;
    private GuiButton unlockResearchableButton;
    private GuiButton previousCategoryButton;
    private GuiButton nextCategoryButton;
    private String selectedKey;
    private int scroll;
    private int categoryScroll;
    private long generationRevision;

    public GuiResearchQueue(GuiScreen parent, String category) {
        this.parent = parent;
        this.category = category == null ? ResearchCatalog.currentCategory() : category;
        this.researchTableMode = parent instanceof GuiResearchTableHelperInterface;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        refreshCategories();
        int center = width / 2;
        searchField = new GuiTextField(
            fontRendererObj,
            center - Math.min(150, width / 2 - 10),
            27,
            Math.min(300, width - 20),
            18);
        searchField.setMaxStringLength(64);
        searchField.setFocused(true);
        int bottom = height - 22;
        buttonList.add(new GuiButtonExt(0, center - 125, bottom, 70, 20, StatCollector.translateToLocal("gui.done")));
        scopeButton = new GuiButtonExt(1, center - 50, bottom, 100, 20, scopeText());
        locateButton = new GuiButtonExt(
            2,
            center + 55,
            bottom,
            70,
            20,
            StatCollector.translateToLocal("tcautores.locate"));
        generateButton = new GuiButtonExt(
            3,
            center - 130,
            height - 44,
            125,
            20,
            StatCollector
                .translateToLocal(researchTableMode ? "tcautores.target_selected" : "tcautores.generate_selected"));
        generateVisibleButton = new GuiButtonExt(
            4,
            center + 5,
            height - 44,
            125,
            20,
            StatCollector.translateToLocal(
                researchTableMode ? "tcautores.target_with_prerequisites" : "tcautores.generate_visible"));
        buttonList.add(scopeButton);
        buttonList.add(locateButton);
        buttonList.add(generateButton);
        buttonList.add(generateVisibleButton);
        unlockResearchableButton = new GuiButtonExt(
            5,
            center - 130,
            height - 66,
            260,
            20,
            StatCollector.translateToLocal("tcautores.unlock_researchable"));
        unlockResearchableButton.visible = researchTableMode;
        buttonList.add(unlockResearchableButton);
        previousCategoryButton = new GuiButtonExt(6, listX(), CATEGORY_Y, 18, 20, "<");
        nextCategoryButton = new GuiButtonExt(7, listX() + listWidth() - 18, CATEGORY_Y, 18, 20, ">");
        buttonList.add(previousCategoryButton);
        buttonList.add(nextCategoryButton);
        updateCategoryButtons();
        refresh();
    }

    @Override
    public void updateScreen() {
        searchField.updateCursorCounter();
        if (generationRevision != ResearchNoteGenerationController.revision()) {
            generationRevision = ResearchNoteGenerationController.revision();
            refresh();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
        } else if (button.id == 1) {
            scope = scope == ResearchCatalog.Scope.CURRENT_CATEGORY ? ResearchCatalog.Scope.ALL_CATEGORIES
                : ResearchCatalog.Scope.CURRENT_CATEGORY;
            scopeButton.displayString = scopeText();
            refresh();
        } else if (button.id == 2) {
            ResearchCatalog.Entry selected = selected();
            if (!researchTableMode && selected != null) {
                ResearchBrowserNavigation.request(selected.research);
                mc.displayGuiScreen(new GuiResearchBrowser());
            }
        } else if (button.id == 3) {
            ResearchCatalog.Entry selected = selected();
            if (researchTableMode && selected != null) {
                TargetResearchController
                    .start((GuiResearchTableHelperInterface) parent, mc.thePlayer, mc, selected.research, false);
                mc.displayGuiScreen(parent);
            } else if (selected != null && selected.status == ResearchCatalog.Status.READY) {
                ResearchNoteGenerationController
                    .start(mc.thePlayer, mc, java.util.Collections.singletonList(selected.research), null, true);
            }
        } else if (button.id == 4) {
            ResearchCatalog.Entry selected = selected();
            if (researchTableMode && selected != null) {
                mc.displayGuiScreen(
                    new GuiPrerequisiteConfirmation(
                        this,
                        parent,
                        (GuiResearchTableHelperInterface) parent,
                        selected.research));
            } else if (ResearchNoteGenerationController.isRunning()) {
                ResearchNoteGenerationController.cancel();
            } else {
                List<ResearchItem> visible = new ArrayList<>();
                for (ResearchCatalog.Entry entry : filtered) {
                    if (entry.status == ResearchCatalog.Status.READY) visible.add(entry.research);
                }
                ResearchNoteGenerationController.start(mc.thePlayer, mc, visible, null, true);
            }
        } else if (button.id == 5 && researchTableMode) {
            TargetResearchController
                .startAllResearchable((GuiResearchTableHelperInterface) parent, mc.thePlayer, mc, scope, category);
            mc.displayGuiScreen(parent);
        } else if (button.id == 6) {
            categoryScroll = Math.max(0, categoryScroll - visibleCategoryCount());
            updateCategoryButtons();
        } else if (button.id == 7) {
            categoryScroll = Math.min(maxCategoryScroll(), categoryScroll + visibleCategoryCount());
            updateCategoryButtons();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (searchField.textboxKeyTyped(typedChar, keyCode)) {
            applyFilter();
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
        searchField.mouseClicked(mouseX, mouseY, mouseButton);
        String clickedCategory = categoryAt(mouseX, mouseY);
        if (clickedCategory != null) {
            category = clickedCategory;
            ResearchCatalog.setCurrentCategory(category);
            scope = ResearchCatalog.Scope.CURRENT_CATEGORY;
            scopeButton.displayString = scopeText();
            refresh();
            return;
        }
        int listX = listX();
        int listY = listY();
        int listBottom = researchTableMode ? height - 69 : height - 49;
        if (mouseX < listX || mouseX >= listX + listWidth() || mouseY < listY || mouseY >= listBottom) return;
        int index = scroll + (mouseY - listY) / ROW_HEIGHT;
        if (index < 0 || index >= filtered.size()) return;
        ResearchCatalog.Entry entry = filtered.get(index);
        boolean doubleClick = entry.research.key.equals(selectedKey) && mouseButton == 0 && isShiftKeyDown();
        selectedKey = entry.research.key;
        updateButtons();
        if (doubleClick && !researchTableMode) {
            ResearchBrowserNavigation.request(entry.research);
            mc.displayGuiScreen(new GuiResearchBrowser());
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int mouseX = Mouse.getEventX() * width / mc.displayWidth;
            int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            if (mouseY >= CATEGORY_Y && mouseY < CATEGORY_Y + 20) {
                categoryScroll += wheel < 0 ? visibleCategoryCount() : -visibleCategoryCount();
                categoryScroll = Math.max(0, Math.min(categoryScroll, maxCategoryScroll()));
                updateCategoryButtons();
            } else {
                scroll += wheel < 0 ? 1 : -1;
                clampScroll();
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("tcautores.queue_title"),
            width / 2,
            9,
            0xFFFFFF);
        searchField.drawTextBox();
        drawCategories(mouseX, mouseY);
        int listX = listX();
        int listY = listY();
        int rows = visibleRows();
        drawRect(listX - 2, listY - 2, listX + listWidth() + 2, listY + rows * ROW_HEIGHT + 2, 0xB0101010);
        RenderItem renderer = new RenderItem();
        for (int row = 0; row < rows && scroll + row < filtered.size(); row++) {
            ResearchCatalog.Entry entry = filtered.get(scroll + row);
            int y = listY + row * ROW_HEIGHT;
            boolean selected = entry.research.key.equals(selectedKey);
            boolean hovered = mouseX >= listX && mouseX < listX + listWidth()
                && mouseY >= y
                && mouseY < y + ROW_HEIGHT - 1;
            drawRect(
                listX,
                y,
                listX + listWidth(),
                y + ROW_HEIGHT - 1,
                selected ? 0xCC52606A : hovered ? 0xAA383838 : 0xAA242424);
            renderIcon(renderer, entry.research, listX + 3, y + 3);
            int textX = listX + 24;
            String name = entry.research.getName();
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(name, listWidth() - 135),
                textX,
                y + 4,
                entry.status == ResearchCatalog.Status.READY ? 0xFFFFFF : 0xBBBBBB);
            String categoryName = ResearchCategories.getCategoryName(entry.research.category);
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(categoryName + " / " + entry.research.key, listWidth() - 135),
                textX,
                y + 14,
                0x888888);
            String status = statusText(entry.status);
            fontRendererObj.drawString(
                status,
                listX + listWidth() - fontRendererObj.getStringWidth(status) - 5,
                y + 8,
                entry.status == ResearchCatalog.Status.READY ? 0x80D890 : 0xAAAAAA);
        }
        if (filtered.isEmpty()) {
            drawCenteredString(
                fontRendererObj,
                StatCollector.translateToLocal("tcautores.queue_empty"),
                width / 2,
                listY + 12,
                0xAAAAAA);
        }
        generateVisibleButton.displayString = researchTableMode
            ? StatCollector.translateToLocal("tcautores.target_with_prerequisites")
            : ResearchNoteGenerationController.progressText();
        updateButtons();
        super.drawScreen(mouseX, mouseY, partialTicks);
        String hoveredCategory = categoryAt(mouseX, mouseY);
        if (hoveredCategory != null) drawHoveringText(
            java.util.Collections.singletonList(ResearchCategories.getCategoryName(hoveredCategory)),
            mouseX,
            mouseY,
            fontRendererObj);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void refresh() {
        entries.clear();
        entries.addAll(ResearchCatalog.entries(mc.thePlayer, scope, category));
        applyFilter();
    }

    private void refreshCategories() {
        categories.clear();
        String username = mc.thePlayer == null ? "" : mc.thePlayer.getCommandSenderName();
        for (String key : ResearchCategories.researchCategories.keySet()) {
            if (!"ELDRITCH".equals(key) || ResearchManager.isResearchComplete(username, "ELDRITCHMINOR"))
                categories.add(key);
        }
        if (!categories.contains(category)) category = categories.isEmpty() ? null : categories.get(0);
        categoryScroll = Math.max(0, Math.min(categoryScroll, maxCategoryScroll()));
    }

    private void applyFilter() {
        filtered.clear();
        String query = searchField == null ? ""
            : searchField.getText()
                .trim()
                .toLowerCase(Locale.ROOT);
        for (ResearchCatalog.Entry entry : entries) {
            String haystack = (entry.research.getName() + " "
                + entry.research.key
                + " "
                + ResearchCategories.getCategoryName(entry.research.category)).toLowerCase(Locale.ROOT);
            if (query.isEmpty() || haystack.contains(query)) filtered.add(entry);
        }
        if (selected() == null) selectedKey = filtered.isEmpty() ? null : filtered.get(0).research.key;
        scroll = 0;
        clampScroll();
        updateButtons();
    }

    private void updateButtons() {
        if (locateButton == null) return;
        ResearchCatalog.Entry selected = selected();
        boolean running = researchTableMode ? TargetResearchController.isRunning()
            : ResearchNoteGenerationController.isRunning();
        scopeButton.enabled = !running;
        locateButton.enabled = !researchTableMode && !running && selected != null;
        generateButton.enabled = !running && selected != null
            && (researchTableMode || selected.status == ResearchCatalog.Status.READY);
        generateVisibleButton.enabled = researchTableMode ? !running && selected != null : running || hasVisibleReady();
        unlockResearchableButton.enabled = researchTableMode && !running && hasResearchableWork();
    }

    private boolean hasResearchableWork() {
        for (ResearchCatalog.Entry entry : entries) {
            if (entry.status == ResearchCatalog.Status.READY || entry.status == ResearchCatalog.Status.HAS_NOTE)
                return true;
        }
        return false;
    }

    private boolean hasVisibleReady() {
        for (ResearchCatalog.Entry entry : filtered) if (entry.status == ResearchCatalog.Status.READY) return true;
        return false;
    }

    private ResearchCatalog.Entry selected() {
        if (selectedKey == null) return null;
        for (ResearchCatalog.Entry entry : filtered) if (selectedKey.equals(entry.research.key)) return entry;
        return null;
    }

    private String scopeText() {
        return StatCollector.translateToLocal(
            scope == ResearchCatalog.Scope.CURRENT_CATEGORY ? "tcautores.scope_current" : "tcautores.scope_all");
    }

    private static String statusText(ResearchCatalog.Status status) {
        return StatCollector.translateToLocal(
            "tcautores.queue_status." + status.name()
                .toLowerCase());
    }

    private int listWidth() {
        return Math.min(440, width - 20);
    }

    private int listX() {
        return (width - listWidth()) / 2;
    }

    private int visibleRows() {
        return Math.max(1, (height - (researchTableMode ? 150 : 126)) / ROW_HEIGHT);
    }

    private int listY() {
        return 75;
    }

    private int visibleCategoryCount() {
        int available = listWidth() - (categories.size() * CATEGORY_SIZE > listWidth() ? 40 : 0);
        return Math.max(1, available / CATEGORY_SIZE);
    }

    private int maxCategoryScroll() {
        return Math.max(0, categories.size() - visibleCategoryCount());
    }

    private int categoryStartX() {
        return listX() + (maxCategoryScroll() > 0 ? 20 : 0);
    }

    private void updateCategoryButtons() {
        if (previousCategoryButton == null) return;
        boolean overflow = maxCategoryScroll() > 0;
        previousCategoryButton.visible = overflow;
        nextCategoryButton.visible = overflow;
        previousCategoryButton.enabled = categoryScroll > 0;
        nextCategoryButton.enabled = categoryScroll < maxCategoryScroll();
    }

    private String categoryAt(int mouseX, int mouseY) {
        if (mouseY < CATEGORY_Y || mouseY >= CATEGORY_Y + 20) return null;
        int index = categoryScroll + (mouseX - categoryStartX()) / CATEGORY_SIZE;
        if (mouseX < categoryStartX() || index < categoryScroll
            || index >= categories.size()
            || index >= categoryScroll + visibleCategoryCount()) return null;
        return categories.get(index);
    }

    private void drawCategories(int mouseX, int mouseY) {
        int startX = categoryStartX();
        int count = visibleCategoryCount();
        for (int visible = 0; visible < count && categoryScroll + visible < categories.size(); visible++) {
            String key = categories.get(categoryScroll + visible);
            int x = startX + visible * CATEGORY_SIZE;
            boolean selectedCategory = key.equals(category) && scope == ResearchCatalog.Scope.CURRENT_CATEGORY;
            boolean hovered = key.equals(categoryAt(mouseX, mouseY));
            drawRect(
                x,
                CATEGORY_Y,
                x + 20,
                CATEGORY_Y + 20,
                selectedCategory ? 0xCC426B82 : hovered ? 0xAA3C4650 : 0xAA242424);
            ResearchCategoryList categoryList = ResearchCategories.getResearchList(key);
            if (categoryList == null || categoryList.icon == null) continue;
            GL11.glPushMatrix();
            GL11.glColor4f(1, 1, 1, 1);
            mc.getTextureManager()
                .bindTexture(categoryList.icon);
            UtilsFX.drawTexturedQuadFull(x + 2, CATEGORY_Y + 2, zLevel);
            GL11.glPopMatrix();
        }
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, filtered.size() - visibleRows())));
    }

    private void renderIcon(RenderItem renderer, ResearchItem research, int x, int y) {
        if (research.icon_item != null) {
            RenderHelper.enableGUIStandardItemLighting();
            renderer.renderItemAndEffectIntoGUI(
                fontRendererObj,
                mc.getTextureManager(),
                InventoryUtils.cycleItemStack(research.icon_item),
                x,
                y);
            RenderHelper.disableStandardItemLighting();
        } else if (research.icon_resource != null) {
            GL11.glPushMatrix();
            GL11.glColor4f(1, 1, 1, 1);
            mc.getTextureManager()
                .bindTexture(research.icon_resource);
            UtilsFX.drawTexturedQuadFull(x, y, zLevel);
            GL11.glPopMatrix();
        }
    }
}

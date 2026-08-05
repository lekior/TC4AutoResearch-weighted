package com.Emil.TCAutoResearch;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.client.config.GuiButtonExt;
import thaumcraft.api.research.ResearchItem;
import thaumcraft.client.lib.UtilsFX;
import thaumcraft.common.lib.utils.InventoryUtils;

public final class GuiPrerequisiteConfirmation extends GuiScreen {

    private static final int ROW_HEIGHT = 22;
    private static final int LIST_Y = 48;

    private final GuiScreen searchScreen;
    private final GuiScreen researchTableScreen;
    private final GuiResearchTableHelperInterface helper;
    private final ResearchItem target;
    private ResearchPlan plan;
    private GuiButton executeButton;
    private int scroll;

    public GuiPrerequisiteConfirmation(GuiScreen searchScreen, GuiScreen researchTableScreen,
        GuiResearchTableHelperInterface helper, ResearchItem target) {
        this.searchScreen = searchScreen;
        this.researchTableScreen = researchTableScreen;
        this.helper = helper;
        this.target = target;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        plan = ResearchPlan.create(mc.thePlayer, helper, target);
        int center = width / 2;
        buttonList
            .add(new GuiButtonExt(0, center - 105, height - 24, 100, 20, StatCollector.translateToLocal("gui.back")));
        executeButton = new GuiButtonExt(
            1,
            center + 5,
            height - 24,
            100,
            20,
            StatCollector.translateToLocal("tcautores.plan_execute"));
        executeButton.enabled = plan.canExecute();
        buttonList.add(executeButton);
        clampScroll();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            mc.displayGuiScreen(searchScreen);
        } else if (button.id == 1 && plan.canExecute()) {
            TargetResearchController.start(helper, mc.thePlayer, mc, target, true);
            mc.displayGuiScreen(researchTableScreen);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(searchScreen);
            return;
        }
        if (keyCode == Keyboard.KEY_HOME) scroll = 0;
        else if (keyCode == Keyboard.KEY_END) scroll = plan.entries.size();
        else if (keyCode == Keyboard.KEY_PRIOR) scroll -= visibleRows();
        else if (keyCode == Keyboard.KEY_NEXT) scroll += visibleRows();
        else {
            super.keyTyped(typedChar, keyCode);
            return;
        }
        clampScroll();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll += wheel < 0 ? 3 : -3;
            clampScroll();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("tcautores.plan_title"),
            width / 2,
            8,
            0xFFFFFF);
        String targetText = StatCollector.translateToLocal(
            "tcautores.plan_target") + ": " + target.getName() + " [" + target.key + "]";
        drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(targetText, listWidth()),
            width / 2,
            23,
            0xD7E7DF);
        drawCenteredString(fontRendererObj, summaryText(), width / 2, 35, plan.canExecute() ? 0xA8E0B2 : 0xEF8585);

        int x = listX();
        int rows = visibleRows();
        drawGradientRect(
            x - 2,
            LIST_Y - 2,
            x + listWidth() + 2,
            LIST_Y + rows * ROW_HEIGHT + 2,
            0xE0222222,
            0xE0101010);
        RenderItem renderer = new RenderItem();
        for (int row = 0; row < rows && scroll + row < plan.entries.size(); row++) {
            ResearchPlan.Entry entry = plan.entries.get(scroll + row);
            int y = LIST_Y + row * ROW_HEIGHT;
            boolean hovered = mouseX >= x && mouseX < x + listWidth() && mouseY >= y && mouseY < y + ROW_HEIGHT - 1;
            drawRect(
                x,
                y,
                x + listWidth(),
                y + ROW_HEIGHT - 1,
                entry.target ? 0xCC52606A : hovered ? 0xAA383838 : 0xAA242424);
            renderIcon(renderer, entry.research, x + 3, y + 3);
            String name = (entry.target ? StatCollector.translateToLocal("tcautores.plan_target_short") + " " : "")
                + entry.research.getName();
            if (entry.action == ResearchPlan.Action.HIDDEN) {
                String identified = name + " [" + entry.research.key + "]";
                fontRendererObj.drawString(
                    fontRendererObj.trimStringToWidth(identified, listWidth() - 32),
                    x + 24,
                    y + 3,
                    actionColor(entry.action));
                fontRendererObj.drawString(
                    fontRendererObj.trimStringToWidth(HiddenResearchUnlocks.describe(entry.research), listWidth() - 32),
                    x + 24,
                    y + 12,
                    0xE0C178);
                continue;
            }
            int statusWidth = fontRendererObj.getStringWidth(actionText(entry.action)) + 10;
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(name, listWidth() - statusWidth - 30),
                x + 24,
                y + 3,
                actionColor(entry.action));
            fontRendererObj.drawString(entry.research.key, x + 24, y + 12, 0x888888);
            String status = actionText(entry.action);
            fontRendererObj.drawString(
                status,
                x + listWidth() - fontRendererObj.getStringWidth(status) - 5,
                y + 7,
                actionColor(entry.action));
        }
        if (plan.entries.isEmpty()) {
            drawCenteredString(
                fontRendererObj,
                StatCollector.translateToLocal("tcautores.plan_empty"),
                width / 2,
                LIST_Y + 8,
                0xAAAAAA);
        } else {
            int last = Math.min(plan.entries.size(), scroll + rows);
            String page = (scroll + 1) + "-" + last + "/" + plan.entries.size();
            drawString(
                fontRendererObj,
                page,
                x + listWidth() - fontRendererObj.getStringWidth(page),
                height - 37,
                0xAAAAAA);
            drawScrollBar(x + listWidth() - 3, rows);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private String summaryText() {
        int completed = plan.count(ResearchPlan.Action.COMPLETED);
        int pending = plan.count(ResearchPlan.Action.LEARN_DISCOVERY) + plan.count(ResearchPlan.Action.SOLVE_EXISTING)
            + plan.count(ResearchPlan.Action.GENERATE_AND_SOLVE);
        int wait = plan.count(ResearchPlan.Action.WAIT_FOR_PREREQUISITES);
        int hidden = plan.count(ResearchPlan.Action.HIDDEN);
        int unavailable = plan.count(ResearchPlan.Action.DIRECT) + plan.count(ResearchPlan.Action.UNSUPPORTED);
        return fontRendererObj.trimStringToWidth(
            String.format(
                StatCollector.translateToLocal("tcautores.plan_summary"),
                completed,
                pending,
                wait,
                hidden,
                unavailable),
            listWidth());
    }

    private static String actionText(ResearchPlan.Action action) {
        return StatCollector.translateToLocal(
            "tcautores.plan_action." + action.name()
                .toLowerCase());
    }

    private static int actionColor(ResearchPlan.Action action) {
        if (action == ResearchPlan.Action.HIDDEN || action == ResearchPlan.Action.DIRECT
            || action == ResearchPlan.Action.UNSUPPORTED) return 0xEF8585;
        if (action == ResearchPlan.Action.COMPLETED) return 0xAAAAAA;
        if (action == ResearchPlan.Action.WAIT_FOR_PREREQUISITES) return 0xE0C178;
        return 0xA8E0B2;
    }

    private int listWidth() {
        return Math.min(520, width - 20);
    }

    private int listX() {
        return (width - listWidth()) / 2;
    }

    private int visibleRows() {
        return Math.max(2, (height - LIST_Y - 48) / ROW_HEIGHT);
    }

    private void clampScroll() {
        int size = plan == null ? 0 : plan.entries.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, size - visibleRows())));
    }

    private void drawScrollBar(int x, int rows) {
        int size = plan.entries.size();
        if (size <= rows) return;
        int height = rows * ROW_HEIGHT;
        int thumbHeight = Math.max(8, height * rows / size);
        int maxScroll = size - rows;
        int thumbY = LIST_Y + (height - thumbHeight) * scroll / maxScroll;
        drawRect(x, LIST_Y, x + 2, LIST_Y + height, 0xAA101010);
        drawRect(x, thumbY, x + 2, thumbY + thumbHeight, 0xFFAAAAAA);
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

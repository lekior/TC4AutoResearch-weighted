package com.Emil.TCAutoResearch;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import cpw.mods.fml.client.config.GuiButtonExt;

/** Shared vanilla-style layout helpers for weight configuration pages. */
public abstract class GuiWeightScreen extends GuiScreen {

    protected final void drawWeightBackground() {
        drawDefaultBackground();
    }

    protected final void drawWeightPanel(int left, int top, int right, int bottom) {
        drawGradientRect(left, top, right, bottom, 0xE0222222, 0xE0101010);
    }

    protected final GuiButton weightButton(int id, int x, int y, int width, int height, String text) {
        return new GuiButtonExt(id, x, y, width, height, text);
    }
}

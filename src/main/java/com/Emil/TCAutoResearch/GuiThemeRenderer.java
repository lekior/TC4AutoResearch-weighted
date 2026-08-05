package com.Emil.TCAutoResearch;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

/** Draws theme colors through explicit RGBA vertices for lwjgl3ify compatibility. */
final class GuiThemeRenderer {

    private static final int ISOLATED_STATE_MASK = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
        | GL11.GL_CURRENT_BIT
        | GL11.GL_LIGHTING_BIT
        | GL11.GL_TEXTURE_BIT;

    private GuiThemeRenderer() {}

    static void isolated(Runnable drawing) {
        GL11.glPushAttrib(ISOLATED_STATE_MASK);
        try {
            drawing.run();
        } finally {
            GL11.glPopAttrib();
        }
    }

    static void rect(int left, int top, int right, int bottom, int color) {
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean textureEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean lightingEnabled = GL11.glIsEnabled(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24);
        tessellator.addVertex(left, bottom, 0.0D);
        tessellator.addVertex(right, bottom, 0.0D);
        tessellator.addVertex(right, top, 0.0D);
        tessellator.addVertex(left, top, 0.0D);
        tessellator.draw();

        if (textureEnabled) GL11.glEnable(GL11.GL_TEXTURE_2D);
        else GL11.glDisable(GL11.GL_TEXTURE_2D);
        if (blendEnabled) GL11.glEnable(GL11.GL_BLEND);
        else GL11.glDisable(GL11.GL_BLEND);
        if (depthEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        else GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (lightingEnabled) GL11.glEnable(GL11.GL_LIGHTING);
        else GL11.glDisable(GL11.GL_LIGHTING);
    }
}

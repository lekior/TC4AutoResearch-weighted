package com.Emil.TCAutoResearch.mixins;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.Emil.TCAutoResearch.ResearchBrowserNavigation;
import com.Emil.TCAutoResearch.ResearchCatalog;
import com.Emil.TCAutoResearch.ResearchNoteGenerationController;

import cpw.mods.fml.client.config.GuiButtonExt;
import thaumcraft.api.research.ResearchItem;
import thaumcraft.client.gui.GuiResearchBrowser;

@Mixin(value = GuiResearchBrowser.class, remap = false)
public abstract class GuiResearchBrowserMixin extends GuiScreen {

    @Shadow
    private static String selectedCategory;
    @Shadow
    protected int paneHeight;
    @Shadow
    protected double field_74117_m;
    @Shadow
    protected double field_74115_n;
    @Shadow
    protected double guiMapX;
    @Shadow
    protected double guiMapY;
    @Shadow
    protected double field_74124_q;
    @Shadow
    protected double field_74123_r;

    @Unique
    private GuiButton tcAutoResearch$generateCategoryButton;

    @Shadow
    public abstract void updateResearch();

    @Inject(method = { "initGui", "func_73866_w_" }, at = @At("HEAD"), require = 1)
    private void tcAutoResearch$applyPendingNavigation(CallbackInfo ci) {
        ResearchItem target = ResearchBrowserNavigation.consume();
        if (target == null) return;
        selectedCategory = target.category;
        updateResearch();
        double targetX = target.displayColumn * 24.0 - 101.0;
        double targetY = target.displayRow * 24.0 - 87.0;
        field_74117_m = guiMapX = field_74124_q = targetX;
        field_74115_n = guiMapY = field_74123_r = targetY;
    }

    @Inject(method = { "initGui", "func_73866_w_" }, at = @At("TAIL"), require = 1)
    private void tcAutoResearch$addGenerateCategoryButton(CallbackInfo ci) {
        int y = Math.min(height - 22, (height + paneHeight) / 2 + 2);
        tcAutoResearch$generateCategoryButton = new GuiButtonExt(
            202,
            width / 2 - 54,
            y,
            108,
            20,
            StatCollector.translateToLocal("tcautores.generate_category"));
        buttonList.add(tcAutoResearch$generateCategoryButton);
    }

    @Inject(method = { "actionPerformed", "func_146284_a" }, at = @At("HEAD"), cancellable = true, require = 1)
    private void tcAutoResearch$handleGenerateCategoryButton(GuiButton button, CallbackInfo ci) {
        if (button.id == 202) {
            if (ResearchNoteGenerationController.isRunning()) {
                ResearchNoteGenerationController.cancel();
            } else {
                ResearchNoteGenerationController.start(
                    mc.thePlayer,
                    mc,
                    ResearchCatalog.generatable(mc.thePlayer, ResearchCatalog.Scope.CURRENT_CATEGORY, selectedCategory),
                    null,
                    true);
            }
            ci.cancel();
        }
    }

    @Inject(method = "updateResearch", at = @At("TAIL"))
    private void tcAutoResearch$rememberCategory(CallbackInfo ci) {
        ResearchCatalog.setCurrentCategory(selectedCategory);
    }

    @Inject(method = { "drawScreen", "func_73863_a" }, at = @At("TAIL"), require = 1)
    private void tcAutoResearch$updateGenerateCategoryButton(int mouseX, int mouseY, float partialTicks,
        CallbackInfo ci) {
        if (tcAutoResearch$generateCategoryButton == null) return;
        tcAutoResearch$generateCategoryButton.displayString = ResearchNoteGenerationController.isRunning()
            ? ResearchNoteGenerationController.progressText()
            : StatCollector.translateToLocal("tcautores.generate_category");
    }
}

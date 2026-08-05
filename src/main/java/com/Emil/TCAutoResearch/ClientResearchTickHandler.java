package com.Emil.TCAutoResearch;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import thaumcraft.client.gui.GuiResearchTable;
import thaumcraft.common.lib.research.ResearchNoteData;

public final class ClientResearchTickHandler {

    private static Watch watch;

    public static void watch(EntityPlayer player, Minecraft mc, GuiResearchTableHelperInterface helper) {
        watch = new Watch(player, mc, helper);
    }

    public static void stopWatching() {
        watch = null;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ResearchNoteGenerationController.clientTick();
        AspectSynthesisController.clientTick();
        ResearchSolveController.clientTick();
        BatchResearchController.clientTick();
        TargetResearchController.clientTick();
        Watch current = watch;
        if (current == null || TargetResearchController.isRunning()
            || ResearchNoteGenerationController.isRunning()
            || BatchResearchController.isRunning()
            || AspectSynthesisController.isRunning()
            || !Config.autoResearch()) return;
        if (++current.ticks < 10) return;
        current.ticks = 0;
        if (current.mc.currentScreen != current.gui) return;
        ResearchNoteData note = current.gui.note;
        if (note == null) {
            current.lastPuzzle = null;
            current.lastComplete = false;
            return;
        }
        String puzzle = ResearchNoteFingerprint.topology(note);
        if (note.complete) {
            current.lastPuzzle = puzzle;
            current.lastComplete = true;
            return;
        }
        if (ResearchSolveController.isAutomaticSuppressed(note)) return;
        if (ResearchSolveController.isProcessing(note)) return;
        if (puzzle.equals(current.lastPuzzle) && !current.lastComplete) return;
        current.lastPuzzle = puzzle;
        current.lastComplete = false;
        ResearchSolveController.request(current.helper, current.player, current.mc, true);
    }

    private static final class Watch {

        final EntityPlayer player;
        final Minecraft mc;
        final GuiResearchTableHelperInterface helper;
        final GuiResearchTable gui;
        String lastPuzzle;
        boolean lastComplete;
        int ticks;

        private Watch(EntityPlayer player, Minecraft mc, GuiResearchTableHelperInterface helper) {
            this.player = player;
            this.mc = mc;
            this.helper = helper;
            this.gui = (GuiResearchTable) helper;
        }
    }
}

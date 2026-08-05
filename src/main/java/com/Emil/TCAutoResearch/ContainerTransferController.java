package com.Emil.TCAutoResearch;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0EPacketClickWindow;

import thaumcraft.common.lib.research.ResearchNoteData;

public final class ContainerTransferController {

    public enum Status {
        IDLE,
        WAITING,
        ACCEPTED,
        REJECTED,
        RESYNCHRONIZED
    }

    static final int SETTLE_TICKS = 1;
    private static final int MAX_ATTEMPTS = 2;

    private static int windowId = -1;
    private static short transactionId;
    private static String sourceNoteState = "";
    private static Status status = Status.IDLE;
    private static int containerSlot = -1;
    private static int mouseButton;
    private static int clickMode;
    private static int attempts;

    private ContainerTransferController() {}

    public static synchronized boolean begin(Minecraft mc, EntityPlayer player, int containerSlot) {
        return beginClick(mc, player, containerSlot, 0, 1, false);
    }

    public static synchronized boolean beginSwap(Minecraft mc, EntityPlayer player, int containerSlot, int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        return beginClick(mc, player, containerSlot, hotbarSlot, 2, false);
    }

    public static synchronized boolean retry(Minecraft mc, EntityPlayer player) {
        if (status != Status.RESYNCHRONIZED || attempts >= MAX_ATTEMPTS) return false;
        int retrySlot = containerSlot;
        int retryButton = mouseButton;
        int retryMode = clickMode;
        String expectedSource = sourceNoteState;
        if (!expectedSource.isEmpty()) {
            Container container = player == null ? null : player.openContainer;
            if (container == null || retrySlot < 0 || retrySlot >= container.inventorySlots.size()) return false;
            ResearchNoteData sourceNote = ResearchNoteItems.data(
                container.getSlot(retrySlot)
                    .getStack());
            if (sourceNote == null || !expectedSource.equals(ResearchNoteFingerprint.state(sourceNote))) return false;
        }
        return beginClick(mc, player, retrySlot, retryButton, retryMode, true);
    }

    private static boolean beginClick(Minecraft mc, EntityPlayer player, int containerSlot, int mouseButton, int mode,
        boolean retry) {
        if (status == Status.WAITING || mc == null || player == null || mc.getNetHandler() == null) return false;
        Container container = player.openContainer;
        if (container == null || containerSlot < 0 || containerSlot >= container.inventorySlots.size()) return false;
        Slot slot = container.getSlot(containerSlot);
        if (slot == null || !slot.getHasStack()) return false;

        ResearchNoteData sourceNote = ResearchNoteItems.data(slot.getStack());
        String sourceState = sourceNote == null ? "" : ResearchNoteFingerprint.state(sourceNote);
        int transferWindowId = container.windowId;
        short transferTransactionId = container.getNextTransactionID(player.inventory);
        ItemStack result = container.slotClick(containerSlot, mouseButton, mode, player);
        beginTracking(transferWindowId, transferTransactionId, sourceState);
        ContainerTransferController.containerSlot = containerSlot;
        ContainerTransferController.mouseButton = mouseButton;
        clickMode = mode;
        attempts = retry ? attempts + 1 : 1;
        mc.getNetHandler()
            .addToSendQueue(
                new C0EPacketClickWindow(
                    transferWindowId,
                    containerSlot,
                    mouseButton,
                    mode,
                    result,
                    transferTransactionId));
        return true;
    }

    public static synchronized Status status() {
        return status;
    }

    public static synchronized String sourceNoteState() {
        return sourceNoteState;
    }

    public static synchronized boolean matchesOpenContainer(EntityPlayer player) {
        return player != null && player.openContainer != null && windowId == player.openContainer.windowId;
    }

    public static synchronized void onConfirmation(int confirmedWindowId, short confirmedTransactionId,
        boolean accepted) {
        if (status != Status.WAITING || windowId != confirmedWindowId || transactionId != confirmedTransactionId)
            return;
        status = accepted ? Status.ACCEPTED : Status.REJECTED;
    }

    public static synchronized void onWindowItems(int synchronizedWindowId) {
        if (status == Status.REJECTED && windowId == synchronizedWindowId) status = Status.RESYNCHRONIZED;
    }

    static boolean hasSettled(int currentTick, int eventTick) {
        return eventTick >= 0 && currentTick - eventTick >= SETTLE_TICKS;
    }

    public static synchronized void clear() {
        windowId = -1;
        transactionId = 0;
        sourceNoteState = "";
        status = Status.IDLE;
        containerSlot = -1;
        mouseButton = 0;
        clickMode = 0;
        attempts = 0;
    }

    static synchronized void beginTracking(int trackedWindowId, short trackedTransactionId) {
        beginTracking(trackedWindowId, trackedTransactionId, "");
    }

    private static synchronized void beginTracking(int trackedWindowId, short trackedTransactionId,
        String trackedSourceNoteState) {
        windowId = trackedWindowId;
        transactionId = trackedTransactionId;
        sourceNoteState = trackedSourceNoteState;
        status = Status.WAITING;
    }
}

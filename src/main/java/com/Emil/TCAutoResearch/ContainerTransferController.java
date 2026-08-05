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
    static final int MAX_ATTEMPTS = 4;
    private static final int MAX_RETRY_DELAY_TICKS = 20;

    private static int windowId = -1;
    private static short transactionId;
    private static ItemStack sourceStack;
    private static String sourceNoteState = "";
    private static Status status = Status.IDLE;
    private static int containerSlot = -1;
    private static int mouseButton;
    private static int clickMode;
    private static int attempts;
    private static int retryNotBeforeTick = -1;
    private static long serverUpdateGeneration;
    private static long transferStartGeneration;
    private static long windowSyncGeneration;
    private static long transferStartWindowSyncGeneration;
    private static int stablePostStateTicks;

    private ContainerTransferController() {}

    public static synchronized boolean begin(Minecraft mc, EntityPlayer player, int containerSlot) {
        return beginClick(mc, player, containerSlot, 0, 1, false);
    }

    public static synchronized boolean beginSwap(Minecraft mc, EntityPlayer player, int containerSlot, int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        return beginClick(mc, player, containerSlot, hotbarSlot, 2, false);
    }

    public static synchronized boolean retry(Minecraft mc, EntityPlayer player) {
        if (!canRetry()) return false;
        int retrySlot = containerSlot;
        int retryButton = mouseButton;
        int retryMode = clickMode;
        if (!sourceStateMatches(player, retrySlot)) return false;
        return beginClick(mc, player, retrySlot, retryButton, retryMode, true);
    }

    private static boolean beginClick(Minecraft mc, EntityPlayer player, int containerSlot, int mouseButton, int mode,
        boolean retry) {
        if (status == Status.WAITING || mc == null || player == null || mc.getNetHandler() == null) return false;
        Container container = player.openContainer;
        if (container == null || containerSlot < 0 || containerSlot >= container.inventorySlots.size()) return false;
        Slot slot = container.getSlot(containerSlot);
        if (slot == null || !slot.getHasStack()) return false;

        ItemStack sourceStack = slot.getStack();
        ResearchNoteData sourceNote = ResearchNoteItems.data(sourceStack);
        String sourceState = sourceNote == null ? "" : ResearchNoteFingerprint.state(sourceNote);
        int transferWindowId = container.windowId;
        short transferTransactionId = container.getNextTransactionID(player.inventory);
        ItemStack result = container.slotClick(containerSlot, mouseButton, mode, player);
        beginTracking(transferWindowId, transferTransactionId, sourceState, sourceStack.copy());
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

    public static synchronized boolean canRetry() {
        return status == Status.RESYNCHRONIZED && attempts < MAX_ATTEMPTS;
    }

    /**
     * Returns true after the current retry backoff and one client settle tick have elapsed.
     * The gate is created when the caller first observes a full server resynchronization.
     */
    public static synchronized boolean retryReady(int currentTick) {
        if (status != Status.RESYNCHRONIZED) return false;
        if (retryNotBeforeTick < 0) retryNotBeforeTick = currentTick + retryDelayTicks();
        return currentTick >= retryNotBeforeTick;
    }

    static synchronized boolean sourceStateMatches(EntityPlayer player, int slotIndex) {
        Container container = player == null ? null : player.openContainer;
        if (container == null || slotIndex < 0 || slotIndex >= container.inventorySlots.size()) return false;
        ItemStack currentStack = container.getSlot(slotIndex)
            .getStack();
        if (!sameStack(sourceStack, currentStack)) return false;
        if (sourceNoteState.isEmpty()) return true;
        ResearchNoteData sourceNote = ResearchNoteItems.data(currentStack);
        return sourceNote != null && sourceNoteState.equals(ResearchNoteFingerprint.state(sourceNote));
    }

    public static synchronized boolean matchesOpenContainer(EntityPlayer player) {
        return player != null && player.openContainer != null && windowId == player.openContainer.windowId;
    }

    /**
     * Returns true once the server has authoritatively settled this transfer.
     *
     * An accepted ConfirmTransaction is itself the server acknowledgement. Some servers
     * do not follow an accepted click with SetSlot or WindowItems, so those packets are
     * only required when recovering from a rejected click.
     */
    public static synchronized boolean hasServerStateUpdate() {
        return status == Status.ACCEPTED || (status != Status.IDLE && serverUpdateGeneration > transferStartGeneration);
    }

    /**
     * Requires the observed post-transfer state to remain stable for two client ticks.
     */
    public static synchronized boolean observePostState(boolean ready) {
        if (!ready) {
            stablePostStateTicks = 0;
            return false;
        }
        stablePostStateTicks++;
        return stablePostStateTicks >= 2;
    }

    public static synchronized void onConfirmation(int confirmedWindowId, short confirmedTransactionId,
        boolean accepted) {
        if (status != Status.WAITING || windowId != confirmedWindowId || transactionId != confirmedTransactionId)
            return;
        status = accepted ? Status.ACCEPTED : Status.REJECTED;
        if (!accepted && windowSyncGeneration > transferStartWindowSyncGeneration) {
            status = Status.RESYNCHRONIZED;
            retryNotBeforeTick = -1;
        }
    }

    public static synchronized void onSetSlot(int synchronizedWindowId) {
        if (isTrackedSlotUpdate(synchronizedWindowId)) serverUpdateGeneration++;
    }

    public static synchronized void onWindowItems(int synchronizedWindowId) {
        if (!isTrackedWindow(synchronizedWindowId)) return;
        serverUpdateGeneration++;
        windowSyncGeneration++;
        if (status == Status.REJECTED) {
            status = Status.RESYNCHRONIZED;
            retryNotBeforeTick = -1;
        }
    }

    static boolean hasSettled(int currentTick, int eventTick) {
        return eventTick >= 0 && currentTick - eventTick >= SETTLE_TICKS;
    }

    public static synchronized void clear() {
        windowId = -1;
        transactionId = 0;
        sourceStack = null;
        sourceNoteState = "";
        status = Status.IDLE;
        containerSlot = -1;
        mouseButton = 0;
        clickMode = 0;
        attempts = 0;
        retryNotBeforeTick = -1;
        stablePostStateTicks = 0;
    }

    static synchronized void beginTracking(int trackedWindowId, short trackedTransactionId) {
        beginTracking(trackedWindowId, trackedTransactionId, "", null);
    }

    private static synchronized void beginTracking(int trackedWindowId, short trackedTransactionId,
        String trackedSourceNoteState, ItemStack trackedSourceStack) {
        windowId = trackedWindowId;
        transactionId = trackedTransactionId;
        sourceStack = trackedSourceStack;
        sourceNoteState = trackedSourceNoteState;
        transferStartGeneration = serverUpdateGeneration;
        transferStartWindowSyncGeneration = windowSyncGeneration;
        retryNotBeforeTick = -1;
        stablePostStateTicks = 0;
        status = Status.WAITING;
    }

    private static int retryDelayTicks() {
        return retryDelayTicksForAttempts(attempts);
    }

    static int retryDelayTicksForAttempts(int attemptCount) {
        int retryNumber = Math.max(0, attemptCount - 1);
        int shift = Math.min(5, retryNumber);
        return Math.max(SETTLE_TICKS, Math.min(MAX_RETRY_DELAY_TICKS, 1 << shift));
    }

    private static boolean sameStack(ItemStack expected, ItemStack actual) {
        return expected == null ? actual == null
            : actual != null && expected.stackSize == actual.stackSize
                && ItemStack.areItemStacksEqual(expected, actual);
    }

    private static boolean isTrackedWindow(int synchronizedWindowId) {
        return windowId >= 0 && windowId == synchronizedWindowId;
    }

    private static boolean isTrackedSlotUpdate(int synchronizedWindowId) {
        return isTrackedWindow(synchronizedWindowId) || synchronizedWindowId == 0;
    }
}

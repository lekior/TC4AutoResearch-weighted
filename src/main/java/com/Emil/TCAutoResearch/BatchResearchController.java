package com.Emil.TCAutoResearch;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;

import thaumcraft.client.gui.GuiResearchTable;
import thaumcraft.client.lib.PlayerNotifications;
import thaumcraft.common.lib.research.ResearchNoteData;

public final class BatchResearchController {

    private static final int TRANSFER_TIMEOUT_TICKS = 60;
    private static final int TRANSFER_RESTART_DELAY_TICKS = 20;

    private static BatchState active;

    private BatchResearchController() {}

    public interface Listener {

        Listener NONE = new Listener() {

            @Override
            public void onSuccess(int completed) {}

            @Override
            public void onFailure(String key, int completed) {}
        };

        void onSuccess(int completed);

        void onFailure(String key, int completed);
    }

    public static void start(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc) {
        start(helper, player, mc, Listener.NONE, true);
    }

    public static void start(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc,
        Listener listener, boolean notifySummary) {
        start(helper, player, mc, null, listener, notifySummary);
    }

    public static void startForKey(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc,
        String researchKey, Listener listener, boolean notifySummary) {
        if (researchKey == null || researchKey.isEmpty()) {
            (listener == null ? Listener.NONE : listener).onFailure("tcautores.target_invalid", 0);
            return;
        }
        start(helper, player, mc, researchKey, listener, notifySummary);
    }

    private static void start(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc,
        String researchKey, Listener listener, boolean notifySummary) {
        cancel();
        ResearchSolveController.cancel();
        AspectSynthesisController.cancel();
        int total = researchKey == null ? helper.countIncompleteResearchNotes()
            : helper.countIncompleteResearchNotes(researchKey);
        if (total <= 0) {
            if (notifySummary)
                PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.batch_none"));
            (listener == null ? Listener.NONE : listener).onSuccess(0);
            return;
        }
        active = new BatchState(
            helper,
            player,
            mc,
            total,
            researchKey,
            listener == null ? Listener.NONE : listener,
            notifySummary);
        if (notifySummary) PlayerNotifications
            .addNotification(String.format(StatCollector.translateToLocal("tcautores.batch_started"), total));
    }

    public static void cancel() {
        boolean wasRunning = active != null;
        active = null;
        ContainerTransferController.clear();
        if (wasRunning) ResearchSolveController.cancel();
    }

    public static boolean isRunning() {
        return active != null;
    }

    public static String buttonText() {
        BatchState current = active;
        if (current == null) return StatCollector.translateToLocal("tcautores.batch_unlock");
        return String
            .format(StatCollector.translateToLocal("tcautores.batch_stop_progress"), current.completed, current.total);
    }

    public static void clientTick() {
        BatchState current = active;
        if (current != null) current.tick();
    }

    public static void attach(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc) {
        BatchState current = active;
        if (current != null && current.player == player) current.attach(helper, mc);
    }

    public static void onResearchTableClosed(GuiResearchTableHelperInterface helper) {
        BatchState current = active;
        if (current != null && current.helper == helper) current.detach();
    }

    private enum Phase {
        WAIT_GUI,
        ADVANCE,
        WAIT_TABLE_EMPTY,
        WAIT_NOTE_LOADED,
        SOLVING,
        RESTART_WAIT
    }

    private static final class BatchState implements ResearchSolveController.SolveListener {

        final EntityPlayer player;
        Minecraft mc;
        GuiResearchTableHelperInterface helper;
        GuiResearchTable gui;
        final int total;
        final String researchKey;
        final Listener listener;
        final boolean notifySummary;
        Phase phase = Phase.ADVANCE;
        int completed;
        int tick;
        int deadline;
        int acceptedTick = -1;
        int resynchronizedTick = -1;
        boolean transferPending;
        String expectedNoteState = "";

        private BatchState(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc, int total,
            String researchKey, Listener listener, boolean notifySummary) {
            this.helper = helper;
            this.player = player;
            this.mc = mc;
            this.gui = (GuiResearchTable) helper;
            this.total = total;
            this.researchKey = researchKey;
            this.listener = listener;
            this.notifySummary = notifySummary;
        }

        void attach(GuiResearchTableHelperInterface newHelper, Minecraft newMinecraft) {
            helper = newHelper;
            gui = (GuiResearchTable) newHelper;
            mc = newMinecraft;
            if (phase == Phase.WAIT_GUI) phase = Phase.ADVANCE;
        }

        void detach() {
            clearTransfer();
            ResearchSolveController.cancel();
            helper = null;
            gui = null;
            phase = Phase.WAIT_GUI;
        }

        void tick() {
            if (active != this) return;
            tick++;
            if (phase == Phase.WAIT_GUI || helper == null || gui == null) return;
            if (phase == Phase.RESTART_WAIT) {
                if (mc.currentScreen != gui || tick < deadline) return;
                phase = Phase.ADVANCE;
                deadline = 0;
                return;
            }
            if (mc.currentScreen != gui) {
                detach();
                return;
            }
            if (phase == Phase.ADVANCE) advance();
            else if (phase == Phase.WAIT_TABLE_EMPTY) waitForEmptyTable();
            else if (phase == Phase.WAIT_NOTE_LOADED) waitForLoadedNote();
        }

        private void advance() {
            ItemStack tableStack = helper.researchNoteStack();
            if (ResearchNoteItems.isIncomplete(tableStack)) {
                if (researchKey != null && !ResearchNoteItems.hasKey(tableStack, researchKey)) {
                    transfer(1, Phase.WAIT_TABLE_EMPTY);
                    return;
                }
                phase = Phase.WAIT_NOTE_LOADED;
                deadline = tick + TRANSFER_TIMEOUT_TICKS;
                transferPending = false;
                expectedNoteState = noteState(tableStack);
                waitForLoadedNote();
                return;
            }
            if (tableStack != null) {
                transfer(1, Phase.WAIT_TABLE_EMPTY);
                return;
            }
            int nextSlot = researchKey == null ? helper.findIncompleteResearchNoteSlot()
                : helper.findIncompleteResearchNoteSlot(researchKey);
            if (nextSlot < 0) {
                finish();
                return;
            }
            transfer(nextSlot, Phase.WAIT_NOTE_LOADED);
        }

        private void transfer(int slot, Phase waitingPhase) {
            ContainerTransferController.clear();
            if (!ContainerTransferController.begin(mc, player, slot)) {
                fail("tcautores.batch_transfer_failed");
                return;
            }
            phase = waitingPhase;
            deadline = tick + TRANSFER_TIMEOUT_TICKS;
            acceptedTick = -1;
            resynchronizedTick = -1;
            transferPending = true;
            expectedNoteState = ContainerTransferController.sourceNoteState();
        }

        private void waitForEmptyTable() {
            if (!transferConfirmed()) return;
            if (ContainerTransferController.observePostState(helper.researchNoteStack() == null)) {
                clearTransfer();
                phase = Phase.ADVANCE;
                return;
            }
            if (tick >= deadline) fail("tcautores.batch_transfer_state_timeout");
        }

        private void waitForLoadedNote() {
            if (!transferConfirmed()) return;
            ItemStack stack = helper.researchNoteStack();
            ResearchNoteData stackData = ResearchNoteItems.data(stack);
            ResearchNoteData guiData = gui.note;
            if (stackData == null || !expectedNoteState.equals(ResearchNoteFingerprint.state(stackData))) {
                ContainerTransferController.observePostState(false);
                if (tick >= deadline) fail("tcautores.note_changed");
                return;
            }
            boolean synchronizedNote = stackData != null && !stackData.complete
                && stack.getItemDamage() < 64
                && guiData != null
                && !guiData.complete
                && ResearchNoteFingerprint.state(stackData)
                    .equals(ResearchNoteFingerprint.state(guiData));
            if (ContainerTransferController.observePostState(synchronizedNote)) {
                clearTransfer();
                startSolve();
                return;
            }
            if (tick >= deadline) fail("tcautores.batch_transfer_timeout");
        }

        private boolean transferConfirmed() {
            if (!transferPending) return true;
            if (!ContainerTransferController.matchesOpenContainer(player)) {
                fail("tcautores.batch_container_changed");
                return false;
            }
            ContainerTransferController.Status status = ContainerTransferController.status();
            if (status == ContainerTransferController.Status.REJECTED) {
                if (tick >= deadline) restartAfterTransferFailure();
                return false;
            }
            if (status == ContainerTransferController.Status.RESYNCHRONIZED) {
                if (resynchronizedTick < 0) {
                    resynchronizedTick = tick;
                    deadline = Math.max(deadline, tick + TRANSFER_TIMEOUT_TICKS);
                    return false;
                }
                if (!ContainerTransferController.hasSettled(tick, resynchronizedTick)) return false;
                if (!ContainerTransferController.retry(mc, player)) {
                    restartAfterTransferFailure();
                    return false;
                }
                acceptedTick = -1;
                resynchronizedTick = -1;
                deadline = tick + TRANSFER_TIMEOUT_TICKS;
                return false;
            }
            if (status == ContainerTransferController.Status.ACCEPTED) {
                if (!ContainerTransferController.hasServerStateUpdate()) {
                    if (tick >= deadline) fail("tcautores.batch_transfer_state_timeout");
                    return false;
                }
                if (acceptedTick < 0) {
                    acceptedTick = tick;
                    deadline = Math.max(deadline, tick + TRANSFER_TIMEOUT_TICKS);
                    return false;
                }
                return ContainerTransferController.hasSettled(tick, acceptedTick);
            }
            if (tick >= deadline) restartAfterTransferFailure();
            return false;
        }

        private void restartAfterTransferFailure() {
            if (phase == Phase.RESTART_WAIT) return;
            clearTransfer();
            ResearchSolveController.cancel();
            phase = Phase.RESTART_WAIT;
            deadline = tick + TRANSFER_RESTART_DELAY_TICKS;
        }

        private void clearTransfer() {
            ContainerTransferController.clear();
            transferPending = false;
            acceptedTick = -1;
            resynchronizedTick = -1;
            expectedNoteState = "";
        }

        private static String noteState(ItemStack stack) {
            ResearchNoteData data = ResearchNoteItems.data(stack);
            return data == null ? "" : ResearchNoteFingerprint.state(data);
        }

        private void startSolve() {
            phase = Phase.SOLVING;
            ResearchSolveController.request(helper, player, mc, true, this);
        }

        @Override
        public void onSuccess() {
            if (active != this) return;
            completed++;
            phase = Phase.ADVANCE;
            deadline = 0;
            acceptedTick = -1;
            resynchronizedTick = -1;
            transferPending = false;
            expectedNoteState = "";
        }

        @Override
        public void onFailure(String key) {
            if (active == this) fail(key);
        }

        private void finish() {
            active = null;
            ContainerTransferController.clear();
            String message = String
                .format(StatCollector.translateToLocal("tcautores.batch_complete"), completed, total);
            if (notifySummary) {
                PlayerNotifications.addNotification(message);
                player.addChatMessage(new ChatComponentText(message));
            }
            listener.onSuccess(completed);
        }

        private void fail(String key) {
            if (active != this) return;
            if (isRecoverableTransferFailure(key)) {
                restartAfterTransferFailure();
                return;
            }
            active = null;
            ContainerTransferController.clear();
            ResearchSolveController.cancel();
            String reason = StatCollector.translateToLocal(key);
            String message = String
                .format(StatCollector.translateToLocal("tcautores.batch_stopped"), completed, total, reason);
            if (notifySummary) {
                PlayerNotifications.addNotification(message);
                player.addChatMessage(new ChatComponentText(message));
            }
            listener.onFailure(key, completed);
        }

        private static boolean isRecoverableTransferFailure(String key) {
            return "tcautores.batch_transfer_failed".equals(key) || "tcautores.batch_transfer_rejected".equals(key)
                || "tcautores.batch_transfer_timeout".equals(key)
                || "tcautores.batch_transfer_state_timeout".equals(key)
                || "tcautores.batch_container_changed".equals(key);
        }
    }
}

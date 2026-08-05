package com.Emil.TCAutoResearch;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;

import thaumcraft.api.research.ResearchCategories;
import thaumcraft.api.research.ResearchItem;
import thaumcraft.client.gui.GuiResearchTable;
import thaumcraft.client.lib.PlayerNotifications;
import thaumcraft.common.lib.research.ResearchManager;

public final class TargetResearchController {

    private static final int TRANSFER_TIMEOUT_TICKS = 60;

    private static Task active;

    private TargetResearchController() {}

    public static void start(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc,
        ResearchItem target, boolean includePrerequisites) {
        List<ResearchItem> plan = ResearchCatalog.prerequisitePlan(target, includePrerequisites);
        startPlan(helper, player, mc, plan, target == null ? "" : target.key);
    }

    public static void startPlan(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc,
        Collection<ResearchItem> research, String label) {
        startPlan(helper, player, mc, research, label, null, null);
    }

    public static void startAllResearchable(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc,
        ResearchCatalog.Scope scope, String category) {
        startPlan(
            helper,
            player,
            mc,
            ResearchCatalog.actionable(player, scope, category),
            scope == ResearchCatalog.Scope.CURRENT_CATEGORY ? category : "ALL",
            scope,
            category);
    }

    public static void startExistingNotes(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc) {
        List<ResearchItem> research = new java.util.ArrayList<>();
        int discoveries = 0;
        int incomplete = 0;
        for (String key : helper.researchNoteKeys()) {
            ResearchItem item = ResearchCategories.getResearch(key);
            if (item != null && !ResearchManager.isResearchComplete(player.getCommandSenderName(), key)) {
                research.add(item);
                if (helper.findCompletedResearchNoteSlot(key) >= 0) discoveries++;
                else if (helper.countIncompleteResearchNotes(key) > 0) incomplete++;
            }
        }
        if (research.isEmpty()) {
            PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.batch_none"));
            return;
        }
        PlayerNotifications.addNotification(
            String.format(
                StatCollector.translateToLocal("tcautores.batch_plan"),
                discoveries,
                incomplete,
                research.size()));
        startPlan(helper, player, mc, research, "NOTES");
    }

    private static void startPlan(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc,
        Collection<ResearchItem> research, String label, ResearchCatalog.Scope dynamicScope, String category) {
        cancel();
        ResearchNoteGenerationController.cancel();
        BatchResearchController.cancel();
        ResearchSolveController.cancel();
        LinkedHashMap<String, ResearchItem> unique = new LinkedHashMap<>();
        if (research != null) {
            for (ResearchItem item : research) if (item != null) unique.put(item.key, item);
        }
        List<ResearchItem> plan = new java.util.ArrayList<>(unique.values());
        if (plan.isEmpty()) {
            notifyFailure(player, "tcautores.target_invalid");
            return;
        }
        active = new Task(helper, player, mc, plan, label == null ? "" : label, dynamicScope, category);
    }

    public static void attach(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc) {
        Task current = active;
        if (current != null && current.player == player) current.attach(helper, mc);
    }

    public static void onResearchTableClosed(GuiResearchTableHelperInterface helper) {
        Task current = active;
        if (current != null && current.helper == helper) current.detach();
    }

    public static void cancel() {
        Task current = active;
        active = null;
        ResearchNoteGenerationController.cancel();
        BatchResearchController.cancel();
        ContainerTransferController.clear();
        if (current != null) ResearchSolveController.cancel();
    }

    public static boolean isRunning() {
        return active != null;
    }

    public static String buttonText() {
        Task current = active;
        if (current == null) return StatCollector.translateToLocal("tcautores.target_search");
        return String
            .format(StatCollector.translateToLocal("tcautores.target_stop_progress"), current.completed, current.total);
    }

    public static void clientTick() {
        Task current = active;
        if (current != null) current.tick();
    }

    private static void notifyFailure(EntityPlayer player, String key) {
        String message = StatCollector.translateToLocal("tcautores.target_failed") + ": "
            + StatCollector.translateToLocal(key);
        PlayerNotifications.addNotification(message);
        if (player != null) player.addChatMessage(new ChatComponentText(message));
    }

    private enum Phase {
        WAIT_GUI,
        ADVANCE,
        MOVE_PEN_OUT,
        GENERATING,
        MOVE_PEN_BACK,
        SOLVING,
        MOVE_DISCOVERY_OUT,
        SWAP_DISCOVERY_IN,
        WAIT_USE,
        SWAP_HELD_BACK
    }

    private static final class Task {

        final EntityPlayer player;
        final ArrayDeque<ResearchItem> plan;
        final Set<String> seen = new HashSet<>();
        final String targetKey;
        final ResearchCatalog.Scope dynamicScope;
        final String category;
        Minecraft mc;
        GuiResearchTableHelperInterface helper;
        GuiResearchTable gui;
        ResearchItem current;
        Phase phase = Phase.WAIT_GUI;
        int completed;
        int total;
        int tick;
        int deadline;
        int acceptedTick = -1;
        int resynchronizedTick = -1;
        boolean penMoved;
        int discoverySlot = -1;
        ItemStack originalHeld;

        private Task(GuiResearchTableHelperInterface helper, EntityPlayer player, Minecraft mc, List<ResearchItem> plan,
            String targetKey, ResearchCatalog.Scope dynamicScope, String category) {
            this.player = player;
            this.mc = mc;
            this.plan = new ArrayDeque<>(plan);
            this.total = plan.size();
            this.targetKey = targetKey;
            this.dynamicScope = dynamicScope;
            this.category = category;
            for (ResearchItem item : plan) seen.add(item.key);
            attach(helper, mc);
        }

        void attach(GuiResearchTableHelperInterface newHelper, Minecraft newMinecraft) {
            helper = newHelper;
            gui = (GuiResearchTable) newHelper;
            mc = newMinecraft;
            if (phase == Phase.WAIT_GUI) phase = Phase.ADVANCE;
        }

        void detach() {
            if (phase != Phase.WAIT_GUI) {
                fail("tcautores.batch_gui_closed");
                return;
            }
            helper = null;
            gui = null;
            phase = Phase.WAIT_GUI;
        }

        void tick() {
            if (active != this) return;
            if (mc == null || mc.thePlayer != player) {
                fail("tcautores.target_player_changed");
                return;
            }
            tick++;

            if (phase == Phase.WAIT_USE) {
                tickWaitUse();
                return;
            }
            if (current != null && phase != Phase.SWAP_DISCOVERY_IN
                && phase != Phase.SWAP_HELD_BACK
                && ResearchManager.isResearchComplete(player.getCommandSenderName(), current.key)) {
                completeCurrent();
            }

            if (phase == Phase.WAIT_GUI || phase == Phase.GENERATING || phase == Phase.SOLVING) return;
            if (gui == null || mc.currentScreen != gui) {
                phase = Phase.WAIT_GUI;
                return;
            }
            if (phase == Phase.ADVANCE) advance();
            else if (phase == Phase.MOVE_PEN_OUT) tickPenOut();
            else if (phase == Phase.MOVE_PEN_BACK) tickPenBack();
            else if (phase == Phase.MOVE_DISCOVERY_OUT) tickDiscoveryOut();
            else if (phase == Phase.SWAP_DISCOVERY_IN) tickSwapIn();
            else if (phase == Phase.SWAP_HELD_BACK) tickSwapBack();
        }

        private void advance() {
            while (current == null && !plan.isEmpty()) {
                ResearchItem candidate = plan.removeFirst();
                if (ResearchManager.isResearchComplete(player.getCommandSenderName(), candidate.key)) {
                    completed++;
                } else {
                    current = candidate;
                }
            }
            if (current == null && appendNewlyResearchable()) advance();
            if (current == null) {
                finish();
                return;
            }
            if (!isRevealed(current)) {
                failReason(
                    String.format(
                        StatCollector.translateToLocal("tcautores.target_hidden_detail"),
                        current.getName(),
                        current.key,
                        HiddenResearchUnlocks.describe(current)));
                return;
            }
            if (current.isVirtual() || current.isStub() || current.isAutoUnlock()) {
                fail("tcautores.target_not_note");
                return;
            }
            if (hasCompletedDiscovery(current.key)) {
                startAutomaticLearning();
                return;
            }
            if (helper.countIncompleteResearchNotes(current.key) > 0) {
                startSolve();
                return;
            }
            if (!ResearchManager.doesPlayerHaveRequisites(player.getCommandSenderName(), current.key)) {
                failPrerequisites();
                return;
            }
            if (isDirect(current)) {
                fail("tcautores.target_direct");
                return;
            }
            startGeneration();
        }

        private boolean appendNewlyResearchable() {
            if (dynamicScope == null) return false;
            int added = 0;
            for (ResearchItem item : ResearchCatalog.actionable(player, dynamicScope, category)) {
                if (seen.add(item.key)) {
                    plan.addLast(item);
                    added++;
                }
            }
            total += added;
            return added > 0;
        }

        private void failPrerequisites() {
            List<String> completedResearch = ResearchManager.getResearchForPlayerSafe(player.getCommandSenderName());
            List<String> missing = ResearchCatalog.missingPrerequisiteKeys(current, completedResearch);
            if (missing.isEmpty()) {
                fail("tcautores.target_prerequisites");
                return;
            }
            List<String> descriptions = new java.util.ArrayList<>();
            List<String> hiddenParents = current.parentsHidden == null ? java.util.Collections.emptyList()
                : Arrays.asList(current.parentsHidden);
            for (String key : missing) {
                ResearchItem prerequisite = ResearchCategories.getResearch(key);
                String name = prerequisite == null ? key : prerequisite.getName() + " [" + key + "]";
                if (hiddenParents.contains(key))
                    name = StatCollector.translateToLocal("tcautores.target_hidden_parent") + " " + name;
                descriptions.add(name);
            }
            failReason(
                String.format(
                    StatCollector.translateToLocal("tcautores.target_missing_prerequisites"),
                    current.getName(),
                    String.join(", ", descriptions)));
        }

        private boolean isRevealed(ResearchItem research) {
            List<String> completedResearch = ResearchManager.getResearchForPlayerSafe(player.getCommandSenderName());
            return ResearchCatalog.isRevealed(research, completedResearch);
        }

        private static boolean isDirect(ResearchItem research) {
            return ResearchCatalog.completesDirectly(research, thaumcraft.common.config.Config.researchDifficulty);
        }

        private boolean hasCompletedDiscovery(String key) {
            if (helper != null) {
                ItemStack tableStack = helper.researchNoteStack();
                if (ResearchNoteItems.hasKey(tableStack, key) && ResearchNoteItems.isComplete(tableStack)) return true;
            }
            for (ItemStack stack : player.inventory.mainInventory) {
                if (ResearchNoteItems.hasKey(stack, key) && ResearchNoteItems.isComplete(stack)) return true;
            }
            return false;
        }

        private void startGeneration() {
            if (ResearchManager.consumeInkFromPlayer(player, false)) {
                beginGeneration();
                return;
            }
            if (!helper.hasInk()) {
                fail("tcautores.generate_reason.no_ink");
                return;
            }
            penMoved = true;
            if (!beginTransfer(0, Phase.MOVE_PEN_OUT)) fail("tcautores.batch_transfer_failed");
        }

        private void tickPenOut() {
            if (!transferAccepted()) return;
            if (helper.scribingToolsStack() != null || !ResearchManager.consumeInkFromPlayer(player, false)) {
                if (tick >= deadline) fail("tcautores.batch_inventory_full");
                return;
            }
            clearTransfer();
            beginGeneration();
        }

        private void beginGeneration() {
            phase = Phase.GENERATING;
            int reservedSlots = helper.researchNoteStack() == null ? 0 : 1;
            ResearchNoteGenerationController.start(
                player,
                mc,
                java.util.Collections.singletonList(current),
                this::generationFinished,
                false,
                reservedSlots);
        }

        private void generationFinished(ResearchNoteGenerationController.Result result) {
            if (active != this) return;
            if (penMoved) {
                pendingGenerationResult = result;
                int penSlot = helper == null ? -1 : helper.findUsableScribingToolsSlot();
                if (penSlot < 0) {
                    fail("tcautores.generate_reason.no_ink");
                    return;
                }
                if (!beginTransfer(penSlot, Phase.MOVE_PEN_BACK)) fail("tcautores.batch_transfer_failed");
                return;
            }
            afterGeneration(result);
        }

        private ResearchNoteGenerationController.Result pendingGenerationResult;

        private void tickPenBack() {
            if (!transferAccepted()) return;
            if (!helper.hasInk()) {
                if (tick >= deadline) fail("tcautores.batch_transfer_failed");
                return;
            }
            clearTransfer();
            penMoved = false;
            ResearchNoteGenerationController.Result result = pendingGenerationResult;
            pendingGenerationResult = null;
            afterGeneration(result);
        }

        private void afterGeneration(ResearchNoteGenerationController.Result result) {
            if (result == null || result.reason != ResearchNoteGenerationController.EndReason.COMPLETE
                || helper.countIncompleteResearchNotes(current.key) <= 0) {
                String reasonKey = result == null ? "tcautores.generate_reason.timeout"
                    : "tcautores.generate_reason." + result.reason.name()
                        .toLowerCase();
                fail(reasonKey);
                return;
            }
            startSolve();
        }

        private void startSolve() {
            phase = Phase.SOLVING;
            BatchResearchController
                .startForKey(helper, player, mc, current.key, new BatchResearchController.Listener() {

                    @Override
                    public void onSuccess(int solved) {
                        if (active != Task.this) return;
                        if (solved <= 0 && !hasCompletedDiscovery(current.key)) {
                            fail("tcautores.target_note_missing");
                            return;
                        }
                        startAutomaticLearning();
                    }

                    @Override
                    public void onFailure(String key, int solved) {
                        if (active == Task.this) fail(key);
                    }
                }, false);
        }

        private void startAutomaticLearning() {
            int slot = helper.findCompletedResearchNoteSlot(current.key);
            if (slot == 1) {
                if (!beginTransfer(1, Phase.MOVE_DISCOVERY_OUT)) fail("tcautores.batch_transfer_failed");
            } else if (slot >= 2) {
                startSwapIn(slot);
            } else {
                fail("tcautores.target_note_missing");
            }
        }

        private void tickDiscoveryOut() {
            if (!transferAccepted()) return;
            if (helper.researchNoteStack() != null) {
                if (tick >= deadline) fail("tcautores.batch_inventory_full");
                return;
            }
            clearTransfer();
            int slot = helper.findCompletedResearchNoteSlot(current.key);
            if (slot < 2) {
                fail("tcautores.target_note_missing");
                return;
            }
            startSwapIn(slot);
        }

        private void startSwapIn(int slot) {
            if (slot == 29 + player.inventory.currentItem) {
                originalHeld = null;
                discoverySlot = -1;
                useHeldDiscovery();
                return;
            }
            originalHeld = player.getHeldItem() == null ? null
                : player.getHeldItem()
                    .copy();
            discoverySlot = slot;
            ContainerTransferController.clear();
            if (!ContainerTransferController.beginSwap(mc, player, slot, player.inventory.currentItem)) {
                fail("tcautores.target_use_failed");
                return;
            }
            phase = Phase.SWAP_DISCOVERY_IN;
            deadline = tick + TRANSFER_TIMEOUT_TICKS;
            acceptedTick = -1;
            resynchronizedTick = -1;
        }

        private void tickSwapIn() {
            if (!transferAccepted()) return;
            ItemStack held = player.getHeldItem();
            if (!ResearchNoteItems.isComplete(held) || !ResearchNoteItems.hasKey(held, current.key)) {
                if (tick >= deadline) fail("tcautores.target_use_failed");
                return;
            }
            clearTransfer();
            useHeldDiscovery();
        }

        private void useHeldDiscovery() {
            ItemStack held = player.getHeldItem();
            if (!ResearchNoteItems.isComplete(held) || !ResearchNoteItems.hasKey(held, current.key)) {
                fail("tcautores.target_use_failed");
                return;
            }
            mc.playerController.sendUseItem(player, player.worldObj, held);
            phase = Phase.WAIT_USE;
            deadline = tick + 100;
        }

        private void tickWaitUse() {
            if (!ResearchManager.isResearchComplete(player.getCommandSenderName(), current.key)) {
                if (tick >= deadline) fail("tcautores.target_use_timeout");
                return;
            }
            ItemStack held = player.getHeldItem();
            if (ResearchNoteItems.hasKey(held, current.key)) {
                if (tick >= deadline) fail("tcautores.target_use_timeout");
                return;
            }
            if (originalHeld == null) {
                completeCurrent();
                return;
            }
            ItemStack displaced = player.openContainer.getSlot(discoverySlot)
                .getStack();
            if (!ItemStack.areItemStacksEqual(originalHeld, displaced)) {
                fail("tcautores.target_held_changed");
                return;
            }
            ContainerTransferController.clear();
            if (!ContainerTransferController.beginSwap(mc, player, discoverySlot, player.inventory.currentItem)) {
                fail("tcautores.target_use_failed");
                return;
            }
            phase = Phase.SWAP_HELD_BACK;
            deadline = tick + TRANSFER_TIMEOUT_TICKS;
            acceptedTick = -1;
            resynchronizedTick = -1;
        }

        private void tickSwapBack() {
            if (!transferAccepted()) return;
            if (!ItemStack.areItemStacksEqual(originalHeld, player.getHeldItem())) {
                if (tick >= deadline) fail("tcautores.target_held_changed");
                return;
            }
            clearTransfer();
            completeCurrent();
        }

        private void completeCurrent() {
            completed++;
            current = null;
            originalHeld = null;
            discoverySlot = -1;
            phase = gui == null ? Phase.WAIT_GUI : Phase.ADVANCE;
        }

        private boolean beginTransfer(int slot, Phase nextPhase) {
            ContainerTransferController.clear();
            if (!ContainerTransferController.begin(mc, player, slot)) return false;
            phase = nextPhase;
            deadline = tick + TRANSFER_TIMEOUT_TICKS;
            acceptedTick = -1;
            resynchronizedTick = -1;
            return true;
        }

        private boolean transferAccepted() {
            if (!ContainerTransferController.matchesOpenContainer(player)) {
                fail("tcautores.batch_container_changed");
                return false;
            }
            ContainerTransferController.Status status = ContainerTransferController.status();
            if (status == ContainerTransferController.Status.REJECTED) {
                if (tick >= deadline) fail("tcautores.batch_transfer_rejected");
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
                    fail("tcautores.batch_transfer_rejected");
                    return false;
                }
                acceptedTick = -1;
                resynchronizedTick = -1;
                deadline = tick + TRANSFER_TIMEOUT_TICKS;
                return false;
            }
            if (status == ContainerTransferController.Status.ACCEPTED) {
                if (acceptedTick < 0) {
                    acceptedTick = tick;
                    deadline = Math.max(deadline, tick + TRANSFER_TIMEOUT_TICKS);
                    return false;
                }
                return ContainerTransferController.hasSettled(tick, acceptedTick);
            }
            if (tick >= deadline) fail("tcautores.batch_transfer_timeout");
            return false;
        }

        private void clearTransfer() {
            ContainerTransferController.clear();
            acceptedTick = -1;
            resynchronizedTick = -1;
            deadline = 0;
        }

        private void finish() {
            if (active != this) return;
            active = null;
            ContainerTransferController.clear();
            String message = String
                .format(StatCollector.translateToLocal("tcautores.target_complete"), completed, total, targetKey);
            PlayerNotifications.addNotification(message);
            player.addChatMessage(new ChatComponentText(message));
        }

        private void fail(String key) {
            failReason(StatCollector.translateToLocal(key));
        }

        private void failReason(String reason) {
            if (active != this) return;
            active = null;
            ResearchNoteGenerationController.cancel();
            BatchResearchController.cancel();
            ContainerTransferController.clear();
            ResearchSolveController.cancel();
            String message = StatCollector.translateToLocal("tcautores.target_failed") + ": " + reason;
            PlayerNotifications.addNotification(message);
            player.addChatMessage(new ChatComponentText(message));
        }
    }
}

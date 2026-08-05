package com.Emil.TCAutoResearch;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.StatCollector;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.client.lib.PlayerNotifications;
import thaumcraft.common.Thaumcraft;

public final class AspectSynthesisController {

    private static final int ACK_TIMEOUT_TICKS = 40;
    private static final int DISCOVERY_TIMEOUT_TICKS = 100;
    private static final int MAX_RETRIES = 3;

    private static Task active;

    private AspectSynthesisController() {}

    public static void synthesize(GuiResearchTableHelperInterface helper, EntityPlayer player, Aspect target,
        int amount) {
        if (target == null || amount <= 0) {
            PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.invalid_amount"));
            return;
        }
        ResearchSolveController.cancel();
        AspectList inventory = helper.availableAspects();
        active = Task.synthesis(helper, player, target, inventory.getAmount(target) + amount);
        PlayerNotifications.addNotification(
            String.format(
                StatCollector.translateToLocal("tcautores.synthesis_started"),
                AspectLocalization.name(target)));
    }

    public static void discoverAll(GuiResearchTableHelperInterface helper, EntityPlayer player, Runnable onComplete) {
        ResearchSolveController.cancel();
        active = Task.discovery(helper, player, onComplete);
        PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.discovery_started"));
    }

    public static void clientTick() {
        Task current = active;
        if (current != null) current.tick();
    }

    public static boolean isRunning() {
        return active != null;
    }

    public static void cancel() {
        active = null;
    }

    private static final class Task {

        final GuiResearchTableHelperInterface helper;
        final EntityPlayer player;
        final boolean discoverAll;
        final Runnable onComplete;
        Aspect target;
        int desiredAmount;
        Aspect pendingAspect;
        int pendingAmount;
        int tick;
        int deadline;
        int retries;
        int discoveryDeadline;

        private Task(GuiResearchTableHelperInterface helper, EntityPlayer player, boolean discoverAll, Aspect target,
            int desiredAmount, Runnable onComplete) {
            this.helper = helper;
            this.player = player;
            this.discoverAll = discoverAll;
            this.target = target;
            this.desiredAmount = desiredAmount;
            this.onComplete = onComplete;
        }

        static Task synthesis(GuiResearchTableHelperInterface helper, EntityPlayer player, Aspect target,
            int desiredAmount) {
            return new Task(helper, player, false, target, desiredAmount, null);
        }

        static Task discovery(GuiResearchTableHelperInterface helper, EntityPlayer player, Runnable onComplete) {
            return new Task(helper, player, true, null, 0, onComplete);
        }

        void tick() {
            tick++;
            AspectList inventory = helper.availableAspects();
            if (pendingAspect != null) {
                if (inventory.getAmount(pendingAspect) > pendingAmount) {
                    pendingAspect = null;
                    retries = 0;
                } else if (tick >= deadline) {
                    if (retries >= MAX_RETRIES) {
                        fail("tcautores.synthesis_timeout", pendingAspect);
                        return;
                    }
                    sendCombination(pendingAspect, inventory);
                } else {
                    return;
                }
            }

            if (discoverAll) {
                if (target != null && discovered(target)) target = null;
                if (target == null) {
                    target = nextUndiscovered();
                    if (target == null) {
                        succeed("tcautores.discovery_complete", null);
                        return;
                    }
                    desiredAmount = Math.max(1, inventory.getAmount(target) + 1);
                    discoveryDeadline = 0;
                }
            }

            if (inventory.getAmount(target) >= desiredAmount) {
                if (discoverAll && !discovered(target)) {
                    if (discoveryDeadline == 0) discoveryDeadline = tick + DISCOVERY_TIMEOUT_TICKS;
                    if (tick < discoveryDeadline) return;
                    fail("tcautores.discovery_timeout", target);
                    return;
                }
                succeed("tcautores.synthesis_complete", target);
                return;
            }

            AspectSynthesisPlanner.Step step = AspectSynthesisPlanner.next(target, inventory);
            if (step.craft == null) {
                fail("tcautores.synthesis_missing", step.missing == null ? target : step.missing);
                return;
            }
            pendingAspect = step.craft;
            retries = 0;
            sendCombination(step.craft, inventory);
        }

        private void sendCombination(Aspect aspect, AspectList inventory) {
            Aspect[] components = aspect.getComponents();
            if (components == null) {
                fail("tcautores.synthesis_missing", aspect);
                return;
            }
            pendingAmount = inventory.getAmount(aspect);
            retries++;
            deadline = tick + ACK_TIMEOUT_TICKS;
            helper.combine(components[0], components[1]);
        }

        private Aspect nextUndiscovered() {
            for (Object value : Aspect.aspects.values()) {
                Aspect aspect = (Aspect) value;
                if (!discovered(aspect)) return aspect;
            }
            return null;
        }

        private boolean discovered(Aspect aspect) {
            return Thaumcraft.proxy.getPlayerKnowledge()
                .hasDiscoveredAspect(player.getCommandSenderName(), aspect);
        }

        private void succeed(String key, Aspect aspect) {
            active = null;
            if (onComplete != null) onComplete.run();
            String message = StatCollector.translateToLocal(key);
            PlayerNotifications
                .addNotification(aspect == null ? message : String.format(message, AspectLocalization.name(aspect)));
        }

        private void fail(String key, Aspect aspect) {
            active = null;
            String message = String.format(StatCollector.translateToLocal(key), AspectLocalization.name(aspect));
            PlayerNotifications.addNotification(message, aspect);
        }
    }
}

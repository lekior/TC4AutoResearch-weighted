package com.Emil.TCAutoResearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;

import thaumcraft.api.research.ResearchItem;
import thaumcraft.common.lib.research.ResearchManager;

public final class ResearchPlan {

    public enum Action {
        COMPLETED,
        LEARN_DISCOVERY,
        SOLVE_EXISTING,
        GENERATE_AND_SOLVE,
        WAIT_FOR_PREREQUISITES,
        HIDDEN,
        DIRECT,
        UNSUPPORTED
    }

    public static final class Entry {

        public final ResearchItem research;
        public final Action action;
        public final boolean target;

        private Entry(ResearchItem research, Action action, boolean target) {
            this.research = research;
            this.action = action;
            this.target = target;
        }
    }

    public final ResearchItem target;
    public final List<Entry> entries;

    private ResearchPlan(ResearchItem target, List<Entry> entries) {
        this.target = target;
        this.entries = Collections.unmodifiableList(entries);
    }

    public static ResearchPlan create(EntityPlayer player, GuiResearchTableHelperInterface helper,
        ResearchItem target) {
        if (player == null || helper == null || target == null)
            return new ResearchPlan(target, Collections.emptyList());
        String username = player.getCommandSenderName();
        List<String> completedResearch = ResearchManager.getResearchForPlayerSafe(username);
        Set<String> completed = completedResearch == null ? Collections.emptySet() : new HashSet<>(completedResearch);
        List<Entry> result = new ArrayList<>();
        for (ResearchItem research : ResearchCatalog.prerequisitePlan(target, true)) {
            result.add(
                new Entry(research, classify(helper, username, completed, research), research.key.equals(target.key)));
        }
        return new ResearchPlan(target, result);
    }

    public int count(Action action) {
        int count = 0;
        for (Entry entry : entries) if (entry.action == action) count++;
        return count;
    }

    public boolean canExecute() {
        return count(Action.HIDDEN) == 0 && count(Action.DIRECT) == 0 && count(Action.UNSUPPORTED) == 0;
    }

    private static Action classify(GuiResearchTableHelperInterface helper, String username, Set<String> completed,
        ResearchItem research) {
        if (completed.contains(research.key)) return Action.COMPLETED;
        if (!ResearchCatalog.isRevealed(research, completed)) return Action.HIDDEN;
        if (helper.findCompletedResearchNoteSlot(research.key) >= 0) return Action.LEARN_DISCOVERY;
        if (helper.countIncompleteResearchNotes(research.key) > 0) return Action.SOLVE_EXISTING;
        if (research.isVirtual() || research.isStub()
            || research.isAutoUnlock()
            || research.tags == null
            || research.tags.size() == 0
            || research.getResearchPrimaryTag() == null) return Action.UNSUPPORTED;
        if (ResearchCatalog.completesDirectly(research, thaumcraft.common.config.Config.researchDifficulty))
            return Action.DIRECT;
        if (!ResearchManager.doesPlayerHaveRequisites(username, research.key)) return Action.WAIT_FOR_PREREQUISITES;
        return Action.GENERATE_AND_SOLVE;
    }
}

package com.Emil.TCAutoResearch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;

import thaumcraft.api.research.ResearchCategories;
import thaumcraft.api.research.ResearchCategoryList;
import thaumcraft.api.research.ResearchItem;
import thaumcraft.common.lib.research.ResearchManager;

public final class ResearchCatalog {

    public enum Scope {
        CURRENT_CATEGORY,
        ALL_CATEGORIES
    }

    public enum Status {
        READY,
        HAS_NOTE,
        LOCKED,
        DIRECT
    }

    public static final class Entry {

        public final ResearchItem research;
        public final Status status;

        private Entry(ResearchItem research, Status status) {
            this.research = research;
            this.status = status;
        }
    }

    private static volatile String currentCategory;

    private ResearchCatalog() {}

    public static void setCurrentCategory(String category) {
        if (category != null && ResearchCategories.getResearchList(category) != null) currentCategory = category;
    }

    public static String currentCategory() {
        String category = currentCategory;
        if (category != null && ResearchCategories.getResearchList(category) != null) return category;
        return ResearchCategories.researchCategories.isEmpty() ? null
            : ResearchCategories.researchCategories.keySet()
                .iterator()
                .next();
    }

    public static List<Entry> entries(EntityPlayer player, Scope scope, String category) {
        if (player == null) return Collections.emptyList();
        String username = player.getCommandSenderName();
        List<String> completedList = ResearchManager.getResearchForPlayerSafe(username);
        Set<String> completed = completedList == null ? Collections.emptySet() : new HashSet<>(completedList);
        List<Entry> result = new ArrayList<>();
        for (String categoryKey : ResearchCategories.researchCategories.keySet()) {
            if (scope == Scope.CURRENT_CATEGORY && !categoryKey.equals(category)) continue;
            ResearchCategoryList researchCategory = ResearchCategories.getResearchList(categoryKey);
            if (researchCategory == null) continue;
            for (ResearchItem research : researchCategory.research.values()) {
                boolean requisites = ResearchManager.doesPlayerHaveRequisites(username, research.key);
                boolean hasNote = ResearchManager.getResearchSlot(player, research.key) >= 0;
                Status status = classify(
                    research,
                    completed,
                    hasNote,
                    requisites,
                    thaumcraft.common.config.Config.researchDifficulty);
                if (status != null) result.add(new Entry(research, status));
            }
        }
        Map<String, Integer> depths = new HashMap<>();
        for (Entry entry : result) dependencyDepth(entry.research, depths, new HashSet<>());
        result.sort(
            Comparator.comparingInt((Entry entry) -> depths.get(entry.research.key))
                .thenComparing(entry -> entry.research.category)
                .thenComparingInt(entry -> entry.research.displayRow)
                .thenComparingInt(entry -> entry.research.displayColumn)
                .thenComparing(entry -> entry.research.key));
        return result;
    }

    private static int dependencyDepth(ResearchItem research, Map<String, Integer> memo, Set<String> visiting) {
        Integer cached = memo.get(research.key);
        if (cached != null) return cached;
        if (!visiting.add(research.key)) return 0;
        int depth = 0;
        depth = Math.max(depth, parentDepth(research.parents, memo, visiting));
        depth = Math.max(depth, parentDepth(research.parentsHidden, memo, visiting));
        visiting.remove(research.key);
        memo.put(research.key, depth);
        return depth;
    }

    private static int parentDepth(String[] parents, Map<String, Integer> memo, Set<String> visiting) {
        int depth = 0;
        if (parents == null) return depth;
        for (String key : parents) {
            ResearchItem parent = ResearchCategories.getResearch(key);
            if (parent != null) depth = Math.max(depth, dependencyDepth(parent, memo, visiting) + 1);
        }
        return depth;
    }

    public static List<ResearchItem> generatable(EntityPlayer player, Scope scope, String category) {
        List<ResearchItem> result = new ArrayList<>();
        for (Entry entry : entries(player, scope, category)) {
            if (entry.status == Status.READY) result.add(entry.research);
        }
        return result;
    }

    public static List<ResearchItem> actionable(EntityPlayer player, Scope scope, String category) {
        List<ResearchItem> result = new ArrayList<>();
        for (Entry entry : entries(player, scope, category)) {
            if (entry.status == Status.READY || entry.status == Status.HAS_NOTE) result.add(entry.research);
        }
        return result;
    }

    public static List<ResearchItem> prerequisitePlan(ResearchItem target, boolean includePrerequisites) {
        if (target == null) return Collections.emptyList();
        if (!includePrerequisites) return Collections.singletonList(target);
        List<ResearchItem> result = new ArrayList<>();
        appendPrerequisites(target, result, new HashSet<>(), new HashSet<>());
        return result;
    }

    static List<String> missingPrerequisiteKeys(ResearchItem research, Collection<String> completed) {
        if (research == null) return Collections.emptyList();
        Set<String> completedKeys = completed == null ? Collections.emptySet() : new HashSet<>(completed);
        List<String> missing = new ArrayList<>();
        appendMissing(research.parents, completedKeys, missing);
        appendMissing(research.parentsHidden, completedKeys, missing);
        return missing;
    }

    private static void appendMissing(String[] parents, Set<String> completed, List<String> missing) {
        if (parents == null) return;
        for (String key : parents) {
            if (key != null && !completed.contains(key) && !missing.contains(key)) missing.add(key);
        }
    }

    private static void appendPrerequisites(ResearchItem research, List<ResearchItem> result, Set<String> visiting,
        Set<String> added) {
        if (research == null || added.contains(research.key) || !visiting.add(research.key)) return;
        appendParents(research.parents, result, visiting, added);
        appendParents(research.parentsHidden, result, visiting, added);
        visiting.remove(research.key);
        if (added.add(research.key)) result.add(research);
    }

    private static void appendParents(String[] parents, List<ResearchItem> result, Set<String> visiting,
        Set<String> added) {
        if (parents == null) return;
        for (String parentKey : parents) {
            appendPrerequisites(ResearchCategories.getResearch(parentKey), result, visiting, added);
        }
    }

    static Status classify(ResearchItem research, Set<String> completed, boolean hasNote, boolean requisites,
        int difficulty) {
        if (research == null || completed.contains(research.key)
            || research.isVirtual()
            || research.isStub()
            || research.isAutoUnlock()) return null;
        if (!isRevealed(research, completed)) return null;
        if (!requisites) return Status.LOCKED;
        if (hasNote) return Status.HAS_NOTE;
        if (completesDirectly(research, difficulty)) return Status.DIRECT;
        if (research.tags == null || research.tags.size() == 0 || research.getResearchPrimaryTag() == null) return null;
        return Status.READY;
    }

    static boolean isRevealed(ResearchItem research, Collection<String> completed) {
        return research != null && (!research.isHidden() && !research.isLost()
            || completed != null && completed.contains("@" + research.key));
    }

    static boolean completesDirectly(ResearchItem research, int difficulty) {
        return research != null && research.tags != null
            && research.tags.size() > 0
            && (difficulty == -1 || difficulty == 0 && research.isSecondary());
    }
}

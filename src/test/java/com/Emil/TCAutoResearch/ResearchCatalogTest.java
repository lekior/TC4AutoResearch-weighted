package com.Emil.TCAutoResearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.research.ResearchCategories;
import thaumcraft.api.research.ResearchItem;

class ResearchCatalogTest {

    private static final String TEST_CATEGORY = "TCAUTO_QUEUE_TEST";

    @AfterEach
    void removeTestCategory() {
        ResearchCategories.researchCategories.remove(TEST_CATEGORY);
    }

    @Test
    void classifiesVisibleResearchWithoutBypassingThaumcraftRules() {
        ResearchItem normal = research("CATALOG_NORMAL");

        assertEquals(
            ResearchCatalog.Status.READY,
            ResearchCatalog.classify(normal, Collections.emptySet(), false, true, 1));
        assertEquals(
            ResearchCatalog.Status.LOCKED,
            ResearchCatalog.classify(normal, Collections.emptySet(), false, false, 1));
        assertEquals(
            ResearchCatalog.Status.HAS_NOTE,
            ResearchCatalog.classify(normal, Collections.emptySet(), true, true, 1));
    }

    @Test
    void hidesUnrevealedAndCompletedResearch() {
        ResearchItem hidden = research("CATALOG_HIDDEN").setHidden();
        assertNull(ResearchCatalog.classify(hidden, Collections.emptySet(), false, true, 1));

        Set<String> revealed = new HashSet<>();
        revealed.add("@CATALOG_HIDDEN");
        assertEquals(ResearchCatalog.Status.READY, ResearchCatalog.classify(hidden, revealed, false, true, 1));

        revealed.add("CATALOG_HIDDEN");
        assertNull(ResearchCatalog.classify(hidden, revealed, false, true, 1));
    }

    @Test
    void identifiesResearchThatCompletesDirectlyAtTheCurrentDifficulty() {
        ResearchItem secondary = research("CATALOG_SECONDARY").setSecondary();

        assertEquals(
            ResearchCatalog.Status.DIRECT,
            ResearchCatalog.classify(secondary, Collections.emptySet(), false, true, 0));
        assertEquals(
            ResearchCatalog.Status.READY,
            ResearchCatalog.classify(secondary, Collections.emptySet(), false, true, 1));
    }

    @Test
    void buildsParentFirstPlanAcrossNormalAndHiddenPrerequisites() {
        ResearchCategories.registerCategory(
            TEST_CATEGORY,
            new ResourceLocation("minecraft", "textures/items/paper.png"),
            new ResourceLocation("minecraft", "textures/gui/options_background.png"));
        ResearchItem root = queueResearch("QUEUE_ROOT", 0);
        ResearchItem normalParent = queueResearch("QUEUE_NORMAL", 1).setParents(root.key);
        ResearchItem hiddenParent = queueResearch("QUEUE_HIDDEN", 2).setParents(root.key);
        ResearchItem target = queueResearch("QUEUE_TARGET", 3).setParents(normalParent.key)
            .setParentsHidden(hiddenParent.key);
        ResearchCategories.addResearch(root);
        ResearchCategories.addResearch(normalParent);
        ResearchCategories.addResearch(hiddenParent);
        ResearchCategories.addResearch(target);

        assertEquals(
            java.util.Arrays.asList(root.key, normalParent.key, hiddenParent.key, target.key),
            ResearchCatalog.prerequisitePlan(target, true)
                .stream()
                .map(item -> item.key)
                .collect(Collectors.toList()));
        assertEquals(
            Collections.singletonList(target.key),
            ResearchCatalog.prerequisitePlan(target, false)
                .stream()
                .map(item -> item.key)
                .collect(Collectors.toList()));
    }

    @Test
    void reportsNormalAndHiddenMissingPrerequisitesWithoutDuplicates() {
        ResearchItem target = research("MISSING_TARGET").setParents("DONE_PARENT", "NORMAL_PARENT")
            .setParentsHidden("HIDDEN_PARENT", "NORMAL_PARENT");

        assertEquals(
            java.util.Arrays.asList("NORMAL_PARENT", "HIDDEN_PARENT"),
            ResearchCatalog.missingPrerequisiteKeys(target, Collections.singleton("DONE_PARENT")));
    }

    private static ResearchItem research(String key) {
        return new ResearchItem(
            key,
            "BASICS",
            new AspectList().add(Aspect.AIR, 1),
            0,
            0,
            1,
            (net.minecraft.item.ItemStack) null);
    }

    private static ResearchItem queueResearch(String key, int column) {
        return new ResearchItem(
            key,
            TEST_CATEGORY,
            new AspectList().add(Aspect.AIR, 1),
            column,
            0,
            1,
            (net.minecraft.item.ItemStack) null);
    }
}

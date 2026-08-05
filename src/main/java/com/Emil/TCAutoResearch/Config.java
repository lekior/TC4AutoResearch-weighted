package com.Emil.TCAutoResearch;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraftforge.common.config.Configuration;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;

public final class Config {

    public enum SolveMode {
        NORMAL,
        WEIGHTED
    }

    private static boolean autoResearch;
    private static SolveMode solveMode = SolveMode.WEIGHTED;
    private static boolean previewResult = true;
    private static boolean reportBoardPreview = true;
    private static boolean inventoryAware = true;
    private static int solveTimeoutMs = 8000;
    private static int beamWidth = 12;
    private static Configuration config;
    private static File configFilePath;

    private static final Map<String, Integer> AspectCosts = new LinkedHashMap<>();
    private static final Set<String> DisabledAspects = new LinkedHashSet<>();
    private static long revision;
    private static boolean dirty;

    private Config() {}

    public static synchronized void synchronizeConfiguration(File suggestedFile) {
        configFilePath = new File(suggestedFile.getParent(), "TCAutoResearch.cfg");
        config = new Configuration(configFilePath);
        config.load();
        autoResearch = config
            .getBoolean("AutoResearch", Configuration.CATEGORY_GENERAL, false, "Enable the Research Auto Start");
        String mode = config
            .getString("SolveMode", Configuration.CATEGORY_GENERAL, SolveMode.WEIGHTED.name(), "NORMAL or WEIGHTED");
        try {
            solveMode = SolveMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            solveMode = SolveMode.WEIGHTED;
        }
        previewResult = config.getBoolean(
            "PreviewResult",
            Configuration.CATEGORY_GENERAL,
            true,
            "Preview a solution before placing aspects");
        reportBoardPreview = config.getBoolean(
            "ReportBoardPreview",
            Configuration.CATEGORY_GENERAL,
            true,
            "Show the research board in the completion report");
        inventoryAware = config.getBoolean(
            "InventoryAware",
            Configuration.CATEGORY_GENERAL,
            true,
            "Prefer plans that use aspects currently available to the player");
        solveTimeoutMs = config.getInt(
            "SolveTimeoutMs",
            Configuration.CATEGORY_GENERAL,
            8000,
            500,
            60000,
            "Maximum weighted solver time in milliseconds");
        beamWidth = config.getInt(
            "BeamWidth",
            Configuration.CATEGORY_GENERAL,
            12,
            1,
            64,
            "Number of partial multi-anchor plans retained by the weighted solver");
        loadWeights();
        if (config.hasChanged()) {
            config.save();
        }
        dirty = false;
    }

    private static synchronized void loadWeights() {
        AspectCosts.clear();
        String[] defaults = serializeWeights(AspectWeights.wikiDefaults());
        for (String entry : config
            .get("weights", "AspectCosts", defaults, "Aspect costs as tag=cost. Unknown aspects default to 16.")
            .getStringList()) {
            int split = entry.indexOf('=');
            if (split <= 0) continue;
            try {
                AspectCosts.put(
                    AspectWeights.normalize(entry.substring(0, split)),
                    AspectWeights.clamp(
                        Integer.parseInt(
                            entry.substring(split + 1)
                                .trim())));
            } catch (NumberFormatException ignored) {}
        }
        DisabledAspects.clear();
        for (String tag : config
            .get("weights", "DisabledAspects", new String[0], "Aspect tags excluded from generated paths")
            .getStringList()) {
            DisabledAspects.add(AspectWeights.normalize(tag));
        }
        revision++;
        dirty = false;
    }

    public static synchronized void saveSolverConfiguration() {
        if (config == null) return;
        if (!dirty && !config.hasChanged()) return;
        config.get(Configuration.CATEGORY_GENERAL, "AutoResearch", false)
            .set(autoResearch);
        config.get(Configuration.CATEGORY_GENERAL, "SolveMode", SolveMode.WEIGHTED.name())
            .set(solveMode.name());
        config.get(Configuration.CATEGORY_GENERAL, "PreviewResult", true)
            .set(previewResult);
        config.get(Configuration.CATEGORY_GENERAL, "ReportBoardPreview", true)
            .set(reportBoardPreview);
        config.get(Configuration.CATEGORY_GENERAL, "InventoryAware", true)
            .set(inventoryAware);
        config.get(Configuration.CATEGORY_GENERAL, "SolveTimeoutMs", 8000)
            .set(solveTimeoutMs);
        config.get(Configuration.CATEGORY_GENERAL, "BeamWidth", 12)
            .set(beamWidth);
        config.get("weights", "AspectCosts", new String[0])
            .set(serializeWeights(AspectCosts));
        List<String> disabled = new ArrayList<>(DisabledAspects);
        Collections.sort(disabled);
        config.get("weights", "DisabledAspects", new String[0])
            .set(disabled.toArray(new String[0]));
        config.save();
        dirty = false;
    }

    public static synchronized int getAspectCost(String tag) {
        Integer value = AspectCosts.get(AspectWeights.normalize(tag));
        return value == null ? AspectWeights.wikiCost(tag) : value;
    }

    public static synchronized void setAspectCost(String tag, int value) {
        String normalized = AspectWeights.normalize(tag);
        int clamped = AspectWeights.clamp(value);
        if (getAspectCost(normalized) == clamped) return;
        AspectCosts.put(normalized, clamped);
        markSolverDirty();
    }

    public static synchronized boolean isAspectDisabled(String tag) {
        return DisabledAspects.contains(AspectWeights.normalize(tag));
    }

    public static synchronized void setAspectDisabled(String tag, boolean disabled) {
        String normalized = AspectWeights.normalize(tag);
        boolean changed = disabled ? DisabledAspects.add(normalized) : DisabledAspects.remove(normalized);
        if (changed) markSolverDirty();
    }

    public static synchronized void applyWikiDefaults() {
        if (AspectCosts.equals(AspectWeights.wikiDefaults()) && DisabledAspects.isEmpty()) return;
        AspectCosts.clear();
        AspectCosts.putAll(AspectWeights.wikiDefaults());
        DisabledAspects.clear();
        markSolverDirty();
    }

    public static synchronized void applyInventoryWeights(AspectList inventory) {
        Map<String, Integer> stock = new LinkedHashMap<>();
        for (Object value : Aspect.aspects.values()) {
            Aspect aspect = (Aspect) value;
            stock.put(aspect.getTag(), inventory.getAmount(aspect));
        }
        Map<String, Integer> allocated = AspectWeights.inventoryCosts(stock);
        if (AspectCosts.equals(allocated)) return;
        AspectCosts.clear();
        AspectCosts.putAll(allocated);
        markSolverDirty();
    }

    public static synchronized void setSolveMode(SolveMode mode) {
        if (mode == null || solveMode == mode) return;
        solveMode = mode;
        markSolverDirty();
    }

    public static synchronized void setPreviewResult(boolean enabled) {
        if (previewResult == enabled) return;
        previewResult = enabled;
        markDirty();
    }

    public static synchronized void setReportBoardPreview(boolean enabled) {
        if (reportBoardPreview == enabled) return;
        reportBoardPreview = enabled;
        markDirty();
    }

    public static synchronized void setInventoryAware(boolean enabled) {
        if (inventoryAware == enabled) return;
        inventoryAware = enabled;
        markSolverDirty();
    }

    public static synchronized boolean autoResearch() {
        return autoResearch;
    }

    public static synchronized void setAutoResearch(boolean enabled) {
        if (autoResearch == enabled) return;
        autoResearch = enabled;
        markDirty();
    }

    public static synchronized SolveMode solveMode() {
        return solveMode;
    }

    public static synchronized boolean previewResult() {
        return previewResult;
    }

    public static synchronized boolean reportBoardPreview() {
        return reportBoardPreview;
    }

    public static synchronized boolean inventoryAware() {
        return inventoryAware;
    }

    public static synchronized File configDirectory() {
        return configFilePath == null ? new File("config") : configFilePath.getParentFile();
    }

    public static synchronized WeightProfile snapshotProfile(String name) {
        return new WeightProfile(name, AspectCosts, DisabledAspects);
    }

    public static synchronized void applyProfile(WeightProfile profile) {
        Map<String, Integer> costs = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : profile.costs.entrySet()) {
            costs.put(AspectWeights.normalize(entry.getKey()), AspectWeights.clamp(entry.getValue()));
        }
        Set<String> disabled = new LinkedHashSet<>();
        for (String tag : profile.disabled) disabled.add(AspectWeights.normalize(tag));
        if (AspectCosts.equals(costs) && DisabledAspects.equals(disabled)) return;
        AspectCosts.clear();
        AspectCosts.putAll(costs);
        DisabledAspects.clear();
        DisabledAspects.addAll(disabled);
        markSolverDirty();
    }

    public static synchronized SolverSettings snapshot() {
        return new SolverSettings(
            solveMode,
            previewResult,
            inventoryAware,
            solveTimeoutMs,
            beamWidth,
            revision,
            new LinkedHashMap<>(AspectCosts),
            new LinkedHashSet<>(DisabledAspects));
    }

    private static String[] serializeWeights(Map<String, Integer> weights) {
        List<String> tags = new ArrayList<>(weights.keySet());
        Collections.sort(tags);
        String[] result = new String[tags.size()];
        for (int i = 0; i < tags.size(); i++) {
            result[i] = tags.get(i) + "=" + weights.get(tags.get(i));
        }
        return result;
    }

    private static void markDirty() {
        dirty = true;
    }

    private static void markSolverDirty() {
        dirty = true;
        revision++;
    }

    public static final class WeightProfile {

        public final String name;
        public final Map<String, Integer> costs;
        public final Set<String> disabled;

        public WeightProfile(String name, Map<String, Integer> costs, Set<String> disabled) {
            this.name = name;
            this.costs = Collections.unmodifiableMap(new LinkedHashMap<>(costs));
            this.disabled = Collections.unmodifiableSet(new LinkedHashSet<>(disabled));
        }
    }

    public static final class SolverSettings {

        public final SolveMode mode;
        public final boolean preview;
        public final boolean inventoryAware;
        public final int timeoutMs;
        public final int beamWidth;
        public final long revision;
        private final Map<String, Integer> costs;
        private final Set<String> disabled;

        private SolverSettings(SolveMode mode, boolean preview, boolean inventoryAware, int timeoutMs, int beamWidth,
            long revision, Map<String, Integer> costs, Set<String> disabled) {
            this.mode = mode;
            this.preview = preview;
            this.inventoryAware = inventoryAware;
            this.timeoutMs = timeoutMs;
            this.beamWidth = beamWidth;
            this.revision = revision;
            this.costs = Collections.unmodifiableMap(costs);
            this.disabled = Collections.unmodifiableSet(disabled);
        }

        public int cost(String tag) {
            Integer value = costs.get(AspectWeights.normalize(tag));
            return value == null ? AspectWeights.wikiCost(tag) : value;
        }

        public boolean disabled(String tag) {
            return disabled.contains(AspectWeights.normalize(tag));
        }

        public String fingerprint() {
            StringBuilder result = new StringBuilder();
            result.append(mode)
                .append('|')
                .append(inventoryAware)
                .append('|')
                .append(timeoutMs)
                .append('|')
                .append(beamWidth)
                .append('|');
            for (String entry : serializeWeights(costs)) result.append(entry)
                .append(';');
            List<String> disabledTags = new ArrayList<>(disabled);
            Collections.sort(disabledTags);
            for (String tag : disabledTags) result.append('!')
                .append(tag)
                .append(';');
            return result.toString();
        }

        SolverSettings withBeamWidth(int replacement) {
            return new SolverSettings(
                mode,
                preview,
                inventoryAware,
                timeoutMs,
                replacement,
                revision,
                new LinkedHashMap<>(costs),
                new LinkedHashSet<>(disabled));
        }
    }

}

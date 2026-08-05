package com.Emil.TCAutoResearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AspectWeights {

    public static final int FALLBACK_COST = 16;
    public static final int MAX_COST = 512;

    private static final Map<String, Integer> WIKI_DEFAULTS;

    static {
        Map<String, Integer> values = new LinkedHashMap<>();
        put(values, "Aequalitas", 512);
        put(values, "Aer", 4);
        put(values, "Alienis", 8);
        put(values, "Aqua", 2);
        put(values, "Arbor", 4);
        put(values, "Astrum", 512);
        put(values, "Auram", 64);
        put(values, "Bestia", 64);
        put(values, "Caelum", 512);
        put(values, "Cognitio", 4);
        put(values, "Corpus", 16);
        put(values, "Desidia", 64);
        put(values, "Electrum", 1);
        put(values, "Exanimis", 16);
        put(values, "Fabrico", 1);
        put(values, "Fames", 64);
        put(values, "Gelum", 64);
        put(values, "Gloria", 512);
        put(values, "Gula", 16);
        put(values, "Herba", 4);
        put(values, "Humanus", 64);
        put(values, "Ignis", 4);
        put(values, "Infernus", 64);
        put(values, "Instrumentum", 1);
        put(values, "Invidia", 512);
        put(values, "Ira", 64);
        put(values, "Iter", 4);
        put(values, "Limus", 16);
        put(values, "Lucrum", 4);
        put(values, "Lux", 8);
        put(values, "Luxuria", 512);
        put(values, "Machina", 1);
        put(values, "Magneto", 16);
        put(values, "Messis", 16);
        put(values, "Metallum", 1);
        put(values, "Meto", 64);
        put(values, "Mortuus", 64);
        put(values, "Motus", 4);
        put(values, "Nebrisum", 64);
        put(values, "Ordo", 1);
        put(values, "Pannus", 8);
        put(values, "Perditio", 2);
        put(values, "Perfodio", 64);
        put(values, "Permutatio", 16);
        put(values, "Potentia", 2);
        put(values, "Praecantatio", 8);
        put(values, "Primordium", 512);
        put(values, "Radio", 64);
        put(values, "Sano", 64);
        put(values, "Sensus", 4);
        put(values, "Spiritus", 64);
        put(values, "Strontio", 64);
        put(values, "Superbia", 64);
        put(values, "Tabernus", 64);
        put(values, "Telum", 16);
        put(values, "Tempestas", 64);
        put(values, "Tempus", 64);
        put(values, "Tenebrae", 16);
        put(values, "Terminus", 512);
        put(values, "Terra", 4);
        put(values, "Tutamen", 4);
        put(values, "Vacuos", 8);
        put(values, "Venenum", 64);
        put(values, "Vesania", 512);
        put(values, "Victus", 8);
        put(values, "Vinculum", 16);
        put(values, "Vitium", 64);
        put(values, "Vitreus", 2);
        put(values, "Volatus", 8);
        WIKI_DEFAULTS = Collections.unmodifiableMap(values);
    }

    private AspectWeights() {}

    private static void put(Map<String, Integer> values, String tag, int cost) {
        values.put(tag.toLowerCase(), cost);
    }

    public static int wikiCost(String tag) {
        Integer value = WIKI_DEFAULTS.get(normalize(tag));
        return value == null ? FALLBACK_COST : value;
    }

    public static Map<String, Integer> wikiDefaults() {
        return WIKI_DEFAULTS;
    }

    public static Map<String, Integer> inventoryCosts(Map<String, Integer> stockByTag) {
        List<Integer> positiveStock = new ArrayList<>();
        for (Integer amount : stockByTag.values()) if (amount != null && amount > 0) positiveStock.add(amount);
        Collections.sort(positiveStock);

        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : stockByTag.entrySet()) {
            int amount = entry.getValue() == null ? 0 : entry.getValue();
            result.put(normalize(entry.getKey()), percentileCost(amount, positiveStock));
        }
        return result;
    }

    private static int percentileCost(int amount, List<Integer> positiveStock) {
        if (amount <= 0 || positiveStock.isEmpty()) return 512;
        int lower = lowerBound(positiveStock, amount);
        int upper = upperBound(positiveStock, amount);
        double percentile = (lower + (upper - lower) / 2.0D) / positiveStock.size();
        int exponent = Math.max(0, Math.min(8, (int) Math.floor(9.0D * (1.0D - percentile))));
        return 1 << exponent;
    }

    private static int lowerBound(List<Integer> values, int target) {
        int low = 0;
        int high = values.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values.get(middle) < target) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static int upperBound(List<Integer> values, int target) {
        int low = 0;
        int high = values.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values.get(middle) <= target) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    public static int clamp(int value) {
        return Math.max(0, Math.min(MAX_COST, value));
    }

    public static String normalize(String tag) {
        return tag == null ? ""
            : tag.trim()
                .toLowerCase(Locale.ROOT);
    }
}

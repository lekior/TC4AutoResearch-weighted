package com.Emil.TCAutoResearch;

import net.minecraft.util.StatCollector;

public final class SolverLocalization {

    private SolverLocalization() {}

    public static String mode(Config.SolveMode mode) {
        if (mode == null) return StatCollector.translateToLocal("tcautores.mode_unavailable");
        return StatCollector.translateToLocal(
            mode == Config.SolveMode.WEIGHTED ? "tcautores.mode_weighted_value" : "tcautores.mode_normal_value");
    }

    public static String failure(String reason) {
        if (reason == null || reason.isEmpty()) return StatCollector.translateToLocal("tcautores.failure.unknown");
        String key = "tcautores.failure." + reason;
        String translated = StatCollector.translateToLocal(key);
        return key.equals(translated) ? reason : translated;
    }
}

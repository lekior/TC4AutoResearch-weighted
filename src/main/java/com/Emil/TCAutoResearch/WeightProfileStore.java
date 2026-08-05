package com.Emil.TCAutoResearch;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class WeightProfileStore {

    private static final int FORMAT_VERSION = 1;
    private static final int MAX_PROFILES = 64;
    private static final int MAX_ASPECTS = 512;
    private static final int MAX_JSON_LENGTH = 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();
    private static final Map<String, ProfileRecord> PROFILES = new LinkedHashMap<>();
    private static boolean loaded;

    private WeightProfileStore() {}

    public static synchronized List<String> names() {
        load();
        List<String> names = new ArrayList<>(PROFILES.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static synchronized String saveCurrent(String requestedName) throws IOException {
        load();
        String name = normalizeName(requestedName);
        Config.WeightProfile profile = Config.snapshotProfile(name);
        String existing = findKey(name);
        if (existing == null && PROFILES.size() >= MAX_PROFILES) throw new IllegalStateException("Too many profiles");
        if (existing != null) PROFILES.remove(existing);
        PROFILES.put(name, ProfileRecord.from(profile));
        persist();
        return name;
    }

    public static synchronized boolean apply(String requestedName) {
        load();
        ProfileRecord record = find(requestedName);
        if (record == null) return false;
        Config.applyProfile(record.toProfile());
        return true;
    }

    public static synchronized boolean delete(String requestedName) throws IOException {
        load();
        String key = findKey(requestedName);
        if (key == null) return false;
        PROFILES.remove(key);
        persist();
        return true;
    }

    public static synchronized String exportJson(String requestedName) {
        load();
        ProfileRecord record = find(requestedName);
        return record == null ? null : GSON.toJson(record);
    }

    public static synchronized String importJson(String json, String requestedName) throws IOException {
        load();
        if (json == null || json.length() > MAX_JSON_LENGTH) throw new IllegalArgumentException("Invalid profile size");
        ProfileRecord record = GSON.fromJson(json, ProfileRecord.class);
        if (record == null) throw new IllegalArgumentException("Empty profile");
        record.validate();
        record.name = requestedName == null || requestedName.trim()
            .isEmpty() ? normalizeName(record.name) : normalizeName(requestedName);
        String existing = findKey(record.name);
        if (existing == null && PROFILES.size() >= MAX_PROFILES) throw new IllegalStateException("Too many profiles");
        if (existing != null) PROFILES.remove(existing);
        PROFILES.put(record.name, record);
        persist();
        return record.name;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        File file = profileFile();
        if (!file.isFile()) return;
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            ProfileDocument document = GSON.fromJson(reader, ProfileDocument.class);
            if (document == null || document.version != FORMAT_VERSION || document.profiles == null) return;
            for (ProfileRecord record : document.profiles) {
                try {
                    record.validate();
                    record.name = normalizeName(record.name);
                    PROFILES.put(record.name, record);
                    if (PROFILES.size() >= MAX_PROFILES) break;
                } catch (RuntimeException ignored) {}
            }
        } catch (IOException | RuntimeException exception) {
            TCAutoResearch.LOG.warn("Unable to load weight profiles", exception);
        }
    }

    private static void persist() throws IOException {
        File file = profileFile();
        Path parent = file.toPath()
            .getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "TCAutoResearch-weight-profiles", ".tmp");
        ProfileDocument document = new ProfileDocument();
        document.profiles.addAll(PROFILES.values());
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(document, writer);
        }
        try {
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static File profileFile() {
        return new File(Config.configDirectory(), "TCAutoResearch-weight-profiles.json");
    }

    private static ProfileRecord find(String requestedName) {
        String key = findKey(requestedName);
        return key == null ? null : PROFILES.get(key);
    }

    private static String findKey(String requestedName) {
        if (requestedName == null) return null;
        String name = requestedName.trim();
        for (String key : PROFILES.keySet()) {
            if (key.equalsIgnoreCase(name)) return key;
        }
        return null;
    }

    private static String normalizeName(String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Profile name is empty");
        return name.length() <= 48 ? name : name.substring(0, 48);
    }

    private static final class ProfileDocument {

        int version = FORMAT_VERSION;
        List<ProfileRecord> profiles = new ArrayList<>();
    }

    private static final class ProfileRecord {

        String name;
        Map<String, Integer> costs = new LinkedHashMap<>();
        List<String> disabled = new ArrayList<>();

        static ProfileRecord from(Config.WeightProfile profile) {
            ProfileRecord record = new ProfileRecord();
            record.name = profile.name;
            record.costs.putAll(profile.costs);
            record.disabled.addAll(profile.disabled);
            Collections.sort(record.disabled);
            return record;
        }

        Config.WeightProfile toProfile() {
            Map<String, Integer> sanitizedCosts = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : costs.entrySet()) {
                sanitizedCosts.put(AspectWeights.normalize(entry.getKey()), AspectWeights.clamp(entry.getValue()));
            }
            Set<String> sanitizedDisabled = new LinkedHashSet<>();
            for (String tag : disabled) sanitizedDisabled.add(AspectWeights.normalize(tag));
            return new Config.WeightProfile(name, sanitizedCosts, sanitizedDisabled);
        }

        void validate() {
            normalizeName(name);
            if (costs == null || disabled == null || costs.size() > MAX_ASPECTS || disabled.size() > MAX_ASPECTS) {
                throw new IllegalArgumentException("Invalid profile data");
            }
            for (Map.Entry<String, Integer> entry : costs.entrySet()) {
                if (AspectWeights.normalize(entry.getKey())
                    .isEmpty() || entry.getValue() == null) {
                    throw new IllegalArgumentException("Invalid aspect cost");
                }
                entry.setValue(AspectWeights.clamp(entry.getValue()));
            }
            for (String tag : disabled) {
                if (AspectWeights.normalize(tag)
                    .isEmpty()) throw new IllegalArgumentException("Invalid disabled aspect");
            }
        }
    }
}

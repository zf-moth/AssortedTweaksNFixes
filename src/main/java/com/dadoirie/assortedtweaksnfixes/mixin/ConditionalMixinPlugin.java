package com.dadoirie.assortedtweaksnfixes.mixin;

import com.dadoirie.assortedtweaksnfixes.AssortedTweaksNFixesConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ConditionalMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = AssortedTweaksNFixesConstants.getLogger(ConditionalMixinPlugin.class);
    
    private static final Map<String, String> COUPLED_CHILDREN = new HashMap<>();
    private static final LinkedHashMap<String, Boolean> MIXIN_CONFIG = new LinkedHashMap<>();
    private static final Map<String, Boolean> MOD_CONFIG = new LinkedHashMap<>();
    
    // mixinPath -> { modId -> { min, max } }
    private static final Map<String, Map<String, VersionConstraint>> REQUIRED_MODS = new HashMap<>();
    
    // mixinPath -> enabled (from JSON)
    private static final Map<String, Boolean> MIXIN_ENABLED = new HashMap<>();
    
    private static class VersionConstraint {
        final String min;
        final String max;
        
        VersionConstraint(String min, String max) {
            this.min = min.isEmpty() ? null : min;
            this.max = max.isEmpty() ? null : max;
        }
    }

    private static boolean isModLoaded(String modId) {
        if (LoadingModList.get() != null) {
            return LoadingModList.get().getModFileById(modId) == null;
        }
        return true;
    }
    
    private static String getModVersion(String modId) {
        if (LoadingModList.get() == null) return null;
        return LoadingModList.get().getMods().stream()
            .filter(mod -> mod.getModId().equals(modId))
            .findFirst()
            .map(mod -> {
                String version = mod.getVersion().toString();
                // Extract semantic version: X.Y.Z from strings like "1.1.0-beta.53+1.21.1"
                StringBuilder result = new StringBuilder();
                int dots = 0;
                for (int i = 0; i < version.length(); i++) {
                    char c = version.charAt(i);
                    if (c == '.') {
                        dots++;
                        if (dots > 2) break;
                        result.append(c);
                    } else if (Character.isDigit(c)) {
                        result.append(c);
                    } else {
                        break;
                    }
                }
                return !result.isEmpty() ? result.toString() : version;
            })
            .orElse(null);
    }
    
    /**
     * Compares two semantic version strings.
     * @return negative if v1 < v2, 0 if equal, positive if v1 > v2
     */
    private static int compareVersions(String v1, String v2) {
        if (v1 == null || v2 == null) return 0;
        
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int p2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }
        return 0;
    }
    
    private static int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    private void loadRequiredMods() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(AssortedTweaksNFixesConstants.MOD_ID + ".mixin_requirements.json");
        if (stream == null) return;
        
        JsonObject json = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();
        for (Map.Entry<String, JsonElement> mixinEntry : json.entrySet()) {
            String mixinPath = mixinEntry.getKey();
            Map<String, VersionConstraint> modConstraints = new HashMap<>();
            
            JsonObject mixinObj = mixinEntry.getValue().getAsJsonObject();
            
            // Extract "enabled" flag (default to true if not present)
            boolean enabled = mixinObj.get("enabled").getAsBoolean();
            MIXIN_ENABLED.put(mixinPath, enabled);
            
            // Process mod entries (skip "enabled" key)
            for (Map.Entry<String, JsonElement> modEntry : mixinObj.entrySet()) {
                String key = modEntry.getKey();
                if (key.equals("enabled")) continue; // Skip the enabled flag

                JsonObject versionObj = modEntry.getValue().getAsJsonObject().getAsJsonObject("version");
                String min = versionObj.has("min") ? versionObj.get("min").getAsString() : "";
                String max = versionObj.has("max") ? versionObj.get("max").getAsString() : "";
                modConstraints.put(key, new VersionConstraint(min, max));
            }
            REQUIRED_MODS.put(mixinPath, modConstraints);
        }
    }

    private void createTomlConfig() throws IOException {
        LinkedHashMap<String, Boolean> mixinEntries = parseMixinJson();
        Path configPath = Paths.get("config", AssortedTweaksNFixesConstants.MOD_ID + "_mixins.toml");
        Files.createDirectories(configPath.getParent());

        LinkedHashMap<String, Boolean> existingConfig = new LinkedHashMap<>();
        if (Files.exists(configPath)) {
            List<String> lines = Files.readAllLines(configPath);
            for (String line : lines) {
                line = line.trim();
                if (line.contains("=") && !line.startsWith("#")) {
                    String[] parts = line.split("=", 2);
                    String key = parts[0].trim();
                    String valueStr = parts[1].trim();

                    boolean value;
                    if ("true".equalsIgnoreCase(valueStr)) {
                        value = true;
                    } else value = !"false".equalsIgnoreCase(valueStr);

                    existingConfig.put(key, value);
                }
            }
        }
        
        // Collect unique mod IDs from REQUIRED_MODS
        Set<String> modIds = new LinkedHashSet<>();
        for (Map<String, VersionConstraint> constraints : REQUIRED_MODS.values()) {
            modIds.addAll(constraints.keySet());
        }
        
        // Add mod entries if not present
        for (String modId : modIds) {
            if (!existingConfig.containsKey(modId)) {
                existingConfig.put(modId, true);
            }
        }
        
        // Add mixin entries if not present
        for (Map.Entry<String, Boolean> entry : mixinEntries.entrySet()) {
            if (!existingConfig.containsKey(entry.getKey())) {
                existingConfig.put(entry.getKey(), true);
            }
        }
        
        MIXIN_CONFIG.clear();
        MIXIN_CONFIG.putAll(existingConfig);
        MOD_CONFIG.clear();
        
        // Write config with header comment and grouped by mod
        List<String> lines = new ArrayList<>();
        lines.add("# Disabling a mod overrides all its mixin settings (mixins are skipped regardless of their individual values)");
        lines.add("");
        
        for (String modId : modIds) {
            Boolean modValue = existingConfig.get(modId);
            if (modValue != null) {
                MOD_CONFIG.put(modId, modValue);
                lines.add(modId + " = " + modValue);
            }
            
            // Write mixins for this mod
            for (String mixinPath : mixinEntries.keySet()) {
                if (mixinPath.startsWith(modId + ".")) {
                    Boolean value = existingConfig.get(mixinPath);
                    if (value != null) {
                        lines.add(mixinPath + " = " + value);
                    }
                }
            }
            lines.add("");
        }
        
        Files.write(configPath, lines);
    }
    
    private LinkedHashMap<String, Boolean> parseMixinJson() {
        LinkedHashMap<String, Boolean> mixinNames = new LinkedHashMap<>();
        InputStream mixinEntries = getClass().getClassLoader().getResourceAsStream(AssortedTweaksNFixesConstants.MOD_ID + ".mixins.json");
        assert mixinEntries != null;
        JsonObject json = JsonParser.parseReader(new InputStreamReader(mixinEntries)).getAsJsonObject();
        
        for (String key : List.of("client", "server")) {
            JsonArray mixins = json.getAsJsonArray(key);
            if (mixins != null) {
                for (JsonElement element : mixins) {
                    String mixinPath = element.getAsString();
                    
                    int dotIdx = mixinPath.indexOf('.');
                    int secondDotIdx = mixinPath.indexOf('.', dotIdx + 1);
                    if (secondDotIdx > 0) {
                        String modId = mixinPath.substring(0, dotIdx);
                        String subpackage = mixinPath.substring(dotIdx + 1, secondDotIdx);
                        
                        String matchedParent = null;
                        for (String parent : mixinNames.keySet()) {
                            if (parent.startsWith(modId + ".")) {
                                String parentName = parent.substring(modId.length() + 1);
                                if (parentName.endsWith("Mixin")) {
                                    String parentLower = parentName.substring(0, parentName.length() - 5).toLowerCase();
                                    if (parentLower.equals(subpackage)) {
                                        matchedParent = parent;
                                        break;
                                    }
                                }
                            }
                        }
                        
                        if (matchedParent != null) {
                            COUPLED_CHILDREN.put(mixinPath, matchedParent);
                        } else {
                            mixinNames.put(mixinPath, true);
                        }
                    } else {
                        mixinNames.put(mixinPath, true);
                    }
                }
            }
        }
        return mixinNames;
    }

    @Override
    public void onLoad(String mixinPackage) {
        LOGGER.info("preparing: {}", mixinPackage);
        loadRequiredMods();
        try {
            createTomlConfig();
        } catch (IOException e) {
            LOGGER.error("Failed to create TOML config", e);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        int mixinIdx = mixinClassName.indexOf(".mixin.");
        String mixinPath = mixinIdx >= 0 
            ? mixinClassName.substring(mixinIdx + ".mixin.".length()) 
            : mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        
        String parentMixin = COUPLED_CHILDREN.get(mixinPath);
        if (parentMixin != null) {
            Boolean parentEnabled = MIXIN_CONFIG.get(parentMixin);
            if (parentEnabled == null || !parentEnabled) {
                return false;
            }
            Map<String, VersionConstraint> parentRequirements = REQUIRED_MODS.get(parentMixin);
            if (parentRequirements != null) {
                for (String modId : parentRequirements.keySet()) {
                    if (isModLoaded(modId)) {
                        return false;
                    }
                }
            }
            String childClassName = mixinPath.substring(mixinPath.lastIndexOf('.') + 1);
            LOGGER.info("applying coupled child {} of parent {}", childClassName, parentMixin);
            return true;
        }
        
        Map<String, VersionConstraint> modConstraints = REQUIRED_MODS.get(mixinPath);
        if (modConstraints == null) {
            LOGGER.info("skipping {} (not in requirements json)", mixinPath);
            return false;
        }
        
        // Check if mixin is enabled in JSON
        Boolean jsonEnabled = MIXIN_ENABLED.get(mixinPath);
        if (jsonEnabled != null && !jsonEnabled) {
            LOGGER.info("skipping {} (disabled for triage)", mixinPath);
            return false;
        }
        
        // Check mod-level overrides first
        for (String modId : modConstraints.keySet()) {
            Boolean modEnabled = MOD_CONFIG.get(modId);
            if (modEnabled != null && !modEnabled) {
                LOGGER.info("skipping {} (mod '{}' disabled in config)", mixinPath, modId);
                return false;
            }
        }
        
        // Check individual mixin config
        Boolean enabled = MIXIN_CONFIG.get(mixinPath);
        if (enabled != null && !enabled) {
            LOGGER.info("skipping {} (disabled in config)", mixinPath);
            return false;
        }
        
        // Check if required mods are loaded and satisfy version constraints
        for (Map.Entry<String, VersionConstraint> entry : modConstraints.entrySet()) {
            String modId = entry.getKey();
            VersionConstraint constraint = entry.getValue();
            
            if (isModLoaded(modId)) {
                LOGGER.info("skipping {} (requires mod '{}' which is not loaded)", mixinPath, modId);
                return false;
            }
            
            String currentVersion = getModVersion(modId);
            if (currentVersion == null) {
                LOGGER.info("skipping {} (cannot determine version of mod '{}')", mixinPath, modId);
                return false;
            }
            
            // Check min version
            if (constraint.min != null && compareVersions(currentVersion, constraint.min) < 0) {
                LOGGER.info("skipping {} (mod '{}' version {} is below minimum {})", mixinPath, modId, currentVersion, constraint.min);
                return false;
            }
            
            // Check max version
            if (constraint.max != null && compareVersions(currentVersion, constraint.max) > 0) {
                LOGGER.info("skipping {} (mod '{}' version {} is above maximum {})", mixinPath, modId, currentVersion, constraint.max);
                return false;
            }
        }
        
        // Log applying with mod versions
        StringBuilder versionInfo = new StringBuilder();
        for (String modId : modConstraints.keySet()) {
            if (!versionInfo.isEmpty()) versionInfo.append(", ");
            versionInfo.append(modId).append("@").append(getModVersion(modId));
        }
        LOGGER.info("applying {} [{}]", mixinPath, versionInfo);
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    public static boolean isMixinEnabled(String mixinPath) {
        Boolean enabled = MIXIN_ENABLED.get(mixinPath);
        return enabled != null && enabled;
    }
}

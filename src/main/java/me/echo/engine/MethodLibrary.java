package me.echo.engine;

import com.google.gson.*;
import me.echo.Echo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MethodLibrary {

    // ROUTES AI SAVES DIRECTLY INTO YOUR SOURCE CODE DIRECTORY
    private static final File FILE = new File("src/main/java/me/echo/ai_created/methods/methods.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, MethodEntry> LIBRARY = new HashMap<>();
    private static final Map<String, ConstructBehavior> COMPILED_CACHE = new HashMap<>();

    public static class MethodEntry {
        public String name;
        public String shape;
        public float size;
        public String mode;
        public float yOffset;
        public float forwardOffset;
        public boolean crouchDrop;
        public String javaCode;

        public MethodEntry(String name, String shape, float size, String mode, float yOffset, float forwardOffset, boolean crouchDrop, String javaCode) {
            this.name = name;
            this.shape = shape;
            this.size = size;
            this.mode = mode;
            this.yOffset = yOffset;
            this.forwardOffset = forwardOffset;
            this.crouchDrop = crouchDrop;
            this.javaCode = javaCode;
        }
    }

    public static synchronized void init() {
        if (!FILE.exists()) {
            try {
                File dir = FILE.getParentFile();
                if (!dir.exists()) dir.mkdirs();
                saveToDisk();
            } catch (Exception e) {
                Echo.LOGGER.error("[MethodLibrary] Failed to initialize methods.json", e);
            }
            return;
        }
        loadFromDisk();
    }

    public static synchronized void saveMethod(String name, String shape, float size, String mode, float yOffset, float forwardOffset, boolean crouchDrop, String javaCode, ConstructBehavior compiledInstance) {
        String key = name.toLowerCase().trim();
        MethodEntry entry = new MethodEntry(name, shape, size, mode, yOffset, forwardOffset, crouchDrop, javaCode);
        LIBRARY.put(key, entry);
        if (compiledInstance != null) {
            COMPILED_CACHE.put(key, compiledInstance);
        }
        saveToDisk();
        Echo.LOGGER.info("[MethodLibrary] Permanently saved custom method '{}' to disk!", name);
    }

    public static synchronized MethodEntry getMethod(String name) {
        if (name == null) return null;
        return LIBRARY.get(name.toLowerCase().trim());
    }

    public static synchronized ConstructBehavior getOrCompileBehavior(String name) {
        String key = name.toLowerCase().trim();
        if (COMPILED_CACHE.containsKey(key)) {
            return COMPILED_CACHE.get(key);
        }
        MethodEntry entry = LIBRARY.get(key);
        if (entry != null && entry.javaCode != null && !entry.javaCode.isEmpty()) {
            ConstructBehavior behavior = LiveJavaEngine.compile(entry.javaCode);
            if (behavior != null) {
                COMPILED_CACHE.put(key, behavior);
                return behavior;
            }
        }
        return null;
    }

    public static synchronized List<String> getSavedMethodNames() {
        List<String> names = new ArrayList<>();
        for (MethodEntry entry : LIBRARY.values()) {
            names.add(entry.name);
        }
        return names;
    }

    private static void saveToDisk() {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(LIBRARY.values(), writer);
        } catch (Exception e) {
            Echo.LOGGER.error("[MethodLibrary] Failed to save methods.json to disk", e);
        }
    }

    private static void loadFromDisk() {
        try (Reader reader = new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement elem : array) {
                MethodEntry entry = GSON.fromJson(elem, MethodEntry.class);
                if (entry != null && entry.name != null) {
                    LIBRARY.put(entry.name.toLowerCase().trim(), entry);
                }
            }
            Echo.LOGGER.info("[MethodLibrary] Loaded {} saved custom methods from disk.", LIBRARY.size());
        } catch (Exception e) {
            Echo.LOGGER.warn("[MethodLibrary] Could not read methods.json (may be empty).");
        }
    }
}
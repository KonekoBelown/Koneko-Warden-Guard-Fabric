package com.koneko.wardenguard.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WardenGuardConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("koneko_warden_guard.json");

    private static boolean autoSummonWhenAttacked = false;

    private WardenGuardConfig() {
    }

    public static void load() {
        if (!Files.exists(PATH)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(PATH)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json != null && json.has("autoSummonWhenAttacked")) {
                autoSummonWhenAttacked = json.get("autoSummonWhenAttacked").getAsBoolean();
            }
        } catch (Exception ignored) {
            autoSummonWhenAttacked = false;
        }
    }

    public static void save() {
        JsonObject json = new JsonObject();
        json.addProperty("autoSummonWhenAttacked", autoSummonWhenAttacked);
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException ignored) {
        }
    }

    public static boolean autoSummonWhenAttacked() {
        return autoSummonWhenAttacked;
    }

    public static void setAutoSummonWhenAttacked(boolean enabled) {
        autoSummonWhenAttacked = enabled;
        save();
        syncToServer();
    }

    public static void syncToServer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand("wardenguard config auto_summon " + autoSummonWhenAttacked);
        }
    }
}

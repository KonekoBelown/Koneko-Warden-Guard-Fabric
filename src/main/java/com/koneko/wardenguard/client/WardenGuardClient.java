package com.koneko.wardenguard.client;

import com.koneko.wardenguard.WardenGuard;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class WardenGuardClient implements ClientModInitializer {
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("koneko", "warden_guard"));

    private static KeyBinding toggleKey;
    private static KeyBinding sonicKey;

    @Override
    public void onInitializeClient() {
        WardenGuardConfig.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.koneko.warden_guard.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                CATEGORY
        ));

        sonicKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.koneko.warden_guard.sonic",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                CATEGORY
        ));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(WardenGuardConfig::syncToServer));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                if (client.player != null && client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatCommand("wardenguard toggle");
                }
            }
            while (sonicKey.wasPressed()) {
                if (client.player != null && client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatCommand("wardenguard sonic");
                }
            }
        });
    }
}

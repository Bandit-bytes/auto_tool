package net.bandit.autotool.fabric.client;

import net.bandit.autotool.AutoToolMod;
import net.fabricmc.api.ClientModInitializer;

public final class AutoToolModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AutoToolMod.initClient();
        // This entrypoint is suitable for setting up client-specific logic, such as rendering.
    }
}

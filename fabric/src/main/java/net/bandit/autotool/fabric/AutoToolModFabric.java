package net.bandit.autotool.fabric;

import net.fabricmc.api.ModInitializer;

import net.bandit.autotool.AutoToolMod;

public final class AutoToolModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        AutoToolMod.init();
    }
}

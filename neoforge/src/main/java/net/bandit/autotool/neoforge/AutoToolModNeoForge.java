package net.bandit.autotool.neoforge;

import dev.architectury.utils.EnvExecutor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

import net.bandit.autotool.AutoToolMod;

@Mod(AutoToolMod.MOD_ID)
public final class AutoToolModNeoForge {
    public AutoToolModNeoForge() {
        // Run our common setup.
        AutoToolMod.init();
        EnvExecutor.runInEnv(Dist.CLIENT, ()-> AutoToolMod::initClient);
    }
}

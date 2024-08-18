package net.bandit.autotool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public final class AutoToolMod {
    public static final String MOD_ID = "auto_tool";
    public static boolean USE_BEST_TOOL = true;
    public static boolean AVOID_LOW_DURABILITY = true;
    public static float MINIMUM_DURABILITY_PERCENTAGE = 0.05f;

    private static final File CONFIG_FILE = new File(Platform.getConfigFolder().toFile(), "auto_tool_config.json");

    public static void init() {
        loadConfig();
    }

    @Environment(EnvType.CLIENT)
    public static void initClient() {
        ClientTickEvent.CLIENT_PRE.register(client -> {
            if (client.player != null && client.level != null) {
                if (Minecraft.getInstance().options.keyAttack.isDown()) {
                    handleBlockBreaking(client.player, client.level);
                }
            }
        });
    }

    private static void handleBlockBreaking(Player player, Level level) {
        HitResult hitResult = player.pick(5.0D, 0.0F, false);

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
            BlockState state = level.getBlockState(pos);

            if (!state.isAir()) {
                autoSwitchTool(player.getInventory(), state, level, pos);
            }
        }
    }

    private static void autoSwitchTool(Inventory inventory, BlockState state, Level world, BlockPos pos) {
        int bestToolIndex = -1;
        float bestSpeed = USE_BEST_TOOL ? 1.0f : Float.MAX_VALUE;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            float speed = stack.getDestroySpeed(state);
            if (AVOID_LOW_DURABILITY && isToolDurabilityLow(stack)) {
                continue;
            }

            if (USE_BEST_TOOL) {
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestToolIndex = i;
                }
            } else {
                if (speed < bestSpeed && speed > 1.0f) {
                    bestSpeed = speed;
                    bestToolIndex = i;
                }
            }
        }

        if (bestToolIndex != -1 && bestToolIndex != inventory.selected) {
            inventory.selected = bestToolIndex;

            Item item = inventory.getItem(bestToolIndex).getItem();
            String toolType = getToolTypeName(item);

            Minecraft.getInstance().player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("Switched to " + toolType), true);
        }
    }

    private static boolean isToolDurabilityLow(ItemStack stack) {
        if (stack.isDamageableItem()) {
            int durability = stack.getMaxDamage() - stack.getDamageValue();
            float durabilityPercentage = (float) durability / stack.getMaxDamage();
            return durabilityPercentage <= MINIMUM_DURABILITY_PERCENTAGE;
        }
        return false;
    }

    private static String getToolTypeName(Item item) {
        if (item instanceof net.minecraft.world.item.PickaxeItem) {
            return "Pickaxe";
        } else if (item instanceof net.minecraft.world.item.AxeItem) {
            return "Axe";
        } else if (item instanceof net.minecraft.world.item.ShovelItem) {
            return "Shovel";
        } else if (item instanceof net.minecraft.world.item.HoeItem) {
            return "Hoe";
        } else if (item instanceof net.minecraft.world.item.SwordItem) {
            return "Sword";
        } else {
            return "Tool";
        }
    }

    private static void loadConfig() {
        try {
            if (CONFIG_FILE.exists()) {
                FileReader reader = new FileReader(CONFIG_FILE);
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                reader.close();

                if (jsonObject.has("useBestTool")) {
                    USE_BEST_TOOL = jsonObject.get("useBestTool").getAsBoolean();
                }
                if (jsonObject.has("avoidLowDurability")) {
                    AVOID_LOW_DURABILITY = jsonObject.get("avoidLowDurability").getAsBoolean();
                }
                if (jsonObject.has("minimumDurabilityPercentage")) {
                    MINIMUM_DURABILITY_PERCENTAGE = jsonObject.get("minimumDurabilityPercentage").getAsFloat();
                }
            } else {
                saveConfig();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void saveConfig() {
        try {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("useBestTool", USE_BEST_TOOL);
            jsonObject.addProperty("avoidLowDurability", AVOID_LOW_DURABILITY);
            jsonObject.addProperty("minimumDurabilityPercentage", MINIMUM_DURABILITY_PERCENTAGE);

            FileWriter writer = new FileWriter(CONFIG_FILE);
            writer.write(jsonObject.toString());
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

package net.bandit.autotool;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal, safe Auto-Tool with:
 * - Spam-safe announcements
 * - Skip while in GUI or using an item
 * - Durability clamp & undamageable items OK
 * - Tie-break with correct-tool-for-drops
 * - Preferred tool mapping per block id (supports "*" suffix for prefix wildcard)
 *   Defaults:
 *     - minecraft:mushroom_stem -> AXE
 *     - minecraft:brown_mushroom_block -> AXE
 *     - minecraft:red_mushroom_block -> AXE
 *     - minecraft:bamboo -> SWORD
 *
 * Config file: config/auto_tool_config.json
 *
 * Example "preferredTools" section:
 *  "preferredTools": {
 *    "minecraft:bamboo": "SWORD",
 *    "minecraft:mushroom_stem": "AXE",
 *    "mycoolmod:rubber_wood_*": "AXE"   // wildcard prefix (matches mycoolmod:rubber_wood_log, etc.)
 *  }
 */
public final class AutoToolMod {
    public static final String MOD_ID = "auto_tool";

    public static boolean USE_BEST_TOOL = true;
    public static boolean AVOID_LOW_DURABILITY = true;
    public static float MINIMUM_DURABILITY_PERCENTAGE = 0.05f;
    public static boolean TOOL_SWAP_ENABLED = true;

    private static final File CONFIG_FILE = new File(Platform.getConfigFolder().toFile(), "auto_tool_config.json");

    private static final Map<String, ToolType> PREFERRED_TOOLS = new LinkedHashMap<>();

    private static KeyMapping toggleToolSwapKeybind;
    private static int lastAnnouncedSlot = -1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void init() {
        loadConfig();
    }

    @Environment(EnvType.CLIENT)
    public static void initClient() {
        registerKeybinds();

        ClientTickEvent.CLIENT_PRE.register(client -> {
            if (client.player == null || client.level == null) return;
            if (client.screen != null) return; // don't swap inside GUIs
            if (client.player.isUsingItem()) return; // don't swap while eating, drawing bow, etc.

            if (Minecraft.getInstance().options.keyAttack.isDown()) {
                handleBlockBreaking(client.player, client.level);
            }

            if (toggleToolSwapKeybind.consumeClick()) {
                toggleToolSwap(client.player);
            }
        });
    }

    private static void registerKeybinds() {
        toggleToolSwapKeybind = new KeyMapping("key.auto_tool.toggle_swap", GLFW.GLFW_KEY_LEFT_ALT, "key.categories.misc");
        KeyMappingRegistry.register(toggleToolSwapKeybind);
    }

    private static void toggleToolSwap(Player player) {
        TOOL_SWAP_ENABLED = !TOOL_SWAP_ENABLED;
        player.displayClientMessage(
                Component.translatable(TOOL_SWAP_ENABLED ? "message.auto_tool.enabled" : "message.auto_tool.disabled"),
                true
        );
        saveConfig();
    }

    private static void handleBlockBreaking(Player player, Level level) {
        if (!TOOL_SWAP_ENABLED) return;

        HitResult hit = player.pick(5.0D, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!state.isAir()) {
            autoSwitchTool(player.getInventory(), state);
        }
    }

    private static void autoSwitchTool(Inventory inv, BlockState state) {
        int current = inv.selected;
        ToolType preferred = getPreferredToolForBlock(state);

        int bestIdxPreferred = -1;
        float bestSpeedPreferred = -1.0f;

        int bestIdxGeneral = -1;
        float bestSpeedGeneral = USE_BEST_TOOL ? 1.0f : Float.MAX_VALUE;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (AVOID_LOW_DURABILITY && isToolDurabilityLow(stack)) continue;

            float speed = stack.getDestroySpeed(state);
            boolean correctForDrops = stack.isCorrectToolForDrops(state);
            ToolType stackType = getToolType(stack.getItem());

            if (preferred != null && stackType == preferred) {
                if (speed > bestSpeedPreferred || (speed == bestSpeedPreferred && correctForDrops)) {
                    bestSpeedPreferred = speed;
                    bestIdxPreferred = i;
                }
            }

            if (USE_BEST_TOOL) {
                if (speed > bestSpeedGeneral || (speed == bestSpeedGeneral && correctForDrops)) {
                    bestSpeedGeneral = speed;
                    bestIdxGeneral = i;
                }
            } else {
                if ((speed > 1.0f) && (speed < bestSpeedGeneral)) {
                    bestSpeedGeneral = speed;
                    bestIdxGeneral = i;
                }
            }
        }

        int chosen = (preferred != null && bestIdxPreferred != -1) ? bestIdxPreferred : bestIdxGeneral;
        if (chosen != -1 && chosen != current) {
            inv.selected = chosen;
            if (lastAnnouncedSlot != chosen && Minecraft.getInstance().player != null) {
                Item item = inv.getItem(chosen).getItem();
                String toolType = getToolTypeName(item);
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("message.auto_tool.switched", toolType), true
                );
                lastAnnouncedSlot = chosen;
            }
        }
    }

    private static ToolType getPreferredToolForBlock(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) return null;
        String key = id.toString();

        ToolType t = PREFERRED_TOOLS.get(key);
        if (t != null) return t;

        for (Map.Entry<String, ToolType> e : PREFERRED_TOOLS.entrySet()) {
            String pattern = e.getKey();
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (key.startsWith(prefix)) return e.getValue();
            }
        }
        return null;
    }

    private static boolean isToolDurabilityLow(ItemStack stack) {
        if (!stack.isDamageableItem()) return false;
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        float pct = (float) remaining / Math.max(1, stack.getMaxDamage());
        float threshold = Math.max(0f, Math.min(1f, MINIMUM_DURABILITY_PERCENTAGE));
        return pct <= threshold;
    }

    private static String getToolTypeName(Item item) {
        switch (getToolType(item)) {
            case PICKAXE: return "Pickaxe";
            case AXE:     return "Axe";
            case SHOVEL:  return "Shovel";
            case HOE:     return "Hoe";
            case SWORD:   return "Sword";
            case SHEARS:  return "Shears";
            default:      return "Tool";
        }
    }

    private static ToolType getToolType(Item item) {
        if (item instanceof PickaxeItem) return ToolType.PICKAXE;
        if (item instanceof AxeItem)     return ToolType.AXE;
        if (item instanceof ShovelItem)  return ToolType.SHOVEL;
        if (item instanceof HoeItem)     return ToolType.HOE;
        if (item instanceof SwordItem)   return ToolType.SWORD;
        if (item instanceof ShearsItem)  return ToolType.SHEARS;
        return ToolType.OTHER;
    }

    private enum ToolType {
        PICKAXE, AXE, SHOVEL, HOE, SWORD, SHEARS, OTHER;

        static ToolType fromString(String s) {
            if (s == null) return null;
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    private static void loadConfig() {
        if (PREFERRED_TOOLS.isEmpty()) {
            PREFERRED_TOOLS.put("minecraft:mushroom_stem", ToolType.AXE);
            PREFERRED_TOOLS.put("minecraft:brown_mushroom_block", ToolType.AXE);
            PREFERRED_TOOLS.put("minecraft:red_mushroom_block", ToolType.AXE);
            PREFERRED_TOOLS.put("minecraft:bamboo", ToolType.SWORD);
        }

        if (!CONFIG_FILE.exists()) {
            saveConfig();
            return;
        }

        try (InputStream in = new FileInputStream(CONFIG_FILE);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {

            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

            if (json.has("useBestTool")) USE_BEST_TOOL = json.get("useBestTool").getAsBoolean();
            if (json.has("avoidLowDurability")) AVOID_LOW_DURABILITY = json.get("avoidLowDurability").getAsBoolean();
            if (json.has("minimumDurabilityPercentage")) MINIMUM_DURABILITY_PERCENTAGE = json.get("minimumDurabilityPercentage").getAsFloat();
            if (json.has("toolSwapEnabled")) TOOL_SWAP_ENABLED = json.get("toolSwapEnabled").getAsBoolean();

            if (json.has("preferredTools") && json.get("preferredTools").isJsonObject()) {
                JsonObject prefs = json.getAsJsonObject("preferredTools");
                for (Map.Entry<String, JsonElement> e : prefs.entrySet()) {
                    String blockKey = e.getKey();
                    String val = e.getValue().getAsString();
                    if ("NONE".equalsIgnoreCase(val)) {
                        PREFERRED_TOOLS.remove(blockKey);
                    } else {
                        ToolType tt = ToolType.fromString(val);
                        if (tt != null) PREFERRED_TOOLS.put(blockKey, tt);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            saveConfig();
        }
    }

    private static void saveConfig() {
        JsonObject json = new JsonObject();
        json.addProperty("useBestTool", USE_BEST_TOOL);
        json.addProperty("avoidLowDurability", AVOID_LOW_DURABILITY);
        json.addProperty("minimumDurabilityPercentage", MINIMUM_DURABILITY_PERCENTAGE);
        json.addProperty("toolSwapEnabled", TOOL_SWAP_ENABLED);

        JsonObject prefs = new JsonObject();
        for (Map.Entry<String, ToolType> e : PREFERRED_TOOLS.entrySet()) {
            prefs.addProperty(e.getKey(), e.getValue().name());
        }
        json.add("preferredTools", prefs);

        try (OutputStream out = new FileOutputStream(CONFIG_FILE);
             OutputStreamWriter osw = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             JsonWriter writer = new JsonWriter(osw)) {
            writer.setIndent("  ");
            GSON.toJson(json, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

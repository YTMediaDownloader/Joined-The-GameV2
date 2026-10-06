package net.joinedthegame.ai;

import java.util.function.Predicate;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Inventory helpers: counting, picking tools, holding things, ranking gear. */
public final class Inv {
    private Inv() {}

    /** Slots 0-35: the hotbar and the main inventory (not armor or offhand). */
    public static final int MAIN_SLOTS = Inventory.INVENTORY_SIZE;

    public static int count(BotPlayer bot, Item item) {
        return count(bot, s -> s.is(item));
    }

    public static int count(BotPlayer bot, Predicate<ItemStack> filter) {
        Inventory inv = bot.getInventory();
        int total = 0;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && filter.test(stack)) total += stack.getCount();
        }
        return total;
    }

    public static int find(BotPlayer bot, Predicate<ItemStack> filter) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && filter.test(stack)) return i;
        }
        return -1;
    }

    public static int usedSlots(BotPlayer bot) {
        int used = 0;
        for (int i = 0; i < MAIN_SLOTS; i++) if (!bot.getInventory().getItem(i).isEmpty()) used++;
        return used;
    }

    /** Remove up to {@code amount} matching items. Returns how many were removed. */
    public static int remove(BotPlayer bot, Predicate<ItemStack> filter, int amount) {
        Inventory inv = bot.getInventory();
        int left = amount;
        for (int i = 0; i < MAIN_SLOTS && left > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !filter.test(stack)) continue;
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
        }
        inv.setChanged();
        return amount - left;
    }

    /** Put the item in {@code slot} into the bot's hand (moving it to the hotbar if needed). */
    public static void hold(BotPlayer bot, int slot) {
        if (slot < 0) return;
        Inventory inv = bot.getInventory();
        if (slot < Inventory.SELECTION_SIZE) {
            inv.setSelectedSlot(slot);
            return;
        }
        int selected = inv.getSelectedSlot();
        ItemStack inHand = inv.getItem(selected);
        inv.setItem(selected, inv.getItem(slot));
        inv.setItem(slot, inHand);
    }

    public static boolean hold(BotPlayer bot, Predicate<ItemStack> filter) {
        if (filter.test(bot.getMainHandItem())) return true;
        int slot = find(bot, filter);
        if (slot < 0) return false;
        hold(bot, slot);
        return true;
    }

    // --- keeping the bag tidy ------------------------------------------------------

    /** What kind of gear this is, for comparing like with like (null if it isn't gear). */
    public static @Nullable String gearKind(ItemStack s) {
        if (s.is(ItemTags.PICKAXES)) return "pickaxe";
        if (s.is(ItemTags.AXES)) return "axe";
        if (s.is(ItemTags.SHOVELS)) return "shovel";
        if (s.is(ItemTags.HOES)) return "hoe";
        if (s.is(ItemTags.SWORDS)) return "sword";
        if (s.is(ItemTags.SPEARS)) return "spear";
        if (s.is(Items.MACE)) return "mace";
        EquipmentSlot slot = armorSlot(s);
        return slot == null ? null : "armor_" + slot.getName();
    }

    /**
     * Gear we've got something better of (worn, or in the bag): the old stone pickaxe once
     * there's an iron one. Enchanted gear never counts as old (it might be the better one).
     */
    public static boolean isObsolete(BotPlayer bot, ItemStack stack) {
        String kind = gearKind(stack);
        if (kind == null || stack.isEnchanted()) return false;
        int value = gearValue(stack);
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack worn = bot.getItemBySlot(slot);
            if (worn != stack && kind.equals(gearKind(worn)) && gearValue(worn) > value) return true;
        }
        Inventory inv = bot.getInventory();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack other = inv.getItem(i);
            if (other == stack || other.isEmpty() || !kind.equals(gearKind(other))) continue;
            if (gearValue(other) > value || gearValue(other) == value && i < indexOf(bot, stack)) return true; // a spare
        }
        return false;
    }

    private static int indexOf(BotPlayer bot, ItemStack stack) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < MAIN_SLOTS; i++) if (inv.getItem(i) == stack) return i;
        return MAIN_SLOTS;
    }

    /**
     * Lay the hotbar out like a player would: weapon, pickaxe, axe, shovel, bow first, building
     * blocks and food at the end. Only call when nothing's being used (it moves things about).
     */
    public static void organize(BotPlayer bot) {
        Inventory inv = bot.getInventory();
        java.util.List<Predicate<ItemStack>> layout = java.util.List.of(
                s -> s.is(ItemTags.SWORDS) || s.is(Items.MACE),
                s -> s.is(ItemTags.PICKAXES),
                s -> s.is(ItemTags.AXES),
                s -> s.is(ItemTags.SHOVELS),
                s -> s.is(Items.BOW) || s.is(Items.CROSSBOW),
                s -> s.is(Items.WATER_BUCKET),
                s -> s.is(Items.TORCH),
                s -> isPlaceableBlock(s) && !s.is(ItemTags.PLANKS),
                s -> isFood(s) && !isBadFood(s));
        for (int target = 0; target < layout.size() && target < Inventory.SELECTION_SIZE; target++) {
            Predicate<ItemStack> want = layout.get(target);
            if (want.test(inv.getItem(target)) && !isObsolete(bot, inv.getItem(target))) continue;
            // The best of that kind, wherever it is (not one already laid out further left).
            int best = -1;
            int bestValue = Integer.MIN_VALUE;
            for (int i = 0; i < MAIN_SLOTS; i++) {
                if (i < target && i < Inventory.SELECTION_SIZE) continue;
                ItemStack s = inv.getItem(i);
                if (s.isEmpty() || !want.test(s)) continue;
                int v = gearValue(s) * 100 + s.getCount();
                if (v > bestValue) {
                    bestValue = v;
                    best = i;
                }
            }
            if (best < 0 || best == target) continue;
            ItemStack a = inv.getItem(target);
            inv.setItem(target, inv.getItem(best));
            inv.setItem(best, a);
        }
        inv.setChanged();
    }

    /** Hold the best tool for breaking a block. Bare hands if nothing helps. */
    public static void holdBestToolFor(BotPlayer bot, BlockState state) {
        Inventory inv = bot.getInventory();
        int best = -1;
        float bestSpeed = 1.0f;
        boolean needsTool = state.requiresCorrectToolForDrops();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (needsTool && !stack.isCorrectToolForDrops(state)) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        if (best >= 0) {
            hold(bot, best);
        } else if (isTool(bot.getMainHandItem())) {
            // Don't wear out a good tool on something it doesn't help with.
            int empty = find(bot, s -> !isTool(s));
            int emptySlot = firstEmptyHotbar(bot);
            if (emptySlot >= 0) inv.setSelectedSlot(emptySlot);
            else if (empty >= 0) hold(bot, empty);
        }
    }

    public static boolean canHarvest(BotPlayer bot, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        return find(bot, s -> s.isCorrectToolForDrops(state)) >= 0;
    }

    private static int firstEmptyHotbar(BotPlayer bot) {
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) if (bot.getInventory().getItem(i).isEmpty()) return i;
        return -1;
    }

    public static boolean isTool(ItemStack stack) {
        return stack.has(DataComponents.TOOL) || stack.has(DataComponents.WEAPON);
    }

    // --- gear ranking --------------------------------------------------------

    /** 0 = none, 1 wood/gold/leather, 2 stone, 3 copper, 4 iron/chainmail, 5 diamond, 6 netherite. */
    public static int tier(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (path.startsWith("netherite_")) return 6;
        if (path.startsWith("diamond_")) return 5;
        if (path.startsWith("iron_") || path.startsWith("chainmail_")) return 4;
        if (path.startsWith("copper_")) return 3;
        if (path.startsWith("stone_")) return 2;
        if (path.startsWith("wooden_") || path.startsWith("golden_") || path.startsWith("leather_")) return 1;
        return 1;
    }

    /**
     * How good a piece of gear is: its tier, plus its enchantments. Iron with Protection IV
     * beats plain diamond; a cursed item counts for less.
     */
    public static int gearValue(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        int value = tier(stack.getItem()) * 10;
        for (var e : stack.getEnchantments().entrySet()) {
            int level = e.getIntValue();
            value += e.getKey().is(net.minecraft.tags.EnchantmentTags.CURSE) ? -8 : level * 4;
        }
        // Nearly broken: worth less than a fresh one.
        if (stack.isDamageableItem() && stack.getMaxDamage() > 0 && stack.getDamageValue() > stack.getMaxDamage() * 0.9) value -= 6;
        return value;
    }

    public static int bestTier(BotPlayer bot, Predicate<ItemStack> kind) {
        int best = 0;
        Inventory inv = bot.getInventory();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && kind.test(stack)) best = Math.max(best, tier(stack.getItem()));
        }
        return best;
    }

    public static int pickaxeTier(BotPlayer bot) {
        return bestTier(bot, s -> s.is(ItemTags.PICKAXES));
    }

    public static int swordTier(BotPlayer bot) {
        return bestTier(bot, s -> s.is(ItemTags.SWORDS));
    }

    public static int axeTier(BotPlayer bot) {
        return bestTier(bot, s -> s.is(ItemTags.AXES));
    }

    /** Every armour slot at least this tier. */
    public static boolean fullArmor(BotPlayer bot, int tier) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (armorTier(bot, slot) < tier) return false;
        }
        return true;
    }

    /**
     * The first big milestone: iron pickaxe, sword and axe, full iron armour and a shield.
     * Until then a bot does nothing but gear up (and stay alive).
     */
    public static boolean fullIron(BotPlayer bot) {
        return pickaxeTier(bot) >= 4 && swordTier(bot) >= 4 && axeTier(bot) >= 4 && fullArmor(bot, 4)
                && (bot.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD) || count(bot, net.minecraft.world.item.Items.SHIELD) > 0);
    }

    /** Diamond pickaxe and sword and full diamond armour: nothing left to gear up for. */
    public static boolean fullDiamond(BotPlayer bot) {
        return pickaxeTier(bot) >= 5 && swordTier(bot) >= 5 && fullArmor(bot, 5);
    }

    public static @Nullable EquipmentSlot armorSlot(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) return null;
        EquipmentSlot slot = equippable.slot();
        return slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? slot : null;
    }

    public static int armorTier(BotPlayer bot, EquipmentSlot slot) {
        ItemStack worn = bot.getItemBySlot(slot);
        return worn.isEmpty() ? 0 : tier(worn.getItem());
    }

    /** Put on any armor that beats what's being worn. Returns true if something changed. */
    public static boolean equipBestArmor(BotPlayer bot) {
        boolean changed = false;
        Inventory inv = bot.getInventory();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inv.getItem(i);
            EquipmentSlot slot = armorSlot(stack);
            if (slot == null) continue;
            if (gearValue(stack) > gearValue(bot.getItemBySlot(slot))) {
                ItemStack old = bot.getItemBySlot(slot);
                bot.setItemSlot(slot, stack.copy());
                inv.setItem(i, old);
                changed = true;
            }
        }
        return changed;
    }

    /** Keep a shield in the offhand if we have one. */
    public static boolean equipShield(BotPlayer bot) {
        if (bot.getOffhandItem().is(Items.SHIELD)) return false;
        int slot = find(bot, s -> s.is(Items.SHIELD));
        if (slot < 0) return false;
        Inventory inv = bot.getInventory();
        ItemStack shield = inv.getItem(slot);
        inv.setItem(slot, bot.getOffhandItem().copy());
        bot.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, shield);
        return true;
    }

    /** The best spear we have (by tier and enchantments), or -1. */
    public static int bestSpear(BotPlayer bot) {
        int best = -1, bestValue = -1;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack s = bot.getInventory().getItem(i);
            if (!s.is(ItemTags.SPEARS)) continue;
            int v = gearValue(s);
            if (v > bestValue) {
                bestValue = v;
                best = i;
            }
        }
        return best;
    }

    /**
     * Is a spear our best melee weapon? (A spear jabs from further off but for less than a sword
     * of the same metal; it's the weapon of choice when there's no sword or axe as good.)
     */
    public static boolean spearIsBest(BotPlayer bot) {
        int spear = bestSpear(bot);
        if (spear < 0) return false;
        int spearTier = tier(bot.getInventory().getItem(spear).getItem());
        return Math.max(swordTier(bot), axeTier(bot)) < spearTier;
    }

    /** Best melee weapon slot, or -1. Swords beat axes beat nothing. */
    public static int bestWeapon(BotPlayer bot) {
        Inventory inv = bot.getInventory();
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inv.getItem(i);
            int score = 0;
            if (stack.is(ItemTags.SWORDS)) score = 100 + gearValue(stack) * 2;
            else if (stack.is(ItemTags.AXES)) score = 90 + gearValue(stack) * 2;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    // --- food -------------------------------------------------------------

    public static boolean isFood(ItemStack stack) {
        return stack.has(DataComponents.FOOD);
    }

    /** Foods a player only eats when desperate. */
    public static boolean isBadFood(ItemStack stack) {
        return stack.is(Items.ROTTEN_FLESH) || stack.is(Items.SPIDER_EYE) || stack.is(Items.POISONOUS_POTATO)
                || stack.is(Items.PUFFERFISH) || stack.is(Items.CHICKEN) || stack.is(Items.SUSPICIOUS_STEW);
    }

    public static boolean isRawMeat(ItemStack stack) {
        return stack.is(Items.BEEF) || stack.is(Items.PORKCHOP) || stack.is(Items.MUTTON) || stack.is(Items.CHICKEN)
                || stack.is(Items.RABBIT) || stack.is(Items.COD) || stack.is(Items.SALMON);
    }

    /** Best food to eat right now, or -1. */
    public static int bestFood(BotPlayer bot, boolean desperate) {
        Inventory inv = bot.getInventory();
        int best = -1;
        float bestScore = 0;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inv.getItem(i);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) continue;
            if (!desperate && isBadFood(stack)) continue;
            float score = food.nutrition() + food.saturation();
            if (isBadFood(stack)) score -= 10;
            if (best < 0 || score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    public static boolean isPlaceableBlock(ItemStack stack) {
        return stack.getItem() instanceof BlockItem
                && (stack.is(Items.DIRT) || stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)
                || stack.is(Items.NETHERRACK) || stack.is(Items.STONE) || stack.is(Items.ANDESITE)
                || stack.is(Items.DIORITE) || stack.is(Items.GRANITE) || stack.is(Items.TUFF)
                || stack.is(ItemTags.PLANKS));
    }

    /**
     * What to build a bridge or pillar out of: cobble, dirt and other junk blocks first, and
     * planks only when there's nothing else (they're wanted for crafting and building).
     */
    public static java.util.function.Predicate<ItemStack> scaffold(BotPlayer bot) {
        java.util.function.Predicate<ItemStack> junk = s -> isPlaceableBlock(s) && !s.is(ItemTags.PLANKS);
        return find(bot, junk) >= 0 ? junk : Inv::isPlaceableBlock;
    }

    /** Blocks the bot is happy to use up for bridges and pillars. */
    public static int scaffoldCount(BotPlayer bot) {
        return count(bot, Inv::isPlaceableBlock);
    }
}

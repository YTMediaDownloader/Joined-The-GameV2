package net.joinedthegame.ai;

import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What a bot considers worth carrying. Anything else gets stored at home or thrown on the
 * ground when the inventory fills up (yes, including your hay bales).
 */
public final class Junk {
    private Junk() {}

    public static boolean isKeeper(ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (stack.has(DataComponents.TOOL) || stack.has(DataComponents.WEAPON) || stack.has(DataComponents.EQUIPPABLE)) return true;
        if (stack.is(Items.SHIELD) || isWeaponItem(stack)) return true;
        if (stack.is(Items.WIND_CHARGE) || stack.is(Items.BREEZE_ROD) || stack.is(Items.ENDER_PEARL) || stack.is(Items.POTION)
                || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION) || stack.is(Items.TRIAL_KEY)
                || stack.is(Items.OMINOUS_TRIAL_KEY) || stack.is(Items.OMINOUS_BOTTLE) || stack.is(Items.HEAVY_CORE)) return true;
        if (stack.has(DataComponents.FOOD) && !Inv.isBadFood(stack)) return true;
        if (Inv.isRawMeat(stack)) return true;
        return stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || stack.is(Items.STICK)
                || stack.is(Items.COAL) || stack.is(Items.CHARCOAL)
                || stack.is(Items.RAW_IRON) || stack.is(Items.RAW_COPPER) || stack.is(Items.RAW_GOLD)
                || stack.is(Items.IRON_INGOT) || stack.is(Items.COPPER_INGOT) || stack.is(Items.GOLD_INGOT)
                || stack.is(Items.DIAMOND) || stack.is(Items.OBSIDIAN) || stack.is(Items.TORCH) || stack.is(Items.CRAFTING_TABLE)
                || stack.is(Items.FURNACE) || stack.is(ItemTags.BEDS) || stack.is(Items.CHEST) || stack.is(ItemTags.SIGNS)
                || stack.is(ItemTags.WOOL) || stack.is(Items.HAY_BLOCK) || stack.is(Items.WHEAT) || Inv.isPlaceableBlock(stack)
                || isProjectStuff(stack);
    }

    /** Things for personal projects and building: bones for a dog, seeds, fences, glass... */
    public static boolean isProjectStuff(ItemStack stack) {
        return stack.is(net.minecraft.tags.ItemTags.BUNDLES) || stack.is(Items.LEATHER) || stack.is(Items.TOTEM_OF_UNDYING) || stack.is(Items.OMINOUS_BOTTLE) || stack.is(Items.STRING) || stack.is(Items.FEATHER) || stack.is(net.minecraft.tags.ItemTags.ARROWS)
                || stack.is(Items.BOW) || stack.is(Items.CROSSBOW) || stack.is(Items.FLINT) || stack.is(Items.TRIPWIRE_HOOK)
                || stack.is(Items.BONE) || stack.is(Items.WHEAT_SEEDS) || stack.is(Items.BEETROOT_SEEDS)
                || stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET) || stack.is(Items.GLASS)
                || stack.is(ItemTags.WOODEN_FENCES) || stack.is(ItemTags.FENCE_GATES) || stack.is(ItemTags.WOODEN_DOORS)
                || stack.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item()) || stack.is(Items.SAND)
                // Enchanting: lapis, books and what they're made of, shelves, the table itself.
                || stack.is(Items.LAPIS_LAZULI) || stack.is(Items.SUGAR_CANE) || stack.is(Items.PAPER) || stack.is(Items.BOOK)
                || stack.is(Items.BOOKSHELF) || stack.is(Items.ENCHANTING_TABLE) || stack.is(Items.ENCHANTED_BOOK);
    }

    /** Items worth putting in a chest at home rather than carrying around. */
    public static boolean isStorable(ItemStack stack) {
        return !stack.isEmpty() && keepQuota(stack) < Integer.MAX_VALUE;
    }

    /**
     * How many of this a bot likes to keep on it; anything over goes in a chest at home.
     * Tools, armour, food and iron are always kept (they're needed for crafting and fights).
     */
    public static int keepQuota(ItemStack stack) {
        // The kit a bot carries about, whatever it's doing. Everything else lives in the chests
        // at home: it's put away whenever the bot's home, and fetched when a job needs it.
        if (stack.has(DataComponents.TOOL) || stack.has(DataComponents.WEAPON) || stack.has(DataComponents.EQUIPPABLE)) return Integer.MAX_VALUE;
        if (stack.is(Items.SHIELD) || stack.is(Items.BOW) || stack.is(Items.CROSSBOW) || isWeaponItem(stack)) return Integer.MAX_VALUE;
        if (stack.is(Items.WIND_CHARGE)) return 16;
        if (stack.is(Items.ENDER_PEARL)) return 16;
        if (stack.is(net.minecraft.tags.ItemTags.ARROWS)) return 64;
        if (stack.is(Items.TOTEM_OF_UNDYING) || stack.is(net.minecraft.tags.ItemTags.BUNDLES)) return Integer.MAX_VALUE;
        if (stack.has(DataComponents.FOOD) && !Inv.isBadFood(stack)) return Integer.MAX_VALUE;
        if (Inv.isRawMeat(stack)) return Integer.MAX_VALUE;
        if (stack.is(Items.TORCH)) return 32;
        if (stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET)) return 1;
        if (stack.is(ItemTags.BEDS)) return 1;
        // For mending a broken tool out in the field: a table, a few planks and sticks.
        if (stack.is(Items.CRAFTING_TABLE)) return 1;
        if (stack.is(ItemTags.PLANKS)) return 8;
        if (stack.is(Items.STICK)) return 8;
        // One stack of the everyday building block (see quotaKey): bridging, pillaring, walling in.
        if (isMainBlock(stack)) return 64;
        // Diamonds for gear are handled by the deposit (kept till it's in full diamond).
        if (stack.is(Items.DIAMOND)) return 3;
        return 0; // ores, ingots, seeds, flowers, spare stone, string... all of it goes home
    }

    /**
     * Extra kit for whatever the bot's doing right now: the job's materials stay in the bag.
     * Building: the lot. Enchanting: books, lapis, leather. A farm: seeds. A dog: bones.
     */
    static int forTheJob(BotBrain brain, ItemStack s) {
        var kind = brain.projects().current();
        if (kind != null && brain.focus() == BotBrain.Focus.PROJECT) {
            switch (kind) {
                case FARM -> {
                    if (net.joinedthegame.ai.project.Farms.isSeed(s) || s.is(ItemTags.HOES) || s.is(Items.BONE_MEAL) || s.is(ItemTags.WOODEN_FENCES)
                            || s.is(ItemTags.FENCE_GATES) || s.is(Items.WATER_BUCKET) || s.is(Items.BUCKET)) return 128;
                }
                case WOLF -> {
                    if (s.is(Items.BONE)) return 64;
                }
                case ENCHANT -> {
                    if (s.is(Items.BOOK) || s.is(Items.BOOKSHELF) || s.is(Items.LAPIS_LAZULI) || s.is(Items.LEATHER)
                            || s.is(Items.PAPER) || s.is(Items.SUGAR_CANE) || s.is(Items.OBSIDIAN) || s.is(Items.DIAMOND)
                            || s.is(Items.ENCHANTING_TABLE) || s.is(Items.GRINDSTONE) || s.is(ItemTags.PLANKS)) return 64;
                }
                case BOW -> {
                    if (s.is(Items.STRING) || s.is(Items.FEATHER) || s.is(Items.FLINT)) return 64;
                }
                case TRIAL_CHAMBER -> {
                    // The chamber kit: keys, the ominous bottle, wind charges, potions, pearls, totems.
                    if (s.is(Items.TRIAL_KEY) || s.is(Items.OMINOUS_TRIAL_KEY) || s.is(Items.OMINOUS_BOTTLE) || s.is(Items.WIND_CHARGE)
                            || s.is(Items.POTION) || s.is(Items.SPLASH_POTION) || s.is(Items.ENDER_PEARL) || s.is(Items.GOLDEN_APPLE)
                            || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.TOTEM_OF_UNDYING) || s.is(ItemTags.ARROWS)
                            || s.is(Items.WATER_BUCKET)) return 64;
                }
                case DECORATE -> {
                    if (s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item()) || s.is(Items.TORCH) || s.is(ItemTags.SAPLINGS)) return 64;
                }
                default -> {
                }
            }
        }
        return 0;
    }

    /** The everyday building block: cobble (or its deepslate twin). Netherrack over there. */
    public static boolean isMainBlock(ItemStack s) {
        return s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.NETHERRACK);
    }

    /**
     * What a keep amount counts against: building blocks all share one allowance (64 cobble
     * and 64 deepslate is two stacks of the same job); everything else counts on its own.
     */
    public static Object quotaKey(ItemStack s, boolean building) {
        if (!building && Inv.isPlaceableBlock(s) && !s.is(ItemTags.PLANKS)) return "building blocks";
        if (!building && s.is(ItemTags.PLANKS)) return "planks";
        if (!building && s.is(ItemTags.LOGS)) return "logs";
        return s.getItem();
    }

    /** As {@link #keepQuota(ItemStack, boolean)}, but on a nether trip the portal stuff stays on us. */
    public static int keepQuota(ItemStack stack, boolean building, boolean netherTrip) {
        if (netherTrip && (stack.is(Items.OBSIDIAN) || stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.GOLDEN_HELMET)
                || stack.is(Items.GOLD_INGOT) || stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET))) return Integer.MAX_VALUE;
        return keepQuota(stack, building);
    }

    /**
     * Keep amounts while building a house: the planks, logs, stone, glass and door it's
     * carrying for the build stay with it; everything else can still go in a chest.
     */
    public static int keepQuota(ItemStack stack, boolean building) {
        int normal = keepQuota(stack);
        if (!building) return normal;
        if (stack.is(ItemTags.PLANKS) || stack.is(ItemTags.LOGS) || stack.is(Items.GLASS) || stack.is(ItemTags.WOODEN_DOORS)
                || net.joinedthegame.ai.HousePlans.isRoofBlock(stack)) {
            return Math.max(normal, 192);
        }
        // The furniture we've gathered for the new place stays with us till it's in.
        if (stack.is(Items.CHEST) || stack.is(ItemTags.BEDS) || stack.is(Items.CRAFTING_TABLE) || stack.is(Items.FURNACE)
                || stack.is(ItemTags.SIGNS) || stack.is(Items.TORCH)) {
            return Math.max(normal, 16);
        }
        return normal;
    }

    /** Treasure: goes in the secret chest under the floor, not the normal one. */
    public static boolean isValuable(ItemStack stack) {
        return stack.is(Items.DIAMOND) || stack.is(Items.EMERALD) || stack.is(Items.GOLD_INGOT) || stack.is(Items.RAW_GOLD)
                || stack.is(Items.GOLD_NUGGET) || stack.is(Items.LAPIS_LAZULI) || stack.is(Items.AMETHYST_SHARD)
                || stack.is(Items.ENCHANTED_BOOK) || stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)
                || stack.is(Items.NAME_TAG) || stack.is(Items.SADDLE) || stack.is(Items.TRIAL_KEY) || stack.is(Items.OMINOUS_TRIAL_KEY) || stack.is(Items.DIAMOND_BLOCK)
                || stack.is(Items.EMERALD_BLOCK) || stack.is(Items.GOLD_BLOCK) || stack.is(ItemTags.CREEPER_DROP_MUSIC_DISCS)
                // Trial chamber treasure (from the 26.3 vault and spawner loot tables).
                || stack.is(Items.OMINOUS_BOTTLE) || stack.is(Items.HEAVY_CORE) || stack.is(Items.MUSIC_DISC_PRECIPICE)
                || stack.is(Items.MUSIC_DISC_CREATOR) || stack.is(Items.MUSIC_DISC_CREATOR_MUSIC_BOX)
                || stack.is(Items.FLOW_BANNER_PATTERN) || stack.is(Items.GUSTER_BANNER_PATTERN)
                || stack.is(Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE) || stack.is(Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE);
    }

    /** Weapons that don't say so on the tin (spears have no weapon component, unlike swords). */
    public static boolean isWeaponItem(ItemStack s) {
        return s.is(ItemTags.SPEARS) || s.is(Items.MACE) || s.is(Items.TRIDENT);
    }

    /**
     * Old gear we've replaced: wooden, stone, gold, copper and leather gets dropped (nobody
     * carries their old stone pickaxe about). Replaced iron and better is worth keeping, so
     * that goes in a chest at home instead (see {@link #keepQuota(BotPlayer, ItemStack, boolean, boolean)}).
     */
    /** Throw something out for good: not to be picked straight back up again (by us, walking over it). */
    public static void throwAway(BotPlayer bot, ItemStack stack) {
        var entity = bot.drop(stack, true, Prediction.SERVER_ONLY);
        if (entity != null) {
            entity.setThrower(bot);
            entity.setPickUpDelay(20 * 60 * 5);
        }
    }

    public static void dropObsolete(BotPlayer bot, BotBrain brain) {
        int dropped = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty() || Inv.tier(stack.getItem()) >= 4 || !Inv.isObsolete(bot, stack)) continue;
            bot.getInventory().setItem(i, ItemStack.EMPTY);
            throwAway(bot, stack);
            dropped++;
        }
        if (dropped > 0) brain.log("dropped " + dropped + " bits of old gear");
    }

    /**
     * What to keep on us of this, for this bot right now: the everyday kit, plus whatever the
     * current job needs, plus anything just fetched from a chest for a job (so it isn't put
     * straight back before it's used). Replaced gear goes in storage.
     */
    public static int keepQuota(BotPlayer bot, ItemStack stack, boolean building, boolean netherTrip) {
        BotBrain brain = bot.brain();
        if (brain != null && brain.fetchedRecently(stack.getItem())) return Integer.MAX_VALUE;
        if (Inv.isObsolete(bot, stack)) return 0;
        int q = keepQuota(stack, building, netherTrip);
        if (brain != null) q = Math.max(q, forTheJob(brain, stack));
        return q;
    }

    /** Odds and ends worth a few of, not a slot each of a full stack: flowers, saplings, seeds. */
    private static int clutterQuota(ItemStack s) {
        if (s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item())) return 4;
        if (s.is(ItemTags.SAPLINGS)) return 8;
        if (s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(Items.MELON_SEEDS) || s.is(Items.PUMPKIN_SEEDS)) return 24;
        return Integer.MAX_VALUE;
    }

    /** Join up split stacks of the same thing (they end up split after chests and crafting). */
    public static void consolidate(BotPlayer bot) {
        var inv = bot.getInventory();
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack a = inv.getItem(i);
            if (a.isEmpty() || a.getCount() >= a.getMaxStackSize()) continue;
            for (int j = i + 1; j < Inv.MAIN_SLOTS && a.getCount() < a.getMaxStackSize(); j++) {
                ItemStack b = inv.getItem(j);
                if (b.isEmpty() || !ItemStack.isSameItemSameComponents(a, b)) continue;
                int move = Math.min(a.getMaxStackSize() - a.getCount(), b.getCount());
                a.grow(move);
                b.shrink(move);
                if (b.isEmpty()) inv.setItem(j, ItemStack.EMPTY);
            }
        }
        inv.setChanged();
    }

    /**
     * Bag getting full: make room, so there's always space for what we came for (the logs off
     * the tree we just cut). In order: join split stacks; rubbish; building blocks beyond the
     * two main kinds (cobble first: nobody needs a stack each of diorite, granite and tuff);
     * clutter beyond a few (flowers, saplings, seeds). Never treasure, gear, or the iron. A
     * hoarder keeps the spare blocks and clutter to take home instead.
     */
    public static void tossIfFull(BotPlayer bot, BotBrain brain) {
        if (Inv.usedSlots(bot) < 28) return;
        consolidate(bot);
        if (Inv.usedSlots(bot) < 28) return;
        boolean hoarder = brain.personality().hoarder() >= 0.6 && brain.record().home() != null;
        // At home with chests to put it in: nothing worth keeping gets thrown out, it gets put away.
        var home = brain.record().home();
        boolean atHome = home != null && home.dimension() == bot.level().dimension() && home.pos().distSqr(bot.blockPosition()) < 48 * 48
                && !net.joinedthegame.ai.Storage.chests(bot, brain).isEmpty();
        if (atHome) hoarder = true;
        // The two main building-block kinds: cobble (or cobbled deepslate) first, then the most plentiful.
        java.util.Map<Item, Integer> blocks = new java.util.HashMap<>();
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack s = bot.getInventory().getItem(i);
            if (Inv.isPlaceableBlock(s) && !s.is(ItemTags.PLANKS)) blocks.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        java.util.List<Item> mainBlocks = blocks.entrySet().stream()
                .sorted((x, y) -> {
                    boolean cx = x.getKey() == Items.COBBLESTONE || x.getKey() == Items.COBBLED_DEEPSLATE;
                    boolean cy = y.getKey() == Items.COBBLESTONE || y.getKey() == Items.COBBLED_DEEPSLATE;
                    if (cx != cy) return cx ? -1 : 1;
                    return Integer.compare(y.getValue(), x.getValue());
                })
                .limit(1).map(java.util.Map.Entry::getKey).toList();
        java.util.Map<Item, Integer> clutterKept = new java.util.HashMap<>();
        int tossed = 0;
        int scaffold = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            boolean toss = !isKeeper(stack) && !isValuable(stack); // never throw out treasure
            // Way too much cobble and dirt: lose some. Not while building, though: that's the
            // roof and walls it's carrying. Planks never count (they're for crafting).
            // (A hoarder keeps its spare cobble to take home.)
            if (Inv.isPlaceableBlock(stack) && !stack.is(ItemTags.PLANKS) && !brain.buildingHouse() && !hoarder) {
                scaffold += stack.getCount();
                if (scaffold > 96 || !mainBlocks.contains(stack.getItem())) toss = true;
            }
            // Clutter beyond a few of each.
            int quota = clutterQuota(stack);
            if (quota < Integer.MAX_VALUE && !hoarder) {
                int kept = clutterKept.getOrDefault(stack.getItem(), 0);
                int keep = Math.max(0, Math.min(stack.getCount(), quota - kept));
                clutterKept.put(stack.getItem(), kept + keep);
                if (keep < stack.getCount()) {
                    ItemStack extra = stack.split(stack.getCount() - keep);
                    throwAway(bot, extra);
                    tossed++;
                }
            }
            if (toss) {
                bot.getInventory().setItem(i, ItemStack.EMPTY);
                throwAway(bot, stack);
                tossed++;
            }
        }
        if (tossed > 0) {
            bot.getInventory().setChanged();
            brain.log("bag's full: threw out " + tossed + " stacks of odds and ends");
        }
    }
}

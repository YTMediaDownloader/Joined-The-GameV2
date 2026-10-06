package net.joinedthegame.ai;

import java.util.function.Predicate;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Raw materials the bots know how to dig up, and where to look for them.
 *
 * @param pickTier minimum pickaxe tier needed (0 = bare hands, 1 = wood, 2 = stone, 4 = iron)
 * @param mineY    the height to dig down to when none is in sight, or {@link #SURFACE}
 */
public record Sources(String label, Predicate<BlockState> blocks, Predicate<ItemStack> yields, int pickTier, int mineY) {
    public static final int SURFACE = Integer.MIN_VALUE;

    public static final Sources LOGS = new Sources("wood",
            s -> s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES), s -> s.is(ItemTags.LOGS), 0, SURFACE);
    public static final Sources STONE = new Sources("stone",
            s -> s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLED_DEEPSLATE),
            s -> s.is(ItemTags.STONE_TOOL_MATERIALS) || s.is(ItemTags.STONE_CRAFTING_MATERIALS), 1, 50);
    public static final Sources COAL = new Sources("coal",
            s -> s.is(BlockItemTags.COAL_ORES.block()), s -> s.is(Items.COAL), 1, 44);
    public static final Sources COPPER = new Sources("copper",
            s -> s.is(BlockTags.COPPER_ORES), s -> s.is(Items.RAW_COPPER), 2, 40);
    public static final Sources IRON = new Sources("iron",
            s -> s.is(BlockTags.IRON_ORES), s -> s.is(Items.RAW_IRON), 2, 14);
    public static final Sources DIAMOND = new Sources("diamonds",
            s -> s.is(BlockItemTags.DIAMOND_ORES.block()), s -> s.is(Items.DIAMOND), 4, -54);

    /** Sand for glass. Beaches, deserts, riverbeds. */
    public static final Sources SAND = new Sources("sand",
            s -> s.is(Blocks.SAND) || s.is(Blocks.RED_SAND), s -> s.is(Items.SAND) || s.is(Items.RED_SAND), 0, SURFACE);

    /** Obsidian: diamond pickaxe only. Lava pools that met water, ruined portals. */
    public static final Sources OBSIDIAN = new Sources("obsidian",
            s -> s.is(Blocks.OBSIDIAN), s -> s.is(Items.OBSIDIAN), 5, -40);
    /** Lapis for enchanting: deep down, around the level diamonds start. */
    public static final Sources LAPIS = new Sources("lapis",
            s -> s.is(BlockItemTags.LAPIS_ORES.block()), s -> s.is(Items.LAPIS_LAZULI), 2, 0);
    /** Sugar cane, for paper (books): grows by water on the surface. */
    public static final Sources SUGAR_CANE = new Sources("sugar cane",
            s -> s.is(Blocks.SUGAR_CANE), s -> s.is(Items.SUGAR_CANE), 0, SURFACE);
    /** Nether quartz: everywhere in the netherrack, and good XP. */
    public static final Sources QUARTZ = new Sources("quartz",
            s -> s.is(Blocks.NETHER_QUARTZ_ORE), s -> s.is(Items.QUARTZ), 1, 60);
    /** Flint comes out of gravel, one time in ten. */
    public static final Sources FLINT = new Sources("gravel",
            s -> s.is(Blocks.GRAVEL), s -> s.is(Items.FLINT), 0, 40);

    /** Plain dirt, for fixing up the ground. */
    public static final Sources DIRT = new Sources("dirt",
            s -> s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK), s -> s.is(Items.DIRT), 0, SURFACE);

    /** Village hay bales: one bale is 9 wheat is 3 bread. */
    public static final Sources HAY = new Sources("hay",
            s -> s.is(Blocks.HAY_BLOCK), s -> s.is(Items.HAY_BLOCK), 0, SURFACE);
    /** Ripe crops in someone's farm (a village's, usually). */
    public static final Sources CROPS = new Sources("crops",
            s -> s.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop && crop.isMaxAge(s),
            s -> s.is(Items.WHEAT) || s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT), 0, SURFACE);

    public static @Nullable Sources forItem(Item item) {
        ItemStack stack = new ItemStack(item);
        if (stack.is(ItemTags.LOGS)) return LOGS;
        if (item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE) return STONE;
        if (item == Items.COAL) return COAL;
        if (item == Items.RAW_COPPER) return COPPER;
        if (item == Items.RAW_IRON) return IRON;
        if (item == Items.DIAMOND) return DIAMOND;
        if (item == Items.SAND || item == Items.RED_SAND) return SAND;
        if (item == Items.DIRT) return DIRT;
        if (item == Items.OBSIDIAN) return OBSIDIAN;
        if (item == Items.QUARTZ) return QUARTZ;
        if (item == Items.LAPIS_LAZULI) return LAPIS;
        if (item == Items.SUGAR_CANE) return SUGAR_CANE;
        if (item == Items.FLINT) return FLINT;
        return null;
    }

    public boolean isUnderground() {
        return this.mineY != SURFACE;
    }

    /** The pickaxe to make if we can't mine this yet. */
    public Item pickaxeNeeded() {
        return switch (this.pickTier) {
            case 0, 1 -> Items.WOODEN_PICKAXE;
            case 2, 3 -> Items.STONE_PICKAXE;
            default -> Items.IRON_PICKAXE;
        };
    }
}

package net.joinedthegame.ai.skill;

import java.util.function.Predicate;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Right-clicking: placing blocks and using things. Goes through the same server code a
 * real right-click packet does, so placement rules, block rotation, sounds and protection
 * mods all apply.
 */
public final class Placer {
    private Placer() {}

    /** Place a block from the inventory at {@code target}. Returns true if it worked. */
    public static boolean place(BotPlayer bot, BlockPos target, Predicate<ItemStack> item) {
        ServerLevel level = bot.level();
        if (!level.getBlockState(target).canBeReplaced()) return false;
        if (!Inv.hold(bot, item)) return false;
        // A dog (or a sheep) standing right where it goes: shoo it first.
        net.joinedthegame.ai.Pets.shooFrom(bot, target);

        BlockHitResult hit = findSupport(bot, target);
        if (hit == null) return false;
        ItemStack stack = bot.getMainHandItem();
        // Sneak while clicking so we place against the block instead of opening it. Not for a
        // chest against something plain (the floor): a sneak-placed chest never joins the one
        // next to it, so they'd never make double chests.
        boolean chest = stack.is(net.minecraft.world.item.Items.CHEST) || stack.is(net.minecraft.world.item.Items.TRAPPED_CHEST);
        boolean wasSneaking = bot.isShiftKeyDown();
        bot.setShiftKeyDown(!chest || isUsable(level.getBlockState(hit.getBlockPos())));
        InteractionResult result = bot.gameMode.useItemOn(bot, level, stack, InteractionHand.MAIN_HAND, hit);
        bot.setShiftKeyDown(wasSneaking);
        if (result.consumesAction()) {
            bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            bot.resetLastActionTime();
        }
        return !level.getBlockState(target).canBeReplaced();
    }

    /** Would clicking this do something (open it, ring it, sleep in it...) rather than place against it? */
    private static boolean isUsable(net.minecraft.world.level.block.state.BlockState s) {
        var b = s.getBlock();
        return s.hasBlockEntity() || b instanceof net.minecraft.world.level.block.CraftingTableBlock
                || b instanceof net.minecraft.world.level.block.DoorBlock || b instanceof net.minecraft.world.level.block.TrapDoorBlock
                || b instanceof net.minecraft.world.level.block.FenceGateBlock || b instanceof net.minecraft.world.level.block.BedBlock
                || b instanceof net.minecraft.world.level.block.ButtonBlock || b instanceof net.minecraft.world.level.block.LeverBlock
                || b instanceof net.minecraft.world.level.block.AnvilBlock || b instanceof net.minecraft.world.level.block.GrindstoneBlock
                || b instanceof net.minecraft.world.level.block.StonecutterBlock || b instanceof net.minecraft.world.level.block.CartographyTableBlock
                || b instanceof net.minecraft.world.level.block.LoomBlock || b instanceof net.minecraft.world.level.block.SmithingTableBlock;
    }

    /** A face of a neighbouring solid block we can click to put something at {@code target}. */
    public static @Nullable BlockHitResult findSupport(BotPlayer bot, BlockPos target) {
        ServerLevel level = bot.level();
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction dir : order) {
            BlockPos against = target.relative(dir);
            if (level.getBlockState(against).canBeReplaced()) continue;
            if (level.getBlockState(against).getCollisionShape(level, against).isEmpty()) continue;
            Direction face = dir.getOpposite();
            Vec3 hitVec = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
            return new BlockHitResult(hitVec, face, against, false);
        }
        return null;
    }

    /**
     * Use the held item on one face of a block: a hoe on grass, seeds on farmland. Holds the
     * first item matching {@code item} first.
     */
    public static boolean useOn(BotPlayer bot, BlockPos pos, Direction face, Predicate<ItemStack> item) {
        if (!Inv.hold(bot, item)) return false;
        ServerLevel level = bot.level();
        Vec3 hitVec = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
        lookExactly(bot, hitVec);
        boolean wasSneaking = bot.isShiftKeyDown();
        // Crouch while doing it: with a shield in the other hand, the game won't let you hoe
        // or strip anything otherwise (players do exactly the same).
        bot.setShiftKeyDown(true);
        InteractionResult result = bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(hitVec, face, pos, false));
        bot.setShiftKeyDown(wasSneaking);
        if (result.consumesAction()) bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        bot.resetLastActionTime();
        return result.consumesAction();
    }

    /**
     * Use the held item in the air while looking at {@code target}, like a bucket: the item
     * does its own ray trace from where we're looking.
     */
    public static boolean useAimedAt(BotPlayer bot, Vec3 target, Predicate<ItemStack> item) {
        if (!Inv.hold(bot, item)) return false;
        lookExactly(bot, target);
        InteractionResult result = bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (result.consumesAction()) bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        bot.resetLastActionTime();
        return result.consumesAction();
    }

    /** Right-click an entity with the held item (a bone for a wolf, meat for a hurt one). */
    public static InteractionResult interact(BotPlayer bot, net.minecraft.world.entity.Entity entity) {
        lookExactly(bot, entity.getBoundingBox().getCenter());
        InteractionResult result = bot.interactOn(entity, InteractionHand.MAIN_HAND, entity.getBoundingBox().getCenter().subtract(entity.position()));
        bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        bot.resetLastActionTime();
        return result;
    }

    /** Snap the view onto a point (items that ray trace need the aim to be exact, not eased in). */
    public static void lookExactly(BotPlayer bot, Vec3 target) {
        Vec3 d = target.subtract(bot.getEyePosition());
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, horizontal));
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        bot.setYHeadRot(yaw);
    }

    /** Right-click a block (open a chest, sleep in a bed, ...). */
    public static InteractionResult use(BotPlayer bot, BlockPos pos) {
        ServerLevel level = bot.level();
        Vec3 hitVec = Vec3.atCenterOf(pos);
        Direction face = Direction.getApproximateNearest(bot.getEyePosition().subtract(hitVec));
        BlockHitResult hit = new BlockHitResult(hitVec, face, pos, false);
        boolean wasSneaking = bot.isShiftKeyDown();
        bot.setShiftKeyDown(false);
        InteractionResult result = bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        bot.setShiftKeyDown(wasSneaking);
        bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        bot.resetLastActionTime();
        return result;
    }
}

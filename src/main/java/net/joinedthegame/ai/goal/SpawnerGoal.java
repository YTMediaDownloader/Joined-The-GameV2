package net.joinedthegame.ai.goal;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Junk;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.task.LootChest;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * A mob spawner (a dungeon's, a mineshaft's cave spider one): left alone it just keeps
 * spawning for as long as anyone's near, and a bot that only ever fights what comes out of it
 * fights for ever. Do what a player does: get to it, break it with a pickaxe (the XP's a nice
 * bonus), and then go through the dungeon's chests.
 * <p>
 * Overworld only (a nether fortress's blaze spawner is what a blaze-rod trip is there for), and
 * only when fit for it: a pickaxe, a weapon, and not hurt. The fighting is still the combat
 * goal's job, which comes first whenever something's coming.
 */
public final class SpawnerGoal extends Goal {
    private @Nullable BlockPos spawner;
    private long lastLook;
    private int ticks;
    private @Nullable Task loot;
    private final List<BlockPos> chests = new ArrayList<>();
    private boolean broken;

    public SpawnerGoal(BotBrain brain) {
        super(brain);
    }

    private boolean fit(BotPlayer bot) {
        return Inv.pickaxeTier(bot) >= 1 && Math.max(Inv.swordTier(bot), Inv.axeTier(bot)) >= 2 && bot.getHealth() >= 12;
    }

    @Override
    public int priority(BotPlayer bot) {
        if (this.broken || this.loot != null) return 49; // finishing off: the chests
        if (!fit(bot)) return 0;
        // A look about now and then (it's a block search).
        if (this.spawner == null && this.brain.now() - this.lastLook >= 60) {
            this.lastLook = this.brain.now();
            // (Dungeons are usually below you: look well down as well as round about.)
            this.spawner = Senses.findBlock(bot, s -> s.is(Blocks.SPAWNER), 12, 10, false, this.brain::isBlacklisted);
        }
        if (this.spawner != null && !bot.level().getBlockState(this.spawner).is(Blocks.SPAWNER)) this.spawner = null;
        return this.spawner == null ? 0 : 49;
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (++this.ticks > 20 * 90) return giveUp(bot);
        if (this.loot != null) {
            Task.Status s = this.loot.tick(bot, this.brain);
            if (s == Task.Status.RUNNING) return true;
            this.loot.stop(bot, this.brain);
            this.loot = null;
        }
        if (this.broken) return lootNext(bot);
        BlockPos pos = this.spawner;
        if (pos == null) return false;
        if (!bot.level().getBlockState(pos).is(Blocks.SPAWNER)) {
            // Gone: the dungeon's chests are right by it.
            this.broken = true;
            this.brain.log("broke a spawner at " + pos.toShortString());
            this.brain.remember(BotMemory.EventType.LOOTED, "a dungeon", GlobalPos.of(bot.level().dimension(), pos), 0.6f);
            findChests(bot, pos);
            return true;
        }
        if (!BlockBreaker.inReach(bot, pos)) {
            Navigator nav = this.brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToReach(bot, pos);
                if (nav.status() == Navigator.Status.FAILED) return giveUp(bot);
            }
            return true;
        }
        this.brain.navigator().stop(bot);
        int pick = Inv.find(bot, s -> s.is(net.minecraft.tags.ItemTags.PICKAXES));
        if (pick >= 0) Inv.hold(bot, pick);
        this.brain.breaker().start(pos);
        if (this.brain.breaker().tick(bot, this.brain.controls()) == BlockBreaker.Result.FAILED) return giveUp(bot);
        return true;
    }

    /** The chests round a dungeon (a couple, against its walls). */
    private void findChests(BotPlayer bot, BlockPos spawner) {
        this.chests.clear();
        for (BlockPos p : BlockPos.betweenClosed(spawner.offset(-5, -2, -5), spawner.offset(5, 2, 5))) {
            if (bot.level().getBlockEntity(p) instanceof ChestBlockEntity c && !c.isEmpty() && !this.brain.recentlyLooted(p)) {
                this.chests.add(p.immutable());
            }
        }
    }

    private boolean lootNext(BotPlayer bot) {
        while (!this.chests.isEmpty()) {
            BlockPos chest = this.chests.removeFirst();
            if (this.brain.recentlyLooted(chest)) continue;
            this.loot = new LootChest(chest, s -> Junk.isKeeper(s) || Junk.isValuable(s), "Looting a dungeon");
            return true;
        }
        this.broken = false;
        this.spawner = null;
        return false;
    }

    private boolean giveUp(BotPlayer bot) {
        if (this.spawner != null) this.brain.blacklist(this.spawner);
        this.spawner = null;
        this.broken = false;
        this.chests.clear();
        cooldown(this.brain.now(), 20 * 60);
        return false;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        if (this.loot != null) this.loot.stop(bot, this.brain);
        this.loot = null;
        this.brain.breaker().cancel(bot);
    }

    @Override
    public String activity() {
        if (this.loot != null) return this.loot.activity();
        return "Breaking a spawner";
    }
}

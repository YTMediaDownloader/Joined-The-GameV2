package net.joinedthegame.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * What's going on in a Trial Chamber, read straight from the world (the game already keeps
 * it): each trial spawner's state, each vault's type and whether it has already rewarded this
 * bot. Only for chunks that are loaded, so "what we can see from here".
 * <p>
 * How trials work in 26.3 (TrialSpawnerState, VaultServerData): a spawner waits for a player
 * in line of sight within 14 blocks, goes ACTIVE and spawns its whole wave, and only once every
 * mob it spawned is dead does it go WAITING_FOR_REWARD_EJECTION, then EJECTING_REWARD (a reward
 * per player who was there), then COOLDOWN for 30 minutes. A vault opens once per player with
 * the right key, and remembers who it has rewarded. A player with Bad Omen (from an ominous
 * bottle) that a spawner sees turns it ominous, even one cooling down after a normal trial.
 */
public final class Chambers {
    private Chambers() {}

    public record Spawner(BlockPos pos, TrialSpawnerState state, boolean ominous) {
        /** The trial's been beaten (the reward's coming, or it's cooling down after it). */
        public boolean beaten() {
            return this.state == TrialSpawnerState.WAITING_FOR_REWARD_EJECTION || this.state == TrialSpawnerState.EJECTING_REWARD
                    || this.state == TrialSpawnerState.COOLDOWN;
        }

        /** Fighting going on. */
        public boolean active() {
            return this.state == TrialSpawnerState.ACTIVE;
        }

        /** Can't spawn anything (peaceful, say): nothing to do here. */
        public boolean dead() {
            return this.state == TrialSpawnerState.INACTIVE;
        }
    }

    public record Vault(BlockPos pos, boolean ominous, boolean usedByUs, VaultState state) {
        public Item key() {
            return this.ominous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY;
        }
    }

    /** Block entities of a kind in the loaded chunks of a box. */
    private static List<BlockEntity> blockEntities(ServerLevel level, BoundingBox box) {
        List<BlockEntity> out = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (box.isInside(be.getBlockPos())) out.add(be);
                }
            }
        }
        return out;
    }

    public static List<Spawner> spawners(ServerLevel level, BoundingBox box) {
        List<Spawner> out = new ArrayList<>();
        for (BlockEntity be : blockEntities(level, box)) {
            if (!(be instanceof TrialSpawnerBlockEntity ts)) continue;
            var state = level.getBlockState(be.getBlockPos());
            boolean ominous = state.hasProperty(TrialSpawnerBlock.OMINOUS) && state.getValue(TrialSpawnerBlock.OMINOUS);
            out.add(new Spawner(be.getBlockPos(), ts.getTrialSpawner().getState(), ominous));
        }
        return out;
    }

    public static List<Vault> vaults(BotPlayer bot, BoundingBox box) {
        List<Vault> out = new ArrayList<>();
        for (BlockEntity be : blockEntities(bot.level(), box)) {
            if (!(be instanceof VaultBlockEntity vault)) continue;
            var state = bot.level().getBlockState(be.getBlockPos());
            boolean ominous = state.getValue(VaultBlock.OMINOUS);
            out.add(new Vault(be.getBlockPos(), ominous, rewarded(vault, bot.getUUID()), state.getValue(VaultBlock.STATE)));
        }
        return out;
    }

    /**
     * Has this vault already given this player its reward? (The vault keeps the list itself;
     * each bot is a real player, so one bot using it doesn't use it up for another.)
     */
    public static boolean rewarded(VaultBlockEntity vault, UUID player) {
        VaultServerData data = vault.getServerData();
        if (data == null) return false;
        Tag tag = VaultServerData.CODEC.encodeStart(NbtOps.INSTANCE, data).result().orElse(null);
        if (!(tag instanceof net.minecraft.nbt.CompoundTag c)) return false;
        Tag list = c.get("rewarded_players");
        if (list == null) return false;
        return UUIDUtil.CODEC_LINKED_SET.parse(NbtOps.INSTANCE, list).result().map(s -> s.contains(player)).orElse(false);
    }

    /**
     * The mobs of a spawner's current wave (the spawner keeps the list: the trial isn't beaten
     * till every one of them is dead, wherever it's wandered off to).
     */
    public static List<net.minecraft.world.entity.LivingEntity> waveMobs(ServerLevel level, BlockPos spawner) {
        List<net.minecraft.world.entity.LivingEntity> out = new ArrayList<>();
        if (!(level.getBlockEntity(spawner) instanceof TrialSpawnerBlockEntity ts)) return out;
        for (UUID id : ts.getTrialSpawner().getStateData().pack().currentMobs()) {
            if (level.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity e && e.isAlive()) out.add(e);
        }
        return out;
    }

    /** The chamber the bot's in (or standing at the edge of), if any. */
    public static Structures.@org.jspecify.annotations.Nullable Known here(BotPlayer bot) {
        for (Structures.Known k : Structures.near(bot, 2)) {
            if (k.kind() == Structures.Kind.TRIAL_CHAMBERS && k.contains(bot.blockPosition(), 6)) return k;
        }
        return null;
    }
}

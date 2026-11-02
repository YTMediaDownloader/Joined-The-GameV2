package net.joinedthegame.bot;

import com.mojang.authlib.GameProfile;
import net.joinedthegame.ai.BotBrain;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.portal.TeleportTransition;
import org.jspecify.annotations.Nullable;

/**
 * An AI player. It's a genuine {@link ServerPlayer}: it's in the tab list, on the locator
 * bar, gets "joined the game" and death messages, has hunger, XP and a real inventory, and
 * other mods see it as a player.
 *
 * <p>The difference is who drives it. A real player's client sends movement and actions as
 * packets. A bot has no client, so every tick its {@link BotBrain} sets the same inputs a
 * player would (walk forward, jump, look here, swing) and this class runs the player
 * physics on the server.
 */
public class BotPlayer extends ServerPlayer {
    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
        super(server, level, profile, info);
    }

    public @Nullable BotBrain brain() {
        return BotManager.get(this.level().getServer()).brain(this.getUUID());
    }

    @Override
    public void tick() {
        // Real clients tell the server when they've finished loading. Ours is always ready.
        if (!this.connection.hasClientLoaded()) this.connection.markClientLoaded();

        if (this.isDeadOrDying()) {
            // Death animation only; BotManager clicks "Respawn" (the body gets removed from
            // the world a second after death, so it can't do that itself).
            super.tick();
            this.doTick();
            return;
        }

        // Knocked back by a player last tick: take the push (the client would have).
        if (this.connection instanceof BotPacketListener l && l.pendingMotion != null) {
            this.setDeltaMovement(l.pendingMotion);
            l.pendingMotion = null;
        }
        super.tick();

        BotBrain brain = this.brain();
        if (brain != null) brain.tick(this);

        // Normally the client's movement packets drive this; for a bot the server does it.
        this.doTick();
        this.connection.resetPosition();
        // Who sees what (entity tracking) and which chunks are ours: a real client only triggers
        // this when it moves, and once a second standing still. Every bot every tick was the
        // biggest single cost the lag catcher found with 20 bots about.
        double moved = this.position().distanceToSqr(this.lastTrackX, this.lastTrackY, this.lastTrackZ);
        if (moved > 0.0001 || Math.abs(this.getYRot() - this.lastTrackYaw) > 1 || this.tickCount - this.lastTrackTick >= 20) {
            this.lastTrackX = this.getX();
            this.lastTrackY = this.getY();
            this.lastTrackZ = this.getZ();
            this.lastTrackYaw = this.getYRot();
            this.lastTrackTick = this.tickCount;
            this.level().getChunkSource().move(this);
        }
    }

    private double lastTrackX = Double.NaN, lastTrackY, lastTrackZ;
    private float lastTrackYaw;
    private int lastTrackTick;

    /** Ticks since this bot died, counted by BotManager. */
    int deadTicks;
    int respawnAfter = -1;

    /** Press "Respawn", exactly like a client does on the death screen. */
    void respawn() {
        this.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        BotBrain brain = this.brain();
        if (brain != null) brain.onDeath(this, source);
    }

    /**
     * Players are normally "client authoritative": the server trusts the client for
     * movement, landing and fall damage. Flipping this makes the server simulate all of it
     * for bots, so they take fall damage, land and make footstep sounds like anyone else.
     */
    @Override
    public boolean isClientAuthoritative() {
        return false;
    }

    @Override
    public @Nullable ServerPlayer teleport(TeleportTransition transition) {
        ServerPlayer result = super.teleport(transition);
        // A real client confirms dimension changes; do it on the bot's behalf.
        if (this.isChangingDimension()) this.hasChangedDimension();
        return result;
    }

    @Override
    public String getIpAddress() {
        return "127.0.0.1";
    }

    @Override
    public boolean allowsListing() {
        return true;
    }
}

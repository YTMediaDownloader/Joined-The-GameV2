package net.joinedthegame;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Gameplay config. The server owns it and clients are sent a copy (config/joinedthegame-synced.toml). */
public final class JtgConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MAX_BOTS = B
            .comment("How many AI players can be in the world at once.")
            .defineInRange("maxBots", 10, 0, 100);

    public static final ModConfigSpec.BooleanValue OPS_ONLY = B
            .comment("On servers, only operators can invite, kick, ban and teleport bots.",
                    "In singleplayer the world owner can always manage them.")
            .define("opsOnly", true);

    public static final ModConfigSpec.BooleanValue RIVALRIES = B
            .comment("Bots can become rivals (sworn enemies) and fight each other when they meet.")
            .define("rivalries", true);

    public static final ModConfigSpec.BooleanValue PLAYER_RIVALRIES = B
            .comment("Bots can become rivals with real players too (and fight them, or hunt them down).")
            .define("playerRivalries", true);

    public static final ModConfigSpec.BooleanValue FIGHTS_TO_DEATH = B
            .comment("Rival fights can go to the death. Off: a fight ends once someone's badly hurt.")
            .define("fightsToTheDeath", true);

    public static final ModConfigSpec.BooleanValue PAUSE_WITHOUT_PLAYERS = B
            .comment("Bots stand still while no real players are online.")
            .define("pauseWithoutPlayers", true);

    // --- anchoring: how much bots stay near the players --------------------------------

    public static final ModConfigSpec.BooleanValue LEASH = B
            .comment("Anchoring: bots head back toward the nearest player when they get too far away",
                    "(maxDistanceFromPlayers). Off = they go wherever they like.")
            .define("leashToPlayers", true);

    public static final ModConfigSpec.IntValue MAX_DISTANCE = B
            .comment("Anchoring: how far (blocks) a bot can get from the nearest player before it heads back. 0 = no limit.")
            .defineInRange("maxDistanceFromPlayers", 96, 0, 10_000);

    public static final ModConfigSpec.IntValue COME_BACK_PERCENT = B
            .comment("Anchoring: when a bot heads back, how close it comes, as a percentage of maxDistanceFromPlayers.")
            .defineInRange("comeBackToPercent", 40, 5, 100);

    public static final ModConfigSpec.BooleanValue ANCHOR_TO_SPECTATORS = B
            .comment("Anchoring: players in spectator mode count as someone to stay near. Off (the default) = while",
                    "you spectate, bots aren't tied to you at all and go anywhere they want.")
            .define("anchorToSpectators", false);

    public static final ModConfigSpec.BooleanValue LEASH_ON_EXPEDITIONS = B
            .comment("Anchoring: bots on a long trip (the nether, netherite, a trial chamber, a raid) are pulled back too.",
                    "Off (the default) = an expedition can go as far as it needs to.")
            .define("leashOnExpeditions", false);

    public static final ModConfigSpec.BooleanValue DRIFT_TO_PLAYERS = B
            .comment("Anchoring: when exploring, bots far from everyone tend to wander back toward the players.")
            .define("driftTowardPlayers", true);

    public static final ModConfigSpec.IntValue FOLLOW_SECONDS = B
            .comment("The longest a bot follows you after you crouch at it while looking at it (a partner or close friend; friends come for less, strangers briefly if at all, and rivals not at all).")
            .defineInRange("followSeconds", 300, 10, 36_000);

    public static final ModConfigSpec.BooleanValue LOOT_CHESTS = B
            .comment("Bots open chests and barrels they find and take useful things. Including yours.")
            .define("lootChests", true);

    public static final ModConfigSpec.BooleanValue GIVE_GUIDE_BOOK = B
            .comment("Give each player the guide book the first time they join.")
            .define("giveGuideBook", true);

    public static final ModConfigSpec SPEC = B.build();

    private JtgConfig() {}
}

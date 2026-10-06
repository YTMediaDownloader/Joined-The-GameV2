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

    public static final ModConfigSpec.BooleanValue PAUSE_WITHOUT_PLAYERS = B
            .comment("Bots stand still while no real players are online.")
            .define("pauseWithoutPlayers", true);

    public static final ModConfigSpec.IntValue MAX_DISTANCE = B
            .comment("Bots head back if they get further than this from the nearest player (blocks). 0 = no limit.")
            .defineInRange("maxDistanceFromPlayers", 96, 0, 10_000);

    public static final ModConfigSpec.IntValue FOLLOW_SECONDS = B
            .comment("How long a bot follows you after you crouch at it.")
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

package net.joinedthegame.bot;

import java.util.List;
import java.util.regex.Pattern;

/** The starting roster. Gamertag-style names, the kind you'd see on any public server. */
public final class BotNames {
    private BotNames() {}

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");

    public static final List<String> DEFAULT = List.of(
            "PixelPickle", "xXCobbleKingXx", "MossyMango", "Tater_Tot77", "NotABotHonest",
            "CreeperHugz", "LavaLamp_Larry", "BirchPlease", "OakleyDokey", "SirDigsALot",
            "TorchBearer99", "Gr4velGurl", "SneakyBeaky", "DiamondDave_", "CopperCrush",
            "noob_master420", "WaffleWarrior", "BedrockBenny", "Kelpie_Kai", "ZombieZoe",
            "FoxInSocks12", "CaveDweller", "PotatoPrince", "StoneColdSteve", "Sk3ptic",
            "EmeraldElla", "Biscuit_Bandit", "MudMuncher", "Nether_Ned", "CraftyCarl",
            "SpudBud", "TheFurnaceGuy", "QuartzQueen", "Twiggy_", "HoneyBadgerMC",
            "PebbleDash", "JustVibin", "AxolotlAndy", "Dandelion_Dan", "GlowSquidGo",
            // Real players, with their real skins and the way they play (see Personality.preset).
            "Duckatyt", "cozmoiscool130", "Redlantern8808", "DanTDM", "stampylongnose");

    /** Names that are real accounts: the bot wears that player's actual skin. */
    public static final java.util.Set<String> REAL_SKINS = java.util.Set.of(
            "duckatyt", "cozmoiscool130", "redlantern8808", "dantdm", "stampylongnose");

    public static boolean isValid(String name) {
        return VALID.matcher(name).matches();
    }
}

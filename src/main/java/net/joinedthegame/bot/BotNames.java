package net.joinedthegame.bot;

import java.util.List;
import java.util.regex.Pattern;

/** The starting roster. Gamertag-style names, the kind you'd see on any public server. */
public final class BotNames {
    private BotNames() {}

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /**
     * The roster (0.9.0): celebrities, Minecraft creators, and the originals who earned their
     * place. Their personalities are fictional Minecraft versions of them, not claims about the
     * real people (see Personality.preset). Worlds made before this keep whoever's already on
     * their roster; the new names are added to it, ready to invite.
     */
    public static final List<String> DEFAULT = List.of(
            // Celebrities (a skin drawn for the mod: see BUNDLED_SKINS).
            "MadisonBeer", "MillieBobbyBrown", "SadieSink", "JennaOrtega", "MayaHawke", "NataliaDyer",
            "EmmaMyers", "SabrinaCarpenter", "OliviaRodrigo", "BillieEilish", "ArianaGrande", "DuaLipa",
            "SelenaGomez", "TateMcRae", "HaileeSteinfeld", "Zendaya", "FlorencePugh",
            "StephenCurry", "LeBronJames", "FinnWolfhard", "JoeKeery", "JackBlack",
            // Minecraft creators (their real skins).
            "LDShadowLady", "GeminiTay", "PearlescentMoon", "falsesymmetry", "shubble", "ihasCupquake",
            "Aphmau", "KatherineEliza", "Grian", "Mumbo", "Smallishbeans", "CaptainSparklez", "markiplier",
            "DanTDM", "stampylongnose",
            // The originals.
            "Redlantern8808", "Duckatyt", "cozmoiscool130", "TorchBearer99", "BedrockBenny", "PixelPickle",
            "AxolotlAndy", "MudMuncher", "PenTester");

    /** Names that are real accounts: the bot wears that player's actual skin. */
    public static final java.util.Set<String> REAL_SKINS = java.util.Set.of(
            "duckatyt", "cozmoiscool130", "redlantern8808", "dantdm", "stampylongnose",
            "ldshadowlady", "geminitay", "pearlescentmoon", "falsesymmetry", "shubble", "ihascupquake",
            "aphmau", "grian", "mumbo", "smallishbeans", "captainsparklez", "markiplier");

    /**
     * Names whose skin ships with the mod ({@code textures/entity/player/<name>.png}, the slim
     * model unless listed in {@link #WIDE_SKINS}) instead of being looked up: the real people who
     * don't have a Minecraft account of their own (someone else owns the name, with a skin that's
     * nothing like them). A PNG of the same name in {@code config/joinedthegame/skins} replaces it.
     */
    public static final java.util.Set<String> BUNDLED_SKINS = java.util.Set.of(
            "madisonbeer", "milliebobbybrown", "sadiesink", "jennaortega", "mayahawke", "nataliadyer",
            "emmamyers", "sabrinacarpenter", "oliviarodrigo", "billieeilish", "arianagrande", "dualipa",
            "selenagomez", "tatemcrae", "haileesteinfeld", "zendaya", "florencepugh", "katherineeliza",
            "stephencurry", "lebronjames", "finnwolfhard", "joekeery", "jackblack");

    /** Bundled skins on the classic (wide-armed) model. */
    public static final java.util.Set<String> WIDE_SKINS = java.util.Set.of(
            "stephencurry", "lebronjames", "finnwolfhard", "joekeery", "jackblack");

    /** On every roster, old worlds included. */
    public static boolean isFeatured(String name) {
        for (String n : DEFAULT) if (n.equalsIgnoreCase(name)) return true;
        return false;
    }

    public static boolean isValid(String name) {
        return VALID.matcher(name).matches();
    }
}

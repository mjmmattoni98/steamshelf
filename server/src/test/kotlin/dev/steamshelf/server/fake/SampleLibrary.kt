package dev.steamshelf.server.fake

import kotlin.random.Random

/** A believable library of real Steam games (so the artwork loads from Steam's CDN) with made-up stats. */
object SampleLibrary {
    const val STEAM_ID = "76561197960287930"
    const val OTHER_STEAM_ID = "76561197960287931"
    const val API_KEY = "0123456789ABCDEF0123456789ABCDEF"

    val TAG_NAMES = mapOf(
        19 to "Action", 21 to "Adventure", 122 to "RPG", 492 to "Indie", 597 to "Casual", 599 to "Simulation",
        9 to "Strategy", 1625 to "Platformer", 1628 to "Metroidvania", 3871 to "2D", 4182 to "Singleplayer",
        3859 to "Multiplayer", 1685 to "Co-op", 1662 to "Survival", 1695 to "Open World", 1742 to "Story Rich",
        3834 to "Exploration", 4166 to "Atmospheric", 1667 to "Horror", 3810 to "Sandbox", 1774 to "Shooter",
        1716 to "Roguelike", 3959 to "Roguelite", 4026 to "Difficult", 1702 to "Crafting", 1643 to "Building",
        4085 to "Anime", 1697 to "Third Person", 3839 to "First-Person", 4747 to "Character Customization",
    )

    /** Three games, for tests that check exact output. */
    val small = listOf(
        FakeGame(367520, "Hollow Knight", playtimeMinutes = 2710, lastPlayed = 1_718_000_000, tagIds = listOf(1628, 1625, 492, 4026),
            deck = 3, linux = true, protonTier = "platinum", hltbMainHours = 27.5, controllerCategories = listOf(28), releaseYear = 2017),
        FakeGame(620, "Portal 2", playtimeMinutes = 95, playtime2WeeksMinutes = 95, lastPlayed = 1_730_000_000, tagIds = listOf(21, 1742, 1685),
            deck = 3, linux = true, protonTier = "gold", hltbMainHours = 8.5, playerCategories = listOf(2, 9), releaseYear = 2011),
        FakeGame(1145360, "Hades", tagIds = listOf(19, 1716, 492), deck = 2, protonTier = "platinum", hltbMainHours = 22.0, releaseYear = 2020),
    )

    private val NAMES = listOf(
        367520 to "Hollow Knight", 620 to "Portal 2", 400 to "Portal", 220 to "Half-Life 2", 1145360 to "Hades",
        413150 to "Stardew Valley", 292030 to "The Witcher 3: Wild Hunt", 1091500 to "Cyberpunk 2077", 1245620 to "ELDEN RING",
        374320 to "DARK SOULS III", 814380 to "Sekiro: Shadows Die Twice", 504230 to "Celeste", 646570 to "Slay the Spire",
        588650 to "Dead Cells", 250900 to "The Binding of Isaac: Rebirth", 105600 to "Terraria", 4000 to "Garry's Mod",
        1174180 to "Red Dead Redemption 2", 489830 to "The Elder Scrolls V: Skyrim Special Edition", 377160 to "Fallout 4",
        1086940 to "Baldur's Gate 3", 435150 to "Divinity: Original Sin 2 - Definitive Edition", 239140 to "Dying Light",
        268910 to "Cuphead", 753640 to "Outer Wilds", 632470 to "Disco Elysium - The Final Cut", 391540 to "Undertale",
        1794680 to "Vampire Survivors", 322330 to "Don't Starve Together", 252950 to "Rocket League", 1593500 to "God of War",
        1817070 to "Marvel's Spider-Man Remastered", 2050650 to "Resident Evil 4", 883710 to "Resident Evil 2",
        312520 to "Rain World", 1426210 to "It Takes Two", 1966720 to "Lethal Company", 2379780 to "Balatro",
        1145350 to "Hades II", 892970 to "Valheim", 548430 to "Deep Rock Galactic", 427520 to "Factorio",
        526870 to "Satisfactory", 1868140 to "DAVE THE DIVER", 2358720 to "Black Myth: Wukong", 1151640 to "Horizon Zero Dawn",
        289070 to "Sid Meier's Civilization VI", 8930 to "Sid Meier's Civilization V", 960090 to "Bloons TD 6",
        1659040 to "HITMAN World of Assassination", 594650 to "Hunt: Showdown 1896", 570 to "Dota 2",
        730 to "Counter-Strike 2", 1172470 to "Apex Legends", 359550 to "Tom Clancy's Rainbow Six Siege",
        1196590 to "Resident Evil Village", 1888930 to "The Last of Us Part I", 2420110 to "Horizon Forbidden West Complete Edition",
        1623730 to "Palworld", 1716740 to "Starfield", 945360 to "Among Us", 236390 to "War Thunder",
    )

    private val TIERS = listOf("platinum", "platinum", "gold", "gold", "silver", "bronze", "borked", null)

    /** About sixty games with a mix of everything the filters look at. Same data every run. */
    val games: List<FakeGame> = NAMES.map { (id, name) ->
        val r = Random(id)
        val played = r.nextInt(100) >= 30
        val minutes = if (played) r.nextInt(10, 9_000) else 0
        val recent = played && r.nextInt(100) < 15
        val hltb = if (r.nextInt(100) < 88) (r.nextInt(3, 160) / 2.0) else null
        FakeGame(
            appId = id,
            name = name,
            playtimeMinutes = minutes,
            playtime2WeeksMinutes = if (recent) r.nextInt(30, 900) else 0,
            lastPlayed = if (played) 1_700_000_000L + r.nextLong(0, 60_000_000) else 0,
            tagIds = TAG_NAMES.keys.shuffled(r).take(r.nextInt(3, 9)),
            deck = r.nextInt(4),
            linux = r.nextInt(100) < 18,
            protonTier = TIERS[r.nextInt(TIERS.size)],
            hltbMainHours = hltb,
            hltbName = if (hltb != null && r.nextInt(100) < 12) "$name Legends" else null,
            reviewPercent = r.nextInt(62, 99),
            playerCategories = listOf(listOf(2), listOf(2, 9), listOf(1, 36), listOf(2, 24, 39)).random(r),
            controllerCategories = listOf(emptyList(), listOf(18), listOf(28)).random(r),
            releaseYear = r.nextInt(2004, 2025),
            developer = listOf("Team Cherry", "Valve", "Supergiant Games", "FromSoftware", "Larian Studios", "Indie Studio").random(r),
        )
    }
}

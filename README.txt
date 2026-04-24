================================================================
KMC Tournament — Complete Bundle
================================================================

This is the FULL project: KMCCore + LuckyBlock + AdventureEscape
in one unified multi-module Maven project. No missing pieces —
all the code we built together is inside.

================================================================
STRUCTURE
================================================================

kmc-tournament/
├── pom.xml                    parent (lists all modules + shared config)
├── build-all.bat              Windows build script
├── build-all.sh               Linux/Mac build script
├── README.txt                 this file
│
├── KMCCore/                   main plugin — tournament engine
│   ├── pom.xml
│   └── src/main/
│       ├── java/nl/kmc/kmccore/
│       │   ├── KMCCore.java        main class
│       │   ├── api/                public API for other plugins
│       │   ├── commands/           all /kmc... commands
│       │   ├── database/           SQLite persistence
│       │   ├── listeners/          event listeners (lobby protection,
│       │   │                       chat, votes, etc.)
│       │   ├── managers/           one manager per feature:
│       │   │                       Team, PlayerData, Points,
│       │   │                       Tournament, Game, Automation,
│       │   │                       TabList, Arena, Schematic
│       │   ├── models/             KMCTeam, PlayerData, KMCGame
│       │   ├── npc/                FancyNpcs hologram leaderboards
│       │   ├── scoreboard/         live per-player sidebar
│       │   └── util/               MessageUtil, AnnouncementUtil,
│       │                           ClickableVoteMessage
│       └── resources/
│           ├── plugin.yml          all commands + permissions
│           ├── config.yml          tournament settings
│           ├── messages.yml        all chat messages (Dutch)
│           └── points.yml          all point rewards
│
├── LuckyBlock/                minigame — yellow-concrete lucky blocks
│   ├── pom.xml
│   └── src/main/
│       ├── java/nl/kmc/luckyblock/
│       │   ├── LuckyBlockPlugin.java
│       │   ├── commands/           /luckyblock start/stop/status
│       │   ├── listeners/          block break + death
│       │   ├── managers/           game state, tracker, loot table
│       │   ├── models/             LootEntry
│       │   └── util/               LootExecutor
│       └── resources/
│           ├── plugin.yml
│           └── config.yml          full loot table with 20+ entries
│
└── AdventureEscape/           minigame — Ace Race style
    ├── pom.xml
    └── src/main/
        ├── java/nl/kmc/adventure/
        │   ├── AdventureEscapePlugin.java
        │   ├── commands/           /adventure or /ae
        │   ├── listeners/          block step + line crossing
        │   ├── managers/           arena, effect blocks, race,
        │   │                       race scoreboard
        │   └── models/             EffectBlock, RacerData
        └── resources/
            ├── plugin.yml
            └── config.yml          10 effect-block colors + settings

================================================================
BUILDING
================================================================

From inside kmc-tournament/ run:

    mvn clean package

Or use the script:
    Windows:   build-all.bat
    Linux/Mac: ./build-all.sh

Both scripts create a dist/ folder at the top level containing
the 3 finished jars ready to drop in plugins/.

Build order (automatic):
    1. KMCCore         (no deps — built first)
    2. LuckyBlock      (depends on KMCCore)
    3. AdventureEscape (depends on KMCCore)

================================================================
INSTALLING
================================================================

Copy all 3 jars into your server's plugins/ folder:
    plugins/KMCCore-1.0.0.jar
    plugins/LuckyBlock-1.0.0.jar
    plugins/AdventureEscape-1.0.0.jar

Optional plugins that add features:
    - WorldEdit  (for schematic paste/reset arenas)
    - FancyNpcs  (for leaderboard NPC integration)

Restart the server. KMCCore loads first, then the games.

================================================================
WHAT'S INCLUDED (FEATURE CHECKLIST)
================================================================

KMCCore:
  ✔ 8 teams (Rode Ratten, Oranje Otters, etc.) max 4 players each
  ✔ Coloured nametags in tab + chat + above head (all synced)
  ✔ Personal points auto-add to team points
  ✔ Round multiplier system (round 1 = 1x, round 5 = 3x)
  ✔ Tournament lifecycle: start → rounds → end with auto-reset
  ✔ Automation engine: intermission → vote → pre-start → game
  ✔ Force-skip command with auto-next-vote
  ✔ Auto-skip games with no arena config
  ✔ Clickable GUI vote with ALL unplayed games per tournament
  ✔ Post-game leaderboard chain (players → teams → total, 10s apart)
  ✔ Random team assignment that works for OPs too
  ✔ Lobby with /kmclobby, damage/mob/grief protection
  ✔ Schematic arena paste + reset via WorldEdit
  ✔ SQLite persistence, extended lifetime stats
  ✔ FancyNpcs leaderboard integration

LuckyBlock:
  ✔ Scans yellow concrete in pasted schematic
  ✔ 20+ loot entries across good/bad/rare categories
  ✔ Weighted random selection
  ✔ Last-one-standing win condition
  ✔ Points awarded via KMCCore API (credits team automatically)

AdventureEscape:
  ✔ Ace Race style with 10 coloured glazed terracotta blocks
  ✔ Effect blocks give speed/jump/elytra/dolphin/etc for X seconds
  ✔ Multi-lap races with start + finish line boxes
  ✔ Live per-player race scoreboard (leader, top 5, your stats)
  ✔ Best-lap tracking + total-time tracking
  ✔ First-past-the-post wins, DNF players get last-place points
  ✔ Runs in its own world (Multiverse compatible)
  ✔ Uses external-arena flag to bypass KMCCore's arena check

================================================================
QUICK START
================================================================

1. Build:                     mvn clean package
2. Copy dist/*.jar             → plugins/
3. Restart server
4. In-game as op:
     /kmclobby set             (stand in lobby)
     /kmcrandomteams           (distribute online players)
     /kmcarena setorigin lucky_block   (stand where arena pastes)
     /ae setworld racetrack    (pick AE race world)
     /kmctournament start      (begin!)
     /kmcauto start            (automation takes over)

================================================================
ADDING A NEW GAME LATER
================================================================

1. Copy LuckyBlock/ as a template folder, rename it
2. Update the <artifactId> in the new folder's pom.xml
3. Rewrite src/ with your game's code
4. Add the folder to the parent pom.xml's <modules> list:
       <modules>
           <module>KMCCore</module>
           <module>LuckyBlock</module>
           <module>AdventureEscape</module>
           <module>YourNewGame</module>
       </modules>
5. mvn clean package

Any new game plugin automatically has access to KMCCore's API
just by adding the KMCCore dependency (see LuckyBlock/pom.xml).

================================================================
TROUBLESHOOTING
================================================================

"Could not resolve dependencies for nl.kmc:KMCCore"
  → You tried to build a single module. Build from the PARENT
    folder instead: cd kmc-tournament && mvn clean package

"BUILD SUCCESS but no jar in target/"
  → You forgot to run 'package' — 'compile' alone doesn't produce jars.

"Plugin loads but some feature is missing"
  → Check your server is Paper 1.21+ with Java 21.

"Lobby protection doesn't work"
  → Set the lobby first: /kmclobby set

"Random teams skipped me"
  → That was a bug in an earlier version. This bundle has the fix.
    If it still happens, make sure you rebuilt and copied the new jar.

================================================================
THAT'S EVERYTHING!
================================================================

All the work we've done together is in this one bundle.
Delete your old project folders and use this as the single
source of truth going forward.

krisheesh
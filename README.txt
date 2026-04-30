# KMC Tournament — Server Setup & Feature Guide

A multi-game tournament system for Paper 1.21 / Java 21. One core plugin
manages teams, points, and tournament flow; 13 minigame plugins plug into
it as the rotation.

> **Audience:** server operators setting this up for the first time.
> If you only need to update scoring values, see each game's `config.yml`.

---

## Contents

- [What you get](#what-you-get)
- [Prerequisites](#prerequisites)
- [First-time install](#first-time-install)
- [Running a tournament](#running-a-tournament)
- [Per-game setup guides](#per-game-setup-guides)
  - [SkyWars](#skywars)
  - [Survival Games](#survival-games)
  - [QuakeCraft](#quakecraft)
  - [Spleef](#spleef)
  - [TGTTOS](#tgttos)
  - [Mob Mayhem](#mob-mayhem)
  - [TNT Tag](#tnt-tag)
  - [Elytra Endrium](#elytra-endrium)
  - [Parkour Warrior](#parkour-warrior)
  - [The Bridge](#the-bridge)
  - [Adventure Escape](#adventure-escape)
  - [Bingo](#bingo)
  - [Lucky Block](#lucky-block)
- [Scoring reference](#scoring-reference)
- [Admin commands cheat sheet](#admin-commands-cheat-sheet)
- [Troubleshooting](#troubleshooting)

---

## What you get

### Core features (KMCCore)

- **Team system** — players assigned to colored teams, persistent across games
- **Tournament mode** — chains all 13 games with round multipliers (×1, ×1, ×1.5, ×1.5, ×2)
- **Points system** — all per-game scoring funnels through a central API; team aggregation automatic
- **Leaderboards** — `/kmclb teams` and `/kmclb players` (paginated)
- **Hall of Fame** — top-stat NPCs displayed in lobby (kills/wins/streak)
- **Achievements** — 22 built-in achievements (12 common, 6 rare, 4 legendary)
- **Stats GUI** — multi-page per-player stats with `/kmcstats`
- **Tournament history** — completed tournaments archived and queryable
- **Voting** — players vote between rotation games via `/kmcvote`
- **Automation** — auto-progresses rotation when each game ends
- **Simulation** — test scoring math without running real games (`/event simulate`)
- **Snapshots / rollback** — recover from disasters with `/event rollback`
- **Map rotation** — multi-map games (TGTTOS, Bridge, etc.) cycle automatically
- **Lobby NPCs** — clickable game-launch NPCs in lobby
- **Player preferences** — opt-in/out of features via `/kmcprefs`
- **Server health monitor** — TPS, RAM, online — `/kmchealth`
- **Achievement broadcasts** — server-wide announcements when unlocked

### The 13 minigames

| Game | Style | Players |
|---|---|---|
| **SkyWars** | PvP, last-alive, sky islands | Solo or teams |
| **Survival Games** | PvP, hunger games style, world border shrinks | Solo |
| **QuakeCraft** | PvP, railguns, first to 25 kills | Teams |
| **Spleef** | Floor-breaking, last-alive | Solo |
| **TGTTOS** | "Get to the other side" mini-courses | Teams (per-round placement) |
| **Mob Mayhem** | Co-op wave defense, multi-team | Teams |
| **TNT Tag** | Hot-potato-style with TNT | Solo |
| **Elytra Endrium** | Elytra checkpoint race | Solo with team aggregation |
| **Parkour Warrior** | Solo parkour with stages and CPs | Solo |
| **The Bridge** | Hypixel-style bridge battle, score goals | Teams |
| **Adventure Escape** | Multi-lap race with checkpoints | Solo with team avg bonus |
| **Bingo** | Survival-style bingo card crafting | Teams |
| **Lucky Block** | Lucky block PvP | Teams |

---

## Prerequisites

| Software | Version |
|---|---|
| Paper / Spigot | 1.21 (any patch) |
| Java | 21 |
| RAM | 6GB minimum, 8GB+ recommended for 32+ players |

### Soft dependencies (highly recommended)

- **FancyNpcs** — for lobby NPCs and Hall of Fame
- **WorldEdit** or **FastAsyncWorldEdit** — for arena pasting in some games
- **Citizens** — alternative NPC backend (less actively used)

These are optional. Without them, NPC-related features are disabled but
everything else works.

---

## First-time install

### 1. Build the plugins


```bash
mvn clean package
```

This builds all 14 modules. If it fails, build KMCCore first:

```bash
mvn clean package -pl KMCCore -am
mvn clean package
```

You'll get JARs in each module's `target/` folder. Copy all 14 to your
server's `plugins/` directory:

- `KMCCore/target/KMCCore-x.x.x.jar`
- `SkyWars/target/SkyWars-x.x.x.jar`
- ... etc

### 2. First server startup

Start the server. The plugins generate their default `config.yml` files in
each plugin's data folder. You'll see messages like:

```
[KMCCore] enabled
[SkyWars] enabled
[bingo] enabled
...
```

Stop the server.

### 3. Set up the lobby

The lobby is your "between-games" hub where players spawn before/after
games. Build (or paste) a small lobby world.

In-game:
```
/kmclobby set
```

Stand where you want players to teleport between games and run that
command. Save successful.

### 4. Create teams

```
/kmcteam create red    "Red"    RED
/kmcteam create blue   "Blue"   BLUE
/kmcteam create green  "Green"  GREEN
/kmcteam create yellow "Yellow" YELLOW
```

(Adjust the team count to your event — typically 4-8 teams.)

Assign players:
```
/kmcteam add <team-id> <player>
```

Or auto-distribute online players:
```
/kmcrandomteams all confirm
```

### 5. Set up each minigame's arena

Each game needs a one-time arena setup. Skip games you don't plan to use —
they won't break anything if their arena isn't configured.

Setup details for each game are in the [per-game guides](#per-game-setup-guides) below.

### 6. Run a test tournament

Once 2+ minigames have arenas configured:

```
/kmcready
/kmctournament start
```

The automation will run the rotation. To skip a game during testing:
```
/kmcgame forceskip
```

---

## Running a tournament

### Quick reference

| Action | Command |
|---|---|
| Start a tournament | `/kmctournament start` |
| End early | `/kmctournament stop` |
| Check status | `/kmctournament status` |
| Reset (wipe points) | `/kmctournament reset` |
| Hard reset (wipe everything) | `/kmctournament hardreset` |

### Round multipliers (built-in)

The tournament automatically applies round multipliers so later games are
worth more:

| Round | Multiplier |
|---|---|
| 1 | ×1 |
| 2 | ×1 |
| 3 | ×1.5 |
| 4 | ×1.5 |
| 5+ | ×2 |

This applies to ALL points (kills, placement bonuses, etc.) automatically
through `KMCApi.givePoints`.

### Game flow per round

```
[Lobby Phase]
  ↓ Players in lobby, vote on next game (/kmcvote)
[Vote Resolved → Game Selected]
  ↓ Countdown, brief intro
[Game Active]
  ↓ Players play, points accumulate
[Game End]
  ↓ Final standings broadcast
[Back to Lobby]
  ↓ Round counter increments
```

### Manual control (for hosts)

If you want to manually pick games instead of voting:

```
/kmcgame set <game_id>
/kmcgame start
```

Available `<game_id>` values: `skywars`, `survival_games`, `quake_craft`,
`spleef`, `tgttos`, `mob_mayhem`, `tnt_tag`, `elytra_endrium`,
`parkour_warrior`, `the_bridge`, `adventure_escape`, `bingo`, `lucky_block`.

### Skipping a stuck game

If something gets wedged (rare):

```
/kmcgame forceskip
```

Aborts the current game, returns players to lobby, and continues the
tournament rotation as if the game ended normally.

---

## Per-game setup guides

Each game has its own command prefix. All admin commands require op or the
relevant permission (`<game>.admin`).

### SkyWars

Sky islands with chests. Last alive wins, with personal placement points.

**Setup steps:**

```
/skywars setworld <world-name>          # The world containing your sky islands
/skywars setmiddle                       # Stand at center of the map
/skywars setmidradius 50                 # Border radius (in blocks)
/skywars setvoidy 0                      # Y-level below which players "die"
/skywars addisland                       # Stand on each island spawn point and run
                                         # Repeat for each island (one per player slot)
/skywars listislands                     # Verify
/skywars stockchests                     # Test chest stocking
```

**Run a game:** `/skywars start`

### Survival Games

Hunger Games style. Pedestals at start, chests throughout, world border
shrinks for deathmatch.

**Setup steps:**

```
/survivalgames setworld <world-name>
/survivalgames setcornucopia            # Stand at center of cornucopia
/survivalgames addpedestal              # Stand on each spawn pedestal, repeat
/survivalgames setborder 200            # Initial border radius
/survivalgames setvoidy 0
/survivalgames stockchests              # Test chest stocking
```

**Run:** `/survivalgames start`

### QuakeCraft

Railgun PvP, first player to 25 kills wins.

**Setup steps:**

```
/quakecraft setworld <world-name>
/quakecraft setspawn                    # Stand on each spawn point and run
                                        # Add as many as you want — players are
                                        # randomly assigned each respawn
/quakecraft setpowerup damage_boost     # (optional) define powerup spawn locations
/quakecraft setpowerup speed_boost
```

**Run:** `/quakecraft start`

Killstreaks (×3, ×5, ×7, ×10) and multi-kill bonuses are configured in
`plugins/QuakeCraft/config.yml`.

### Spleef

Last alive on a snow floor. Configurable layers.

**Setup steps:**

```
/spleef setworld <world-name>
/spleef setlayer                         # Stand on the snow floor — sets
                                         # the breakable region's Y-level
/spleef setvoidy 0
/spleef addspawn                         # Stand on each spawn point and run
```

**Run:** `/spleef start`

### TGTTOS

"Get to the other side" — multi-map sequence with start/finish lines.

**Setup steps:**

```
/tgttos createmap forest                 # Create a new map by id
/tgttos editmap forest                   # Enter edit mode for this map
/tgttos name "Forest Sprint"             # Set map display name
/tgttos world <world-name>               # Set the world
/tgttos addspawn                         # Stand on each starting spawn
/tgttos voidy 0                          # Out-of-bounds Y level
/tgttos commit                           # Save and exit edit mode
/tgttos createmap caves
# ...repeat for each map you want in rotation
/tgttos listmaps                         # Verify
```

The finish line is detected via the world border / death plane. Each map
should have a clear way to "finish" (e.g. step on a goal block — wired
through `BlockTracker`).

**Run:** `/tgttos start`

### Mob Mayhem

Wave-based co-op survival. Each team gets its own arena.

**Setup steps:**

```
/mobmayhem settemplate <template-world>  # Each team gets a clone of this world
/mobmayhem setspawn                      # Stand at the spawn point in template
/mobmayhem addmobspawn                   # Add mob spawn points (repeat)
```

The plugin clones the template world per team at game start.

**Run:** `/mobmayhem start`

### TNT Tag

Hot-potato with TNT. Survive each round; tag others to pass the bomb.

**Setup steps:**

```
/tnttag setworld <world-name>
/tnttag setvoidy 0
/tnttag addspawn                         # Repeat for each spawn point
```

**Run:** `/tnttag start`

### Elytra Endrium

Elytra-only race through hoops and checkpoints.

**Setup steps:**

```
/elytraendrium setworld <world-name>
/elytraendrium setlaunch                 # Stand at launch pad
/elytraendrium cp <name>                 # Stand AT a checkpoint, optionally name it
/elytraendrium points 8                  # Set CP point value
/elytraendrium boost <id> FORWARD 2.5    # Add a boost hoop (Type, strength)
/elytraendrium listcp                    # Verify checkpoints
/elytraendrium listboosts                # Verify hoops
```

**Run:** `/elytraendrium start`

### Parkour Warrior

Solo parkour with stages, CPs, and powerups.

**Setup steps:**

```
/parkourwarrior setworld <world-name>
/parkourwarrior setstart                 # Standard at start point
/parkourwarrior cp <name>                # Add a checkpoint at your location
/parkourwarrior difficulty EASY          # Set difficulty of LAST cp added
/parkourwarrior stage 2                  # Set stage number (organize by section)
/parkourwarrior points 12                # Override per-CP points (optional)
/parkourwarrior respawn                  # Set respawn target for LAST cp
/parkourwarrior powerup speed 5          # Add powerup (type, strength)
/parkourwarrior amplifier 1              # Powerup amplifier
/parkourwarrior duration 60              # Powerup duration (seconds)
/parkourwarrior listcp                   # Verify
```

**Run:** `/parkourwarrior start`

**Tip:** difficulties EASY (+8), MEDIUM (+12), HARD (+15) are configured
in `plugins/ParkourWarrior/config.yml` under `points.by-difficulty`.

### The Bridge

2v2 / 4v4 bridge battle. Score goals to win.

**Setup steps:**

```
/bridge setworld <world-name>
/bridge setvoidy 0
/bridge createteam red                   # Create a team slot
/bridge editteam red                     # Enter edit mode
/bridge name "Red Side"
/bridge color RED
/bridge wool RED_WOOL                    # Wool block they place
/bridge spawn                            # Stand at team spawn
/bridge commit                           # Save and exit
/bridge createteam blue
/bridge editteam blue
# ...repeat
/bridge listteams                        # Verify
```

Goal-detection is based on a "void below the goal hole" mechanism — players
score by jumping into the opposing team's hole.

**Run:** `/bridge start`

### Adventure Escape

Multi-lap race with checkpoints, out-of-bounds zones, and laps.

**Setup steps:**

```
/adventure setworld <world-name>
/adventure setspawn                      # Stand at racer spawn
/adventure setstartline <pos1> <pos2>    # Or stand inside the start line area
/adventure setfinishline <pos1> <pos2>
/adventure setlaps 3                     # Number of laps to complete
/adventure setcheckpoint <name>          # Stand at a checkpoint location
/adventure setcheckpointtrigger <name> <pos1> <pos2>   # Define trigger box
/adventure setoutofbounds <cp_name> <oob_name> <pos1> <pos2>   # Add OOB zone
/adventure listcheckpoints
/adventure removecheckpoint <name>
/adventure removeoob <cp_name> <oob_name>
```

Checkpoints are scored in order. Out-of-bounds zones respawn the player
to their last checkpoint. Fastest single lap earns +25 bonus.

**Run:** `/adventure start`

### Bingo

Survival-style bingo card. Craft items to fill the card.

**Setup steps:**

```
# 1. Build a survival-style template world (regular survival world is fine)

# 2. Register it as the template
/bingo settemplate <world-name>

# 3. Stand where you want players to spawn
/bingo setspawn

# 4. (Optional) Preview a generated card without starting
/bingo card

# 5. Done!
```

The plugin clones the template world per game so the original isn't
permanently modified.

**Run:** `/bingo start`

> **Spawn safety:** the SafeSpawnHelper ensures players spawn on solid ground
> not in lava/water, and 4 blocks apart from each other. Up to 64 players
> work in well under one tick.

### Lucky Block

PvP arena where lucky blocks drop random rewards.

**Setup steps:**

Lucky Block doesn't have arena commands — it uses team spawns from KMCCore
and lucky-block placement is handled by world editing or world generation.

You'll need to:
1. Build a PvP arena
2. Place lucky blocks (configured material in `plugins/LuckyBlock/config.yml`)
3. Set team spawns through KMCCore (not LuckyBlock)

**Run:** `/luckyblock start`

---

## Scoring reference

Quick reference for how points are awarded across all games. Multiplied
by round multiplier automatically.

### SkyWars
- Kill: **+25**
- Living while someone dies: **+5** (each survivor)
- Placement: **+250 → +10** (-10/place, 26th+ = 0)

### Survival Games
- Kill: **+35**
- Living while someone dies: **+5**
- Living while a team dies: **+20**
- Win (last alive): **+200**
- Placement: **+250 → +10** (-10/place)

### QuakeCraft
- Kill: **+12**
- Win-game (top kills): **+50**
- Revenge (within 10s): **+25**
- Killstreak ×3/×5/×7/×10: **+10/+25/+50/+100**
- Multi-kill ×2/×3/×4: **+5/+15/+30**

### Spleef
- Per elimination: **+35**
- Living while someone dies: **+5**
- Win: **+200**
- Placement: **+250 → +10** (-10/place)

### TGTTOS
- Round 1st/2nd/3rd/4th/5th: **+80/+70/+65/+60/+55**
- Team finish bonus 1st/2nd/3rd/4th/5th: **+25/+20/+15/+10/+5** each member

### Mob Mayhem
- Per mob: **+2**
- Per boss mob: **+50**
- Wave survived: **+20**
- Living while someone dies: **+5**

### TNT Tag
- Per tag: **+10**
- Round survive: **+60**
- Last alive: **+50**
- Living while someone dies: **+5**
- Placement: **+250 → +10** (-10/place)

### Elytra Endrium
- Checkpoint: **+8**
- First team to fully finish: **+10** each member
- Finish placement: **+320 → +10** (-10/place, 33rd+ = 0)

### Parkour Warrior
- EASY checkpoint: **+8** | MEDIUM: **+12** | HARD: **+15**
- Section complete (any non-finish CP): **+15** extra
- Finish: **+250 → +25** (floor)

### The Bridge
- Goal: **+50**
- Assist (within 8s): **+20**
- Kill: **+8**
- Round win (each goal): **+10** each member

### Adventure Escape
- Finish placement: **+320 → +10** (-10/place)
- Fastest lap: **+25**
- Team avg placement #1/#2/#3: **+20/+15/+10** each

### Bingo
- Per craft (1st team / 2nd / 3rd / others): **+40/+30/+20/+10**
  - Goes to the player who actually crafted it
- Completion bonus: **+100 split** among most-squares team
  - 4 members = 25 each, 2 members = 50 each

### Lucky Block
- Kill: **+20**
- Win: **+200**

---

## Admin commands cheat sheet

### Tournament & rotation

| Command | What it does |
|---|---|
| `/kmctournament start` | Begin a tournament |
| `/kmctournament stop` | End early |
| `/kmctournament status` | Show progress |
| `/kmctournament reset` | Wipe points only |
| `/kmctournament hardreset` | Wipe everything |
| `/kmcround set <n>` | Force the round number |
| `/kmcgame set <id>` | Pre-select next game |
| `/kmcgame start` | Launch the selected game |
| `/kmcgame skip` | Skip current game (give result anyway) |
| `/kmcgame forceskip` | Abort current game, return to lobby |
| `/kmcgame list` | Show all games and their statuses |
| `/kmcvote` | Open the voting GUI |
| `/kmcauto start` / `pause` / `resume` | Control automation engine |

### Teams

| Command | What it does |
|---|---|
| `/kmcteam create <id> <name> <color>` | Create team |
| `/kmcteam delete <id>` | Delete team |
| `/kmcteam add <team> <player>` | Assign player |
| `/kmcteam remove <player>` | Remove from any team |
| `/kmcteam list` | Show all teams |
| `/kmcteam info <team>` | Member list + points |
| `/kmcrandomteams [all\|new] [confirm]` | Auto-distribute online players |

### Points

| Command | What it does |
|---|---|
| `/kmcpoints set <player\|team> <id> <amount>` | Set points |
| `/kmcpoints add <player\|team> <id> <amount>` | Add points |
| `/kmcpoints remove <player\|team> <id> <amount>` | Subtract points |
| `/kmclb teams [page]` | Team leaderboard |
| `/kmclb players [page]` | Player leaderboard |
| `/kmcstats [player]` | Stats GUI |

### Arena & lobby

| Command | What it does |
|---|---|
| `/kmclobby set` | Set the inter-game lobby spawn |
| `/kmclobby tp` | Teleport yourself to lobby |
| `/kmclobby tpall` | Teleport ALL online players to lobby |
| `/kmcarena ...` | Generic arena helpers (per-game) |

### Simulation & recovery

| Command | What it does |
|---|---|
| `/event simulate <rounds> <players>` | Run a fake tournament for testing |
| `/event snapshot` | Take a snapshot |
| `/event listsnapshots` | Show available snapshots |
| `/event rollback <snapshot-id>` | Restore from a snapshot |

### Hall of Fame & NPCs

| Command | What it does |
|---|---|
| `/kmchof setnpc <stat> <fancyNpcId>` | Bind a FancyNPC to display a stat |
| `/kmchof refresh` | Force-refresh HoF NPCs |
| `/kmchof list` / `slots` | Show config |
| `/kmclobbynpc add` / `remove` / `list` | Lobby game-launcher NPCs |
| `/kmcnpc create` / `remove` / `list` | Leaderboard NPCs |

### Quality of life

| Command | What it does |
|---|---|
| `/kmcready` | Mark yourself ready (during ready-checks) |
| `/kmcprefs` | Open personal preferences |
| `/kmchealth` | Show TPS / RAM / online count |
| `/tc <message>` | Team chat |

### Per-game shortcuts

Every minigame has a `start`, `stop`, `status`, and `reload` command:

```
/skywars start         /skywars stop         /skywars status         /skywars reload
/survivalgames start   /survivalgames stop   /survivalgames status   /survivalgames reload
/quakecraft start      /quakecraft stop      /quakecraft status      /quakecraft reload
/spleef start          /spleef stop          /spleef status          /spleef reload
/tgttos start          /tgttos stop          /tgttos status          /tgttos reload
/mobmayhem start       /mobmayhem stop       /mobmayhem status       /mobmayhem reload
/tnttag start          /tnttag stop          /tnttag status          /tnttag reload
/elytraendrium start   /elytraendrium stop   /elytraendrium status   /elytraendrium reload
/parkourwarrior start  /parkourwarrior stop  /parkourwarrior status  /parkourwarrior reload
/bridge start          /bridge stop          /bridge status          /bridge reload
/adventure start       /adventure stop       /adventure status       /adventure reload
/bingo start           /bingo stop           /bingo status           /bingo reload
/luckyblock start      /luckyblock stop      /luckyblock status      /luckyblock reload
```

`reload` re-reads the per-game `config.yml` without restarting the
server. Useful for tweaking scoring values mid-test.

---

## Troubleshooting

### "/event not a command in game"

Your `KMCCore/src/main/resources/plugin.yml` is missing the `event:`
command entry. See the section in the latest plugin.yml that lists all
admin commands. Reload the server after fixing.

### Points are doubled for kills

Two listeners are firing for the same kill. The KMCCore global kill
listener is supposed to skip games like SkyWars/SG/Spleef/QuakeCraft
that have their own kill credit. If you're seeing doubled points:

1. Check `KMCCore/src/main/java/nl/kmc/kmccore/listeners/PlayerKillListener.java`
2. Verify the switch statement skips your game's id
3. If your game id is missing, add it

### Bingo world doesn't generate

The most common cause is that `/bingo settemplate` was never run, or the
template world isn't loaded by the server.

Check `plugins/Bingo/config.yml` — there should be a `world.template-name`
key. If it's blank or missing, run:
```
/bingo settemplate <existing-world-name>
```

If that's already set but the world still doesn't clone, check
`latest.log` for "Failed to copy world files" or "Failed to load cloned
world" messages.

### Compile error: "cannot find symbol"

Build modules in dependency order:
```
mvn clean package -pl KMCCore -am
mvn clean package
```

KMCCore must build first because every other plugin depends on it.

### "Variable 'ps' is already defined in the scope"

This is a Java naming conflict. If you see it in our patches, the
hotfix shipped earlier renames the conflicting variable. Apply the
hotfix.

### Plugin not loading at startup

Check `latest.log` for stack traces. Common causes:
- KMCCore failed to load (check that one first)
- Missing dependency: install FancyNpcs or set `softdepend` to ignore
- Java version mismatch — must be Java 21

### "/kmcteam create" says team already exists but it doesn't

Database state out of sync with config. Try:
```
/kmctournament hardreset
```

This wipes ALL tournament state and starts fresh. ⚠️ Don't do this
mid-event!

### Player can't be teleported to spawn

Check that the spawn world is loaded. You can force-load with:
```
/mv load <world-name>     (Multiverse)
```

Or add the world to `bukkit.yml` to auto-load at startup.

### Game won't start: "Arena not ready"

Run the per-game `status` command to see what's missing:
```
/skywars status
/survivalgames status
```

It'll list every required setting and whether it's set.

### Tournament softlocks (no game starts after current one ends)

The automation engine can get stuck if a game crashes mid-execution.
Restart the engine:
```
/kmcauto pause
/kmcauto resume
```

Or skip and continue:
```
/kmcgame forceskip
```

---

## Need help?

- Each per-game guide assumes you've completed the [first-time install](#first-time-install)
- For scoring math questions, see [Scoring reference](#scoring-reference)
- For admin commands, see [Admin commands cheat sheet](#admin-commands-cheat-sheet)
- Check `plugins/<game>/config.yml` to fine-tune any value (then `/<game> reload`)

If something behaves unexpectedly, check `latest.log` first — most
issues report a clear stack trace there.

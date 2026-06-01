# KMC Tournament — Wiki & Setup Guide

A multi-game tournament system for Paper 1.21.4 / Java 21. One core plugin
manages teams, points, and tournament flow; 13 mini-game plugins plug in
as the rotation.

> **Audience:** server operators setting this up for the first time,
> and players who want to understand how the tournament works.

---

## Contents

- [What you get](#what-you-get)
- [Prerequisites](#prerequisites)
- [First-time install](#first-time-install)
- [Running a tournament](#running-a-tournament)
- [How the tournament works (player view)](#how-the-tournament-works-player-view)
- [Teams](#teams)
- [Points & scoring](#points--scoring)
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
- [Player commands](#player-commands)
- [Troubleshooting](#troubleshooting)

---

## What you get

### Core features (KMCCore)

- **Team system** — players assigned to colored teams, persistent across games
- **Tournament mode** — chains all 13 games with round multipliers (×1 up to ×5)
- **Points system** — all per-game scoring funnels through a central API; team aggregation automatic
- **Leaderboards** — `/kmclb teams` and `/kmclb players` (paginated)
- **Hall of Fame** — top-stat NPCs permanently displayed in lobby (kills / wins / streak)
- **Achievements** — built-in achievements with server-wide unlock broadcasts
- **Stats GUI** — multi-page per-player stats with `/kmcstats`
- **Tournament history** — completed tournaments archived and queryable
- **Voting** — players vote for the next game via `/kmcvote`
- **Automation** — auto-progresses the rotation when each game ends
- **Simulation** — test scoring math without running real games (`/event simulate`)
- **Snapshots / rollback** — recover from disasters with `/event rollback`
- **Map rotation** — multi-map games (TGTTOS, Bridge, etc.) cycle automatically
- **Lobby NPCs** — clickable game-launch NPCs in lobby
- **Player preferences** — opt in/out of features via `/kmcprefs`
- **Server health monitor** — TPS, RAM, online count — `/kmchealth`
- **Discord integration** — automatic webhook posts for game results and achievements

### The 13 mini-games

| Game | Style | Players |
|---|---|---|
| **SkyWars** | PvP, last alive, sky islands | Teams |
| **Survival Games** | PvP, hunger games, border shrinks | Solo |
| **QuakeCraft** | PvP, railguns, first to kill limit | Teams |
| **Spleef** | Floor-breaking, last alive | Teams |
| **TGTTOS** | Race to the other side, multi-round | Teams |
| **Mob Mayhem** | Co-op wave defense | Teams |
| **TNT Tag** | Hot-potato with TNT | Solo |
| **Elytra Endrium** | Elytra checkpoint race | Solo with team scoring |
| **Parkour Warrior** | Solo parkour with stages and checkpoints | Solo |
| **The Bridge** | Bridge battle, score goals in opponent's hole | Teams |
| **Adventure Escape** | Puzzle escape room with effect blocks | Teams |
| **Bingo** | Collect items to complete a bingo card | Teams |
| **Lucky Block** | Break lucky blocks, fight with random loot | Teams |

---

## Prerequisites

| Software | Version |
|---|---|
| Paper | 1.21.4 |
| Java | 21 |
| RAM | 6 GB minimum, 8 GB+ recommended for 32+ players |

### Soft dependencies (highly recommended)

- **FancyNpcs** — for lobby NPCs and Hall of Fame statues
- **WorldEdit** or **FastAsyncWorldEdit** — for arena pasting in some games
- **Multiverse-Core** — for managing multiple arena worlds

These are optional. Without them, NPC-related features are disabled but
everything else works.

---

## First-time install

### 1. Build the plugins

```bash
mvn clean install
```

This builds all modules in dependency order. If it fails, build KMCCore first:

```bash
mvn clean install -pl KMCCore -am
mvn clean install
```

On Windows use the included helper:
```bat
build-all.bat
```

Copy all JARs from each module's `target/` folder into your server's `plugins/` directory:

```
KMCCore/target/KMCCore-*.jar
SkyWars/target/SkyWars-*.jar
bingo/target/bingo-*.jar
... (all 13 game JARs)
```

### 2. Plugin load order

Paper resolves load order automatically via `depend`/`softdepend` in each `plugin.yml`.
The order is:

| Priority | Plugin | Role |
|---|---|---|
| 1 | `kmc-core` | Shared domain models and API interfaces |
| 2 | `kmc-storage` | Database abstraction layer |
| 3 | `kmc-stats` | Statistics and achievement service |
| 4 | `kmc-game-api` | Base game manager and plugin template |
| 5 | `kmc-tournament-engine` | Tournament lifecycle engine |
| 6 | `KMCCore` | Main plugin — teams, points, lobby, commands |
| 7+ | All 13 game plugins | Any order |

### 3. First startup

Start the server. All plugins generate their default `config.yml` files. You'll see:

```
[KMCCore] enabled
[SkyWars] enabled
[bingo] enabled
...
```

Stop the server and configure (see next steps).

### 4. Set up the lobby

Build or paste your lobby world. Then in-game, stand at the spot where
players should spawn between games and run:

```
/kmclobby set
```

Test it:
```
/kmclobby tp        ← teleports you to lobby
/kmclobby tpall     ← teleports ALL online players to lobby
```

### 5. Create teams

Eight teams are pre-configured in `plugins/KMCCore/config.yml` (Dutch-themed
animal names with colors). You can use them as-is, rename them, or create
your own:

```
/kmcteam create <id> <"Display Name"> <COLOR>
```

Example:
```
/kmcteam create red   "Red Team"   RED
/kmcteam create blue  "Blue Team"  BLUE
/kmcteam create green "Green Team" GREEN
```

Assign players:
```
/kmcteam add <team-id> <player>
```

Or auto-distribute all online players randomly:
```
/kmcrandomteams all confirm
```

### 6. Set up each mini-game's arena

Each game needs a one-time arena setup. Skip games you don't plan to use —
they won't break anything if not configured. Setup details are in the
[per-game guides](#per-game-setup-guides) below.

### 7. Configure Discord (optional)

Add your webhook URL to `plugins/KMCCore/config.yml`:

```yaml
discord:
  webhook-url: "https://discord.com/api/webhooks/..."
```

### 8. Run a test

Once 2+ games have arenas configured:

```
/kmctournament start
```

To skip a game during testing:
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

### Round multipliers

The tournament runs 8 rounds. Placement points (not kills) are multiplied
by the current round multiplier — so later rounds are far more decisive:

| Round | Multiplier |
|---|---|
| 1 | ×1.0 |
| 2 | ×1.5 |
| 3 | ×2.0 |
| 4 | ×2.5 |
| 5 | ×3.0 |
| 6 | ×3.5 |
| 7 | ×4.0 |
| 8 | ×5.0 |

Kill points are always flat (no multiplier) — aggression is always valuable.

### Game flow per round

```
[Lobby Phase]
  ↓  Players in lobby — vote on next game (/kmcvote)
[Vote resolved → game selected]
  ↓  Countdown starts, players freeze in arena
[Grace period — 15 seconds]
  ↓  Game goes live, points accumulate
[Game ends]
  ↓  Final standings broadcast, Discord post fires
[Back to lobby]
  ↓  Round counter increments, repeat
```

### Manual control (for hosts)

If you want to pick games manually instead of voting:

```
/kmcgame set <game_id>
/kmcgame start
```

Available game IDs: `skywars`, `survival_games`, `quake_craft`, `spleef`,
`tgttos`, `mob_mayhem`, `tnt_tag`, `elytra_endrium`, `parkour_warrior`,
`the_bridge`, `adventure_escape`, `bingo`, `lucky_block`

### Skipping a stuck game

```
/kmcgame forceskip
```

Aborts the current game, returns players to lobby, and continues the
rotation as if the game ended normally.

### Automation engine

```
/kmcauto start     ← begin automatic rotation
/kmcauto pause     ← pause between games
/kmcauto resume    ← continue
```

---

## How the tournament works (player view)

Between every game, all players return to the central **Lobby**. The lobby
is a protected zone — no PvP, no block breaking.

Before each game there is a **ready-up** phase. Players confirm they are
ready, then a 15-second countdown fires and everyone teleports to the arena
with their kit already in their inventory. Players are frozen in place until
the countdown ends.

Once the game starts, points are earned live and shown on the **boss bar**
at the top of the screen. The **sidebar scoreboard** (right side) always
shows total team points.

After the game everyone returns to the lobby and results are announced.
This repeats until all rounds are complete. The team with the highest
total points at the end wins.

---

## Teams

- Every player is assigned to a team before the tournament starts.
- Teams have a **name**, a **display color**, and a tag shown in chat and
  on the scoreboard.
- Team membership is fixed for the whole tournament.
- Team chat: `/tc <message>` — only your teammates see it.
- Points are tracked both per-player and per-team. A team's total is the
  sum of all its members.
- Team color shows up in: sidebar scoreboard, tab list, nametags above
  heads, chat messages, and the boss bar during games.

### Pre-configured teams

Eight teams come ready to use out of the box:

| Team | Color |
|---|---|
| Rode Ratten | Red |
| Oranje Otters | Gold |
| Gele Gnoes | Yellow |
| Groene Goudvissen | Green |
| Blauwe Bavianen | Blue |
| Paarse Palingen | Dark Purple |
| Roze Rendieren | Light Purple |
| Witte Wespen | White |

Rename or recolor them freely in `plugins/KMCCore/config.yml` before the
tournament starts.

---

## Points & scoring

All point values are configured in `plugins/KMCCore/points.yml`. You can
change any value there and run `/kmcgame reload` to apply mid-test.

### Base values (before round multiplier)

| Action | Points |
|---|---|
| Kill a player | 50 |
| 1st place finish | 500 |
| 2nd place finish | 400 |
| 3rd place finish | 325 |
| 4th place finish | 275 |
| 5th place finish | 225 |
| Lower placements | decreasing curve down to 10 |
| Team 1st place | 1 000 |
| Team 2nd place | 600 |
| Team 3rd place | 300 |
| Team 4th place | 100 |
| Double kill bonus | +25 |
| Triple kill bonus | +75 |
| Mega kill (5+) bonus | +150 |

Placement points **are** multiplied by the round multiplier.
Kill points are **not** — they are always flat.

### Game-specific bonuses

| Game | Action | Points |
|---|---|---|
| The Bridge | Score a goal | 150 |
| The Bridge | Team goal assist | 75 (team share) |
| Bingo | Complete a square | 25 |
| Bingo | Complete a line | 100 per team member |
| Lucky Block | Lucky bonus loot event | 50 |

---

## Per-game setup guides

Each game has its own command prefix. All admin commands require OP or the
`<game>.admin` permission.

Every game supports:
```
/<game> start
/<game> stop
/<game> status
/<game> reload
```

`reload` re-reads the per-game `config.yml` without restarting the server.

---

### SkyWars

Sky islands with chests. Last team alive wins.

**Setup:**
```
/skywars setworld <world-name>
/skywars setmiddle              ← stand at center of the map
/skywars setmidradius 50        ← border radius in blocks
/skywars setvoidy 0             ← Y-level below which players die
/skywars addisland              ← stand on each island spawn, repeat per slot
/skywars listislands            ← verify
/skywars stockchests            ← test chest stocking
```

**Run:** `/skywars start`

---

### Survival Games

Hunger Games style. Pedestals at start, world border shrinks for deathmatch.

**Setup:**
```
/survivalgames setworld <world-name>
/survivalgames setcornucopia    ← stand at center
/survivalgames addpedestal      ← stand on each starting pedestal, repeat
/survivalgames setborder 200    ← initial border radius
/survivalgames setvoidy 0
/survivalgames stockchests
```

**Run:** `/survivalgames start`

---

### QuakeCraft

Railgun PvP — first team to the kill limit wins.

**Setup:**
```
/quakecraft setworld <world-name>
/quakecraft setspawn            ← stand on each spawn point, repeat
                                   (players are randomly assigned on respawn)
/quakecraft setpowerup damage_boost   ← optional powerup locations
/quakecraft setpowerup speed_boost
```

Kill streaks (×3, ×5, ×7, ×10) and multi-kill bonuses are in
`plugins/QuakeCraft/config.yml`.

**Run:** `/quakecraft start`

---

### Spleef

Last alive on a snow floor. Break blocks under opponents.

**Setup:**
```
/spleef setworld <world-name>
/spleef setlayer                ← stand on the snow floor to set the breakable Y-level
/spleef setvoidy 0
/spleef addspawn                ← stand on each spawn point, repeat
```

**Run:** `/spleef start`

---

### TGTTOS

Race to the other side — multi-map sequence with start/finish lines.

**Setup (repeat for each map you want in rotation):**
```
/tgttos createmap <id>          ← e.g. /tgttos createmap forest
/tgttos editmap <id>            ← enter edit mode
/tgttos name "Forest Sprint"
/tgttos world <world-name>
/tgttos addspawn                ← stand on each starting spawn, repeat
/tgttos voidy 0
/tgttos commit                  ← save and exit edit mode

/tgttos listmaps                ← verify all maps
```

**Run:** `/tgttos start`

---

### Mob Mayhem

Wave-based co-op survival. Each team gets a clone of a template world.

**Setup:**
```
/mobmayhem settemplate <template-world>  ← template is cloned per team at game start
/mobmayhem setspawn                       ← stand at spawn point in the template
/mobmayhem addmobspawn                    ← add mob spawn points, repeat
```

**Run:** `/mobmayhem start`

---

### TNT Tag

Hot-potato with TNT. Survive each round — tag others to pass the bomb.

**Setup:**
```
/tnttag setworld <world-name>
/tnttag setvoidy 0
/tnttag addspawn                ← repeat for each spawn point
```

**Run:** `/tnttag start`

---

### Elytra Endrium

Elytra-only race through hoops and checkpoints.

**Setup:**
```
/elytraendrium setworld <world-name>
/elytraendrium setlaunch                        ← stand at launch pad
/elytraendrium cp <name>                        ← stand at each checkpoint
/elytraendrium points 8                         ← set CP point value
/elytraendrium boost <id> FORWARD 2.5           ← add a boost hoop (type, strength)
/elytraendrium listcp                           ← verify checkpoints
/elytraendrium listboosts                       ← verify boost hoops
```

**Run:** `/elytraendrium start`

---

### Parkour Warrior

Solo parkour with stages and checkpoints.

**Setup:**
```
/parkourwarrior setworld <world-name>
/parkourwarrior setstart                        ← stand at start
/parkourwarrior cp <name>                       ← add a checkpoint at your location
/parkourwarrior difficulty EASY                 ← EASY / MEDIUM / HARD
/parkourwarrior stage 2                         ← set stage number for the last CP
/parkourwarrior points 12                       ← override per-CP points (optional)
/parkourwarrior powerup speed 5                 ← add a powerup (type, strength)
/parkourwarrior listcp                          ← verify
```

Difficulty point values (EASY +8 / MEDIUM +12 / HARD +15) are in
`plugins/ParkourWarrior/config.yml` under `points.by-difficulty`.

**Run:** `/parkourwarrior start`

---

### The Bridge

2v2 / 4v4 bridge battle — score goals by jumping into the opponent's hole.

**Setup:**
```
/bridge setworld <world-name>
/bridge setvoidy 0
/bridge createteam red
/bridge editteam red
/bridge name "Red Side"
/bridge color RED
/bridge wool RED_WOOL           ← wool block this team places
/bridge spawn                   ← stand at team spawn
/bridge commit

/bridge createteam blue
/bridge editteam blue
... repeat ...

/bridge listteams               ← verify
```

Goal detection is based on players entering the void below the goal hole.

**Run:** `/bridge start`

---

### Adventure Escape

Puzzle escape room with effect-block triggers.

**Setup:**
```
/adventure setworld <world-name>
/adventure setspawn
/adventure setstartline <pos1> <pos2>
/adventure setfinishline <pos1> <pos2>
/adventure setlaps 3
/adventure setcheckpoint <name>                         ← stand at CP
/adventure setcheckpointtrigger <name> <pos1> <pos2>    ← define trigger box
/adventure setoutofbounds <cp> <oob_name> <pos1> <pos2> ← respawn zone
/adventure listcheckpoints
```

Fastest escape earns a bonus. Out-of-bounds zones respawn players to
their last checkpoint.

**Run:** `/adventure start`

---

### Bingo

Teams race to complete a shared 5×5 bingo card by collecting items.

**Setup:**
```
# 1. Create or designate a survival-style template world
/bingo settemplate <world-name>

# 2. Stand at the player spawn point
/bingo setspawn

# 3. Preview a generated card without starting
/bingo card

# Done!
```

The plugin clones the template world per game so the original is never
modified. SafeSpawnHelper automatically places players on solid ground
4+ blocks apart.

First team to complete a **line** (row, column, or diagonal) wins.
If time runs out, most completed squares wins.

**Run:** `/bingo start`

---

### Lucky Block

PvP arena where lucky blocks drop random loot.

Lucky Block uses team spawns from KMCCore — no separate arena commands.

**Setup:**
1. Build a PvP arena
2. Place lucky blocks in the arena (configure which material counts in
   `plugins/LuckyBlock/config.yml`)
3. Set team spawns through KMCCore (`/kmcteam spawn set <team>`)

**Run:** `/luckyblock start`

---

## Scoring reference

Quick reference for all point values. All values are before the round
multiplier (which applies to placement points only).

### Global (all games)

| Action | Points |
|---|---|
| Kill | 50 |
| Double kill bonus | +25 |
| Triple kill bonus | +75 |
| Mega kill (5+) bonus | +150 |
| 1st place | 500 |
| 2nd place | 400 |
| 3rd place | 325 |
| 4th place | 275 |
| 5th place | 225 |
| 6th–31st | decreasing curve |
| 32nd+ | 10 |
| Team 1st | 1 000 |
| Team 2nd | 600 |
| Team 3rd | 300 |
| Team 4th | 100 |

### The Bridge

| Action | Points |
|---|---|
| Score a goal | 150 |
| Team goal share | 75 |

### Bingo

| Action | Points |
|---|---|
| Complete a square | 25 |
| Complete a line | 100 per team member |

### Lucky Block

| Action | Points |
|---|---|
| Lucky bonus event | 50 |

All values are configurable in `plugins/KMCCore/points.yml` and each
game's own `config.yml`.

---

## Admin commands cheat sheet

### Tournament & rotation

| Command | What it does |
|---|---|
| `/kmctournament start` | Begin a tournament |
| `/kmctournament stop` | End early |
| `/kmctournament status` | Show progress |
| `/kmctournament reset` | Wipe points only |
| `/kmctournament hardreset` | Wipe everything ⚠️ |
| `/kmcround set <n>` | Force the round number |
| `/kmcgame set <id>` | Pre-select next game |
| `/kmcgame start` | Launch the selected game |
| `/kmcgame skip` | Skip current game (give result anyway) |
| `/kmcgame forceskip` | Abort current game, return to lobby |
| `/kmcgame list` | Show all games and their statuses |
| `/kmcvote` | Open the voting GUI |
| `/kmcauto start` | Start automation engine |
| `/kmcauto pause` | Pause between games |
| `/kmcauto resume` | Resume automation |

### Teams

| Command | What it does |
|---|---|
| `/kmcteam create <id> <name> <color>` | Create team |
| `/kmcteam delete <id>` | Delete team |
| `/kmcteam add <team> <player>` | Assign player to team |
| `/kmcteam remove <player>` | Remove player from any team |
| `/kmcteam list` | Show all teams |
| `/kmcteam info <team>` | Member list + points |
| `/kmcrandomteams all confirm` | Auto-distribute online players |

### Points

| Command | What it does |
|---|---|
| `/kmcpoints set <player\|team> <id> <amount>` | Set points directly |
| `/kmcpoints add <player\|team> <id> <amount>` | Add points |
| `/kmcpoints remove <player\|team> <id> <amount>` | Subtract points |
| `/kmclb teams [page]` | Team leaderboard |
| `/kmclb players [page]` | Player leaderboard |
| `/kmcstats [player]` | Open stats GUI |

### Lobby & arena

| Command | What it does |
|---|---|
| `/kmclobby set` | Set the inter-game lobby spawn |
| `/kmclobby tp` | Teleport yourself to lobby |
| `/kmclobby tpall` | Teleport ALL players to lobby |
| `/kmcarena set <key>` | Generic arena helpers (per-game) |

### Simulation & recovery

| Command | What it does |
|---|---|
| `/event simulate <rounds> <players>` | Run a fake tournament for math testing |
| `/event snapshot` | Take a snapshot of current state |
| `/event listsnapshots` | Show available snapshots |
| `/event rollback <snapshot-id>` | Restore from a snapshot |

### Hall of Fame & NPCs

| Command | What it does |
|---|---|
| `/kmchof setnpc <stat> <fancyNpcId>` | Bind a FancyNPC to display a stat |
| `/kmchof refresh` | Force-refresh HoF NPC skins/names |
| `/kmchof list` | Show current HoF config |
| `/kmclobbynpc add` | Add a lobby game-launcher NPC |
| `/kmclobbynpc remove` | Remove a lobby NPC |
| `/kmclobbynpc list` | List all lobby NPCs |
| `/kmcnpc create` | Create a leaderboard NPC |

### Quality of life

| Command | What it does |
|---|---|
| `/kmcready` | Mark yourself ready |
| `/kmcready force` | Force-start ready phase |
| `/kmcprefs` | Open personal preferences |
| `/kmchealth` | Show TPS / RAM / online count |
| `/kmcmap list` | List available maps for current game |
| `/kmcmap set <name>` | Queue a specific map |

### Per-game shortcuts

Every game supports these four commands:

```
/skywars        start | stop | status | reload
/survivalgames  start | stop | status | reload
/quakecraft     start | stop | status | reload
/spleef         start | stop | status | reload
/tgttos         start | stop | status | reload
/mobmayhem      start | stop | status | reload
/tnttag         start | stop | status | reload
/elytraendrium  start | stop | status | reload
/parkourwarrior start | stop | status | reload
/bridge         start | stop | status | reload
/adventure      start | stop | status | reload
/bingo          start | stop | status | reload
/luckyblock     start | stop | status | reload
```

`reload` re-reads the per-game `config.yml` without restarting the server.
Useful for tweaking scoring values mid-test.

---

## Player commands

| Command | What it does |
|---|---|
| `/tc <message>` | Team-only chat |
| `/kmcstats` | Your personal stats GUI |
| `/kmcstats <player>` | Another player's stats |
| `/kmclb` | Tournament leaderboard |
| `/kmcvote` | Vote for the next game (when vote is open) |
| `/kmcachievements` | Your unlocked achievements |
| `/kmcprefs` | Personal preferences (scoreboard, chat style, etc.) |

---

## Troubleshooting

### `/event` is not a command

The `event:` entry is missing from `KMCCore/src/main/resources/plugin.yml`.
Add it and rebuild, or reload the plugin.

### Points are doubled for kills

Two listeners are firing for the same kill. The global `PlayerKillListener`
in KMCCore should skip games that handle their own kill credit. Check
the switch statement in that file and make sure your game's ID is listed
as an exclusion.

### Bingo world doesn't generate

`/bingo settemplate` was never run, or the template world isn't loaded.
Check `plugins/Bingo/config.yml` for `world.template-name`. If blank, run:

```
/bingo settemplate <existing-world-name>
```

If already set, check `latest.log` for "Failed to copy world files" or
"Failed to load cloned world".

### Compile error: "cannot find symbol"

Build in dependency order:

```
mvn clean install -pl KMCCore -am
mvn clean install
```

KMCCore must build before any game plugin.

### Plugin not loading at startup

Check `latest.log` for stack traces. Common causes:
- KMCCore failed to enable (check that first)
- Missing soft-dependency (FancyNpcs, WorldEdit)
- Wrong Java version — must be Java 21

### "Arena not ready" when starting a game

Run the game's status command to see exactly what is missing:

```
/skywars status
/survivalgames status
/bingo status
```

It lists every required setting and whether it is configured.

### `/kmcteam create` says team already exists

Database is out of sync. Try:

```
/kmctournament hardreset
```

⚠️ This wipes ALL tournament state. Do not run mid-event.

### Player can't be teleported to spawn

Check that the spawn world is loaded:

```
/mv load <world-name>     (if using Multiverse)
```

Or add the world to `bukkit.yml` → `worlds` to auto-load.

### Tournament stops progressing between games

The automation engine may be stuck. Restart it:

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

- Each per-game guide assumes you've completed [First-time install](#first-time-install)
- For point values, see [Scoring reference](#scoring-reference) or `plugins/KMCCore/points.yml`
- For all commands, see [Admin commands cheat sheet](#admin-commands-cheat-sheet)
- Check `plugins/<game>/config.yml` to fine-tune any value, then `/<game> reload`
- When something behaves unexpectedly, check `latest.log` first — most issues
  leave a clear stack trace there

---

*KMC Tournament Platform — built for competitive Minecraft events.*

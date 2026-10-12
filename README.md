# KMC Tournament — Wiki & Setup Guide

A multi-game tournament system for Paper 26.3 / Java 25. One core plugin
manages teams, points, and tournament flow; 16 mini-game plugins plug in
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
- [Special moments](#special-moments)
- [The tutorial hub](#the-tutorial-hub)
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
  - [Block Party](#block-party)
  - [Speed Build](#speed-build)
- [Scoring reference](#scoring-reference)
- [Admin commands cheat sheet](#admin-commands-cheat-sheet)
- [QuakeCraft arsenal & gadgets](#quakecraft-arsenal--gadgets)
- [Player commands](#player-commands)
- [Troubleshooting](#troubleshooting)

---

## What you get

### Core features (KMCCore)

- **Team system** — players assigned to colored teams, persistent across games
- **Tournament mode** — chains all 16 games with round multipliers (×1 up to ×5)
- **Points system** — all per-game scoring funnels through a central API; team aggregation automatic
- **Leaderboards** — `/kmclb teams` and `/kmclb players` (paginated)
- **Hall of Fame** — top-stat NPCs permanently displayed in lobby (kills / wins / streak)
- **Achievements** — built-in achievements with server-wide unlock broadcasts
- **Tutorial hub** — `/tutorial` explains every system (points, teams, voting,
  achievements, your own progress) and links straight into the real GUI for each
- **Stats GUI** — multi-page per-player stats with `/kmcstats`
- **Tournament history** — completed tournaments archived and queryable
- **Voting** — players vote for the next game via `/kmcvote`
- **Automation** — auto-progresses the rotation when each game ends
- **Per-game start presentation** — teleport+freeze, intro title, an arena
  camera flyover (auto-generated if none is recorded), tutorial tips, then a
  countdown — see [Special moments](#special-moments)
- **Golden Hour** — one random, unannounced round per tournament doubles every point award
- **Comeback bonus** — the last-place team gets a scoring boost once a real gap has formed
- **Fan Favorite vote** — players vote for their favourite moment after the tournament ends
- **Easter egg NPCs** — hidden lobby NPCs with their own line; find them all for an achievement
- **Simulation** — test scoring math without running real games (`/event simulate`)
- **Snapshots / rollback** — recover from disasters with `/event rollback`
- **Map rotation** — multi-map games (TGTTOS, Bridge, etc.) cycle automatically
- **Lobby NPCs** — clickable game-launch NPCs in lobby
- **Player preferences** — opt in/out of features via `/kmcprefs`
- **Server health monitor** — TPS, RAM, online count — `/kmchealth`
- **Discord integration** — automatic webhook posts for game results and achievements

### The 16 mini-games

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
| **Block Party** | Colour-elimination, last standing | Solo with team scoring |
| **Speed Build** | Solo schematic-accuracy build challenge | Solo |
| **Hole in the Wall** | Dodge giant walls sweeping over a round platform, multi-round | Solo with team scoring |
| **Formula MC** | Boat racing over a hand-built ice circuit — laps, checkpoints, no collisions | Solo with team scoring |

---

## Prerequisites

| Software | Version |
|---|---|
| Paper | 26.3 |
| Java | 25 |
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
... (all 16 game JARs)
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
| 7+ | All 16 game plugins | Any order |

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

Available game IDs: `team_skywars`, `survival_games`, `quake_craft`, `spleef_teams`,
`tgttos`, `mob_mayhem`, `tnt_tag`, `elytra_endrium`, `parkour_warrior`,
`the_bridge`, `adventure_escape`, `bingo_teams`, `lucky_block`, `block_party`,
`speed_build`

(These are the exact keys from `games.list` in `plugins/KMCCore/config.yml` —
the source of truth for what `/kmcauto` and `/kmcgame set` actually accept.)

### Skipping a stuck game

```
/kmcgame forceskip
```

Aborts the current game, returns players to lobby, and continues the
rotation as if the game ended normally.

### Automation engine

```
/kmcauto start     ← begin automatic rotation (runs the FULL presentation flow)
/kmcauto pause     ← pause between games
/kmcauto resume    ← continue
```

Run by a player, `/kmcauto start` first opens a **setup menu** — toggle
which games are in this tournament's rotation, adjust the intermission and
voting durations, optionally schedule the start for a later clock time or
delay, then click **START TOERNOOI NU** to actually launch. Use
`/kmcauto start force` to skip the menu and start immediately (also skips
the validation check below) — this is what console/scripted starts and the
`/kmcauto schedule` auto-start use, since there's no player to show a menu to.

`/kmcauto start` is the main entry point. It drives the complete championship
flow end-to-end:

```
Opening ceremony → Team showcase → Tournament overview
   → Voting → Game intro → Arena flyover → Countdown → GAME
   → Winner ceremony → (repeat game if configured) → next game
   → Round-end ceremony → ... → Closing ceremony
```

Each stage shows **ceremony text** (from `ceremonies.yml`) and plays a
**cinematic camera route** (from `cameras.yml`) if one is configured. Any
stage with no ceremony/route configured is simply skipped — the tournament
always continues safely.

> There are two engines in the codebase: the V1 `/kmcauto` (recommended,
> fully featured) and a V2 `/kmctournament` engine. Both now run the same
> ceremonies, cinematics, and repetitions. Use **`/kmcauto`** unless you have
> a specific reason to use the V2 engine — don't run both at once.

---

## How the tournament works (player view)

Between every game, all players return to the central **Lobby**. The lobby
is a protected zone — no PvP, no block breaking.

Before each game there is a **ready-up** phase, then players are teleported
to the arena. Several games (MobMayhem, Block Party, The Bridge, Parkour
Warrior, Lucky Block, Speed Build — more being added over time) run a full
start presentation at this point instead of a bare countdown — see
[Special moments](#special-moments). The rest still use a plain countdown.
Players are frozen in place until their game's countdown/presentation ends,
with their kit already in their inventory.

Once the game starts, points are earned live and shown on the **boss bar**
at the top of the screen. The **sidebar scoreboard** (right side) always
shows total team points.

After the game everyone returns to the lobby and results are announced.
This repeats until all rounds are complete. The team with the highest
total points at the end wins.

---

## Special moments

A few tournament-wide systems add drama and replay value on top of the
base points/placement scoring:

| Moment | What happens | Config |
|---|---|---|
| **Golden Hour** | One random round per tournament (picked fresh each `/kmctournament start`, never announced in advance) doubles every point award that round — including kills, which are normally flat. Revealed right before that round's game actually launches. | `plugins/KMCCore/config.yml` → `golden-hour` |
| **Comeback bonus** | Once the leading team is at least `min-gap` points ahead, the last-place team gets a `×1.25` boost on every point award — keeps a lopsided tournament interesting. | `plugins/KMCCore/config.yml` → `comeback-bonus` |
| **Fan Favorite vote** | After the tournament ends, players vote for their favourite moment of the event. | `FanFavoriteManager` (KMCCore) |
| **MVP crown** | A rotating gold particle crown follows the current points leader during a live game. | automatic, every game (`BaseGameManager`) |
| **Easter egg NPCs** | Hidden lobby NPCs (`/kmclobbynpc spawn easter_egg`), each with its own fixed line. Find every spawned one for the "Easter Egg Hunter" achievement — tracked per player, persists across restarts. | `plugins/KMCCore/config.yml` → `easter-egg-npc` |
| **Game start presentation** | Teleport+freeze → intro title → arena camera flyover (auto-generated around the arena if you haven't recorded one via `/kmccamera`) → tutorial tips → countdown → GO. Rolled out per game — see each game's own `config.yml` → `start-flow` / `start-sequence`. | per-game `config.yml` |

---

## The tutorial hub

```
/tutorial            (alias /kmctutorial)
```

Opens a menu covering: Basis & Teams, Puntensysteem, Speciale momenten
(the table above), Spellen (links into `/kmchelp`'s per-game explanations),
Stemmen & rondes, Achievements & Records, Jouw voortgang (profile/MVP/
momentum), and Taal/Language. Every category links straight into the real
GUI for that system instead of duplicating its data — e.g. "Achievements"
opens the real achievements view, it doesn't re-render a copy.

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

Sky islands with chests. Last team alive wins. If a player hits someone
within 10 seconds before a teammate finishes them off, that assist gets a
share of the kill points (`assist-fraction`, default 20% — set to 0 to
disable). SkyWars is currently the only game with assist points wired in.

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
Once the deathmatch trigger time hits, a one-time "🍗 THE FEAST" chest
appears at the cornucopia stocked with top-tier loot (`feast-enabled`,
`loot.feast`).

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

Railgun PvP — one shot, one kill, first to the kill limit wins. QuakeCraft has
the deepest toolkit of any game: a 16-piece arsenal, jump pads, a rarity system
and a fully configurable sound engine. See the dedicated
[QuakeCraft arsenal & gadgets](#quakecraft-arsenal--gadgets) section below.

**Setup:**
```
/quakecraft setworld <world-name>
/quakecraft setspawn            ← stand on each spawn point, repeat
                                   (players are randomly assigned on respawn)
/quakecraft setpowerup <name>   ← stand where powerups should spawn, repeat
/quakecraft setjumppad [height] ← turn the block you're on into a jump pad
```

Kill streaks (×3, ×5, ×7, ×10) and multi-kill bonuses are in
`plugins/QuakeCraft/config.yml`.

**Run:** `/quakecraft start`

> Also runs the full start presentation (intro → arena flyover → tutorial →
> countdown) via its own hand-built flow — one of the two games (with TNT
> Tag) this was modelled on before being generalised for other games.

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
Occasionally a map rolls "🌫 Fog of War" (blurs everyone's vision for the
whole map, `fog-of-war-chance`). Whoever is actually in last place gets a
rubber-banding speed boost that follows the real last-place racer live
(`rubber-banding`), and a bossbar/actionbar always shows the current leader
or your gap to them.

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

Wave-based co-op survival. Each team gets its own cloned copy of a template
world and fights through waves of mobs independently — the team that
survives the most waves wins. Difficulty escalates every wave (more mob
types, two boss waves by default, random modifiers like Blood Moon,
Double Mobs, Low Visibility), and points scale with mob type and wave
reached.

**Setup:**
```
/mm settemplate <template-world>  ← template is cloned per team at game start
/mm setspawn                      ← stand at spawn point in the template (open air!)
/mm addmobspawn                   ← add mob spawn points, repeat (need 4+, open air)
/mm addpowerupspawn                ← optional — add spots for speed/strength/heal pickups
/mm status                        ← verify readiness before testing
```

> **Open-air check:** the cloned arena is a byte-for-byte copy of the
> template, so a spawn point recorded underground stays underground in
> every clone. `/mm start` now warns loudly (console + chat) if a player or
> mob spawn point turns out to be inside solid terrain.

**Waves:** 10 built-in waves by default. Override them entirely with a
`waves:` section in `plugins/MobMayhem/config.yml` (`waves.<n>.mobs.<TYPE>:
<count>`) — leave it unset/commented to keep the built-in progression.

**Achievements:** Survivor (wave 10), Untouchable (wave 5 with no deaths),
Exterminator / Mob Slayer (50 / 100 kills in one game), Boss Slayer, Last
Stand (last player standing).

**Optional — void everything outside the arena:**
```yaml
arena:
  voidify-margin: 40   # 0 = disabled (default)
```
Clears everything outside the arena's bounding box to air in every cloned
world, so mobs/players can't wander into the rest of the template's
terrain. Never touches the template itself.

**Run:** `/mm start` (aliases `/mobmayhem`, `/mayhem`)

---

### TNT Tag

Hot-potato with TNT. Survive each round — tag others to pass the bomb.
The world border shrinks progressively every round
(`progressive-shrink-percent`, floored at `progressive-shrink-min-radius`),
separate from the dedicated Final Showdown shrink once only 2 players remain.

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

### Block Party

Colour-elimination — stand on the announced colour before time runs out;
wrong colour (or no colour) means you fall through the void. Last player
standing wins. Every alive player gets the round's colour as a plain,
unusable item (`BLAUW`, `ROOD`, ... — never "concrete"/"beton") in a fixed
hotbar slot, replaced each round, as a visual reference alongside the
title/actionbar/scoreboard. Only players currently on an active KMC team
take part. The floor regenerates every round with a fresh random
Voronoi-style colour pattern, and gets harder (smaller clusters, less
time) as rounds progress. From round 5 onward, random "chaos events" can
modify a round (low gravity, darkness, a fake colour hint, etc.).

**Colour palette:**
- **Round 1** is a fixed layout — either a pattern you build and capture
  yourself (`/blockparty presetfloor`, like a schematic), or, if none is
  captured, a random floor using only Yellow/White/Light Gray/Black concrete.
- **Every round after that** always draws from all 16 concrete colours,
  fully random each time — never a captured layout, no exceptions. The
  target colour tries not to repeat the previous round's, and always has
  room for every player still alive (plus a configurable floor,
  `minimum-target-blocks`).

Fires `BlockPartyGameStartEvent`, `BlockPartyRoundStartEvent`,
`BlockPartyRoundEndEvent`, `BlockPartyPlayerEliminateEvent`,
`BlockPartyPlayerSurviveEvent`, and `BlockPartyGameEndEvent` so other
systems can hook into specific moments, not just the final result.

**Setup:**
```
/blockparty pos1         ← stand at one corner of a flat floor area
/blockparty pos2         ← stand at the opposite corner
/blockparty spectator    ← stand where eliminated players should watch
/blockparty voidy        ← stand below the floor, at "fell through" height
```

Minimum floor size is 64 blocks. Check readiness with `/blockparty status`.

**Optional — fixed round 1 layout:**
```
/blockparty presetfloor  ← build a pattern by hand, then capture it as round 1's layout
/blockparty clearpreset  ← remove it (round 1 falls back to the random 4-colour default)
```

**Run:** `/blockparty start`

---

### Hole in the Wall

Reaction-survival on a round platform over the void. Giant straight walls slide
across the platform from changing sides; each has gaps, or is a bar you must
jump over or sneak under. A wall that hits you shoves you toward the edge — fall off and you're out. Several
rounds (default 3); the walls speed up over the round, special events twist it
(faster walls, walls close together, smaller holes, ...).

- **Wall types:** `NORMAL` (one hole), `DOUBLE_HOLE`, `SPLIT` (several small holes),
  `LOW` (jump over it), `HIGH` (sneak under it), `FAST`. Types, patterns,
  the speed curve and the events are all in `config.yml` — a new variant is
  usually just a new entry. Patterns (`alternating_height`, `mixed`, ...) chain
  walls from one direction; the next pattern comes from another side.
- **Collision is exact, not block-based:** your hitbox (a circle, 1.8 tall
  standing / 1.5 sneaking) is tested against the wall's real geometry every
  tick, and the whole tick's movement is swept, so a fast wall can't skip over
  you and grazing the corner of a hole is not a hit. The walls are display
  entities, so it's cheap even with many players.
- **Every wall is reachable:** the main gap is always placed within running
  distance of where the living players are, and gaps get wider with more
  players alive.
- **Points:** each round awards points by placement (survivors share 1st —
  `points.round-placement`); the total after the last round decides the final
  standing, paid out via the normal KMC placement scoring. Achievements: Needle
  Threader (25 walls in a round), Last Through the Wall, Clean Sweep.

**Setup:**
```
/hitw setcenter     ← stand on the middle of where the platform should be
/hitw setradius 18  ← platform radius (blocks)
/hitw build         ← builds the platform (also rebuilt at every game start)
/hitw setspectator  ← optional: where eliminated players watch
```

Look at a wall without playing: `/hitw testwall <type> [degrees]` (harmless demo;
`/hitw types` lists them, `/hitw clearwalls` removes them). Quick solo test:
`/kmctest hole_in_the_wall`, `/kmcbot add 3`, `/hitw start`.

**Run:** `/hitw start`

---

### Formula MC

Boat race over a circuit you build yourself. Everybody gets a boat on the grid, a 3-2-1-GO start,
then races a configurable number of laps through ordered checkpoints. **Boats and players never collide**
(team collision rules are switched off and every boat-entity collision is cancelled), other racers only show
when they are within 10 blocks, and 1-block ramps never stop a boat.

Setup (stand where it should be, each step is one command):

```
/fmc create <id> [name]   new circuit in this world
/fmc spawn add            one grid slot per command (your yaw = the way the boat faces)
/fmc pos1 + /fmc pos2     select a gate;  /fmc line = start/finish line,  /fmc cp add = next checkpoint
/fmc reset                safe reset spot for stuck racers
/fmc laps <n>             number of laps
/fmc start                run it   (/kmctest formula_mc builds a test circuit)
```

Scoring: placement points by finish order (unfinished racers rank by laps → checkpoints → distance),
team placement by each team's best racer, +25 for the fastest lap. Tuning lives in `FormulaMC/config.yml`.

---

### Speed Build

Solo, fully objective schematic-accuracy challenge. Each player builds up
to 10 schematics in sequence inside their own isolated region — no voting,
no human judging, score is a block-by-block comparison against the
schematic plus a time bonus for finishing under par. Requires WorldEdit or
FastAsyncWorldEdit.

**Setup:**
```
/speedbuild anchor                          ← stand at the min corner of player 0's build slot
/speedbuild spawn                           ← stand where players spawn / return when idle
/speedbuild gap 4                           ← blocks of empty space between player slots
/speedbuild addbuild <id> <schematic.schem> [difficulty] [weight] [naam...]
/speedbuild listbuilds                      ← verify (up to 10 builds)
```

Schematic files go in `plugins/KMCCore/schematics/` (shared WorldEdit
integration). `difficulty` (1-10) and `weight` scale that build's score
contribution; par time scales with difficulty too
(`par-base-seconds` + `par-per-difficulty` × difficulty in
`plugins/SpeedBuild/config.yml`).

**Run:** `/speedbuild start`

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

### Block Party

Uses its own placement curve instead of the global one above (base score
minus a per-place step, floored at a minimum):

| Action | Points |
|---|---|
| Placement | `250 − (place × 10)`, minimum 25 |
| Last-team-standing bonus | +150 (team) |

### Speed Build

Fully objective per-build score, no fixed point table — see
`plugins/SpeedBuild/config.yml`: `accuracy% × 100` minus 2 per
missing/incorrect block, plus up to 120 bonus for finishing under par time.

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
| `/kmcauto start` | Opens the setup menu (games/timers/schedule), then starts on confirm |
| `/kmcauto start force` | Skips the setup menu and validation, starts immediately |
| `/kmcauto pause` | Pause between games |
| `/kmcauto resume` | Resume automation |
| `/kmcauto schedule 20:00` | Auto-start the whole tournament at a clock time |
| `/kmcauto schedule in <minutes>` | Auto-start after a delay |
| `/kmcauto schedule status \| cancel` | Check or cancel a scheduled start |

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
| `/event simulate <rounds> <players>` | Runs a REAL tournament with fake bot players — same rotation/multiplier/endTournament() as a live event. Refuses if a real tournament is already active. Fewer rounds than `tournament.total-rounds` = ends early (no book, scores not reset), like a real `/kmctournament stop`. |
| `/kmctest <game\|list\|all confirm>` | Generates a throw-away test arena (high in the sky) for a game and configures it — no map needed. **Overwrites that game's arena settings.** |
| `/kmcbot add [n] \| remove <name\|all> \| list \| here` | Test players with no client: real server-side players that stand where you put them, fall, take damage and respawn. Count as online players for every game. |
| `/kmcauto skip` | Skip the opening presentation |
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

### Presentation & cinematics

| Command | What it does |
|---|---|
| `/kmccamera create <route> [desc]` | Start recording a camera route |
| `/kmccamera addpoint [ticks] [interp]` | Add a waypoint at your location |
| `/kmccamera removepoint` | Remove the last waypoint |
| `/kmccamera save` / `discard` | Save or throw away the recording |
| `/kmccamera preview <route>` | Preview a route (yourself only) |
| `/kmccamera info <route>` | Inspect a route's waypoints |
| `/kmccamera list` / `delete <route>` / `reload` / `stop` | Manage routes |
| `/kmcpresentation start <route> [player\|all]` | Force-play a route now |
| `/kmcpresentation skip` | Stop / skip all active cinematics |
| `/kmcpresentation status` / `routes` / `reload` | Inspect / reload routes |
| `/kmcceremonies info <phase>` | Show a ceremony phase's config |
| `/kmcceremonies duration <phase> <secs>` | Set a phase's duration |
| `/kmcceremonies title \| subtitle <phase> <text>` | Set title/subtitle |
| `/kmcceremonies addmsg \| setmsg \| delmsg \| clearmsg <phase>` | Edit chat lines |
| `/kmcceremonies list` / `reload` | List phases / reload from disk |
| `/kmcgame setorigin <gameId>` | Set arena schematic paste origin |
| `/kmcgame resetarena <gameId>` | Manually paste the arena schematic |
| `/kmcgame repetitions <gameId> <count>` | Set how many times a game repeats per round |

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

Every game supports `start | stop | status | reload`, **except Block Party
and Speed Build**, which don't have a `reload` subcommand yet (change their
`config.yml` and restart the plugin/server to apply edits):

```
/skywars        start | stop | status | reload
/survivalgames  start | stop | status | reload
/quakecraft     start | stop | status | reload
/spleef         start | stop | status | reload
/tgttos         start | stop | status | reload
/mm             start | stop | status | reload   (aliases /mobmayhem, /mayhem)
/tnttag         start | stop | status | reload
/elytraendrium  start | stop | status | reload
/parkourwarrior start | stop | status | reload
/bridge         start | stop | status | reload
/adventure      start | stop | status | reload
/bingo          start | stop | status | reload
/luckyblock     start | stop | status | reload
/blockparty     start | stop | status              (no reload)
/speedbuild     start | stop | status              (no reload)
```

`reload` re-reads the per-game `config.yml` without restarting the server.
Useful for tweaking scoring values mid-test.

---

## Presentation & cinematics

KMC runs as a professional championship event: ceremonies, camera flyovers,
title cards, and per-game intros — all driven automatically by `/kmcauto`.

### The three layers

| Layer | File | What it controls |
|---|---|---|
| **Ceremonies** | `ceremonies.yml` | Chat messages, titles, durations per phase |
| **Cinematics** | `cameras.yml` | Camera flyover routes per phase / game |
| **Repetitions** | `config.yml` (`games.list.<id>.repetitions`) | How many times a game plays per round |

All three are **optional** — anything unconfigured is skipped, and the
tournament continues normally.

> **Arena flyovers auto-generate.** If no `arena-<gameId>` route has been
> recorded, a short circular camera orbit around the arena is generated and
> saved automatically the first time it's needed — you get a flyover with
> zero setup, and can still record a nicer one later via `/kmccamera`
> (replaces the auto-generated one). Games running the shared start
> presentation (see [Special moments](#special-moments)) also play their
> arena flyover when started **directly** (`/<game> start`), not only
> through `/kmcauto` — previously flyovers only ever played as part of the
> automated tournament rotation.

### Camera routes — naming convention

Routes are matched to tournament moments by their ID:

| Route ID | Plays during |
|---|---|
| `opening` | Opening ceremony |
| `team-showcase` | Team introduction |
| `tournament-overview` | Game lineup reveal |
| `game-intro-<gameId>` | Before a specific game (e.g. `game-intro-team_skywars`) |
| `arena-<gameId>` | Arena flyover before that game starts |
| `winner-<gameId>` | After that game ends |
| `closing` | Tournament finale |

### Recording a route in-game

```
/kmccamera create opening "Opening flyover"
   [fly to your first camera position]
/kmccamera addpoint 100 smooth title=&6&lKMC TOURNAMENT subtitle=&eSeason 14
   [fly to the next position]
/kmccamera addpoint 80 ease_in_out actionbar=&716 players - 4 teams - 13 games
   [fly to the final position]
/kmccamera addpoint 60 ease_out title=&eLet the games begin!
/kmccamera save
/kmccamera preview opening        ← watch it back
```

- `addpoint [ticks] [interp]` — `ticks` = travel time TO this point (20 = 1s).
  `interp` = `linear`, `smooth`, `ease_in`, `ease_out`, `ease_in_out`.
- Overlay params (use `_` for spaces): `title=...`, `subtitle=...`, `actionbar=...`
- Players are put in spectator mode during a cinematic and fully restored after.

### Editing ceremony text

```
/kmcceremonies info opening                        ← see current config
/kmcceremonies title opening &6&lWELCOME!          ← set the title
/kmcceremonies addmsg opening &eGood luck teams!   ← add a chat line
/kmcceremonies duration opening 30                 ← hold for 30 seconds
/kmcceremonies reload
```

Placeholders: `{tournament_name}`, `{round}`, `{multiplier}`, `{team_count}`,
`{game_name}`, `{game_objective}`, `{winner}`.

### Game repetitions

Make a game play multiple times in a row, with points accumulating:

```
/kmcgame repetitions team_skywars 3
```

Now when SkyWars is chosen, it plays **3 times back-to-back** (arena reset
between each), all points counting toward the same round, before the
tournament moves on. The boss bar shows "ronde 2/3" during repeats.

### Skipping / emergency control

If a cinematic glitches or you need to bail out:

```
/kmcpresentation skip      ← stops ALL active cinematics, restores players
```

The tournament continues from where it was — cinematics never block the flow.

---

## QuakeCraft arsenal & gadgets

QuakeCraft is an instant-kill railgun shooter, but the depth comes from the
**powerups** that spawn around the arena. Everyone starts with the base
railgun + permanent Speed I; powerups are temporary upgrades you fight over.
Every value (uses, cooldown, radius, duration, rarity, sounds) lives in
`plugins/QuakeCraft/config.yml`.

### The 16-piece arsenal

**Weapons (instant-kill):**

| Weapon | Item | Notes |
|---|---|---|
| Railgun | Wooden Hoe | Base weapon, infinite ammo, one-shot |
| Shotgun | Iron Hoe | 5-pellet spread, short range |
| Sniper | Netherite Hoe | Long-range, tracer |
| Machine Gun | Golden Hoe | 3-shot bursts, fast |
| Bazooka | Diamond Hoe | Fired rocket, AoE explosion (2 uses) |
| Grenade | Bone | Lobbed, 2s fuse, AoE |
| Airstrike | Firework Rocket | **Legendary** — mark a spot, 6 instant-kill strikes rain down |

**Mobility:**

| Gadget | Item | Notes |
|---|---|---|
| Grappling Hook | Fishing Rod | Yank yourself toward a block |
| Impulse Cannon | Heavy Core | Knockback blast — launch enemies, or rocket-jump yourself (no kill) |
| Jump Pad Grenade | Slime Ball | Throw → temporary launch pad anyone can use |

**Tactical / control / info:**

| Gadget | Item | Notes |
|---|---|---|
| Proximity Mine | Tripwire Hook | Drops a mine; detonates when an enemy gets close |
| Smoke Bomb | Gunpowder | Sightline-blocking smoke cloud + blindness |
| Freeze Grenade | Packed Ice | AoE slow + crippled jump (no damage) |
| Flashbang | Echo Shard | Blinds only enemies **looking at** the blast |
| Recon Dart | Spectral Arrow | Hit an enemy → they glow through walls for your team |

**Deception:**

| Gadget | Item | Notes |
|---|---|---|
| Hologram Decoy | Armor Stand | A look-alike wearing your skin; enemies waste shots popping it |
| Mimic Device | Player Head | Visually disguise as another team (cosmetic only — real team unchanged) |

### Jump pads

Place launch pads anywhere to reach higher parts of the map. Location-based, so
the pad can be **any block** you build:

```
/qc setjumppad          → default height (4 blocks)
/qc setjumppad 7        → big 7-block launch
/qc setjumppad 5 0.8    → 5 high with a strong forward shove
/qc listjumppads        → list pads + their strength
/qc removejumppad       → remove the nearest pad
/qc clearjumppads       → wipe all
```

Each pad stores its **own** strength, so you can mix 3-block hops and 7-block
launches around the map.

### Powerup rarity

Each powerup has a `rarity:` — `common`, `rare`, `epic`, or `legendary`. Rare+
spawns **broadcast to the whole lobby** with the location and a stinger sound
(e.g. *"LEGENDARY powerup spawned: Airstrike @ mid"*). Spawn frequency is
controlled by the `powerup-spawning.weights` map.

### Configurable sound system

Every weapon/gadget event (fire, impact, kill-confirm, etc.) plays through a
configurable identifier under `sounds:` in the config:

```yaml
sounds:
  kill.confirm: "BLOCK_NOTE_BLOCK_PLING:1.0:2.0"   # vanilla enum
  airstrike.incoming: "kmc:airstrike.whistle:1.0"   # resource-pack sound
```

Format is `NAME[:volume[:pitch]]`. `NAME` is either a vanilla sound enum **or**
a resource-pack key (`namespace:path`), so a server can re-theme the entire
game with a sound pack — no code changes. Missing keys fall back to a sensible
built-in, so nothing is ever silent.

---

## Event direction (presets, finale, pause, rerun)

The automation (`/kmcauto`) is steered from a few places:

| What | How |
|---|---|
| Number of games, finale, breaks, presets | the `/kmcauto start` menu (also shows the estimated duration) |
| Everything live (skip, pause, rerun, round, finale, emergency stop) | `/kmcdirector` |
| Presets | `/kmcauto preset list|show|apply|save <name>` — built in: `standard`, `short`, `special`, `test` |
| Number of games | `/kmcauto games <n>` (rounds follow from `automation.games-per-round`) |
| Estimated duration | `/kmcauto time [games]` — also in the start menu and director panel |
| Pause | `/pause` (between games now; during a game right after it), `/resume`, `/pause cancel` |
| Emergency freeze | `/pause now` — every player stands still; **game timers keep running** |
| Rerun the last game | `/kmcauto rerun` — points, rounds and medals are rolled back, then the game is replayed |
| Dry-run a game | `/kmcauto dryrun <game>` — no points, kills, medals, MVP or achievements; state restored afterwards |
| Finale on/off | `/kmcauto finale on|off` |

**Finale.** After the last regular game the top two teams play `automation.finale.game` (default
`dodgebolt`, best of three). The winner of the finale is the tournament champion. The game is marked
`finale-only`, so it is never part of the vote. Set it up with `/dodgebolt` (spawns per side, spectator
spot, void level) or generate a test arena with `/kmctest dodgebolt`.

**Ready check.** After the long mid-tournament break (`automation.ready-check.mode`: `off`,
`mid-break`, `every-break`) every team gets a clickable "is your team ready?" message. Anyone in the team
can click it (or type `/kmcready`); the game carries on when every team answered or after the timeout.
`/kmcready force` ends it.

**Overtime.** A game that supports it (The Bridge: golden goal, QuakeCraft: sudden death) is extended
once when the top places are level at the end. Per game: `games.<id>.overtime.enabled|seconds|max-extensions`
in that game's config.

## Score breakdown, awards, archive & records

**One book for every point.** Every award — from any game, the legacy API included — is booked by
`PointsManager` in the points ledger (`ledger.csv`): who, which game, why (kill, placement, bonus, ...),
base amount, round multiplier, comeback bonus. Games should call
`api.points().givePoints(uuid, amount, Reason, gameId)` (or the legacy `KMCApi.givePoints(uuid, amount, Reason, gameId)`).

| What | Where |
|---|---|
| Per game: top players, points per team with the biggest reasons, and a private "your game" card | after every game (automatic) |
| Awards ceremony (MVP, most kills, game wins, most consistent, clutch, racer, parkour, teammate, team prizes) | end of the tournament |
| Credits | after the awards |
| Crowd rating (5 clickable stars + `/kmcfeedback <1-5> [comment]`) | after the Fan Favorite vote |
| Personal statsbook (clickable contents, game by game, reasons, team, standings, awards, records) | handed out at the end |
| Edition archive | `plugins/KMCCore/history/edition-<n>.yml` |
| Season / all-time ranking | `/kmcseason [season|all]` |
| Track records + your personal bests (Track Mastery) | `/kmctrack` |

**End sequence order:** archive → announcement → podium → awards → credits → Fan Favorite → feedback → books → reset.
Skip a part with `/kmcauto skip`. Settings: `end-sequence.*`, `awards-ceremony.*`, `credits.*`, `season.editions`.

**Games report highlights and sector times** through `api.games()`:
`recordHighlight(uuid, "clutch"|"racer"|"parkour"|"teammate", value, gameId)` feeds the awards;
`recordSector(gameId, trackId, sector, uuid, name, millis)` feeds the track records (sector 0 = whole lap/course).
Formula MC, Adventure Escape, Elytra Endrium, Parkour Warrior and TGTTOS report sector times.

## Presentation (wave 3)

| Feature | What it does | Config / command |
|---|---|---|
| MCC tablist | Header "presented by", `<game> - GAME POINTS:` line, per team a row with rank, icon, name and right-aligned points, team members below in team colour. Live game points from the ledger while a game runs. | `tablist.layout: mcc` (or `classic`) |
| Rank NPCs | Top-10 leaderboard NPCs with the skin of the ranked player + a personal "your place" hologram per viewer. | `/kmcnpc rank set <1-10>`, `rank own`, `rank remove <n/own/all>`, `rank refresh` |
| Presenter NPC | An NPC that announces the next game, results and the champions (speech bubble + chat). | `/kmcnpc presenter set/remove/skin <speler>/say <tekst>`, `presenter.enabled` |
| Game intros | Typewriter title, arena flash, FX countdown, GO effect, tutorial lines on the actionbar. | `start-flow.fx` |
| Leaderboard animation | Teams rise/fall with ▲/▼, +points and biggest riser/faller. | `presentation.leaderboard-animation` |
| Team jingles | Every team gets a deterministic tune (note block instruments) in the team showcase and on the champion reveal. | `team-jingles.enabled`, `team-jingles.<id>.{instrument,notes,speed}` |
| Wooden spoon | Last-place team gets a consolation ritual (title, roast line, item). Needs 3+ teams. | `consolation.enabled`, `consolation.seconds` |
| Kill-cam / finish-cam | Victim spectates the killer for a few seconds; race winners get a finish title. | `cams.kill-cam.*`, `cams.finish-cam.*` |
| Chat gate | Chat and `/tc` are muted during explanations/ceremonies, OP included (`kmc.chat.bypass` to opt out). | `chat-gate.enabled` |
| GG button | A clickable `[GG]` appears after each game; counts go to the tournament data. | `/gg`, `gg.enabled`, `gg.window-seconds` |
| Surprise game | Mark a game as surprise in the automation menu (right click). Name, icon and objective stay hidden in votes and the line-up card until the game is chosen. | right click in `/kmcauto` setup |

## Games & lobby (wave 4)

### Rising Inferno (new game, `RisingInferno.jar`)
Climb a tower while the lava rises: a long safe route or a short risky route with bonus orbs. The lava is one
scaled block display (no real lava blocks, nothing to clean up). Placement points + 30 for finishing + 10 per orb.

```
/inferno lava pos1 | pos2     corners of the area the lava fills (block you stand on)
/inferno lava base            lava starts at the block you stand on
/inferno finish pos1 | pos2   finish box (3 blocks high)
/inferno spawn add | clear    start positions
/inferno orb add | clear      bonus orbs (put them on the risky route)
/inferno rise <seconds>       how long the lava needs to reach the top (0 = config default)
/inferno start | stop | status
```
`/kmctest rising_inferno` builds a 30-step test tower (spiral = safe, magma helix = risky).

### Survival Games: supply drops, loot tiers, cornucopia events
- **Loot tiers**: gear shows its rarity (Common / Rare / Epic / Legendary); `loot.rare-chest-chance` turns a few outer chests into cornucopia-tier chests.
- **Supply drops** (`drops.*`): an announced beam of light, a chest with epic loot (`loot.drop`) lands after 15 s; the first opener is announced.
- **Cornucopia events** (`cornucopia-events`): `REFILL_CORNUCOPIA` restocks the rare chests, `CORNUCOPIA_DROP` drops a chest next to the cornucopia.

### Formula MC: qualifying, boost pads, power-ups, weather
- **Qualifying** (`qualifying.*`, `/fmc quali on|off`): everyone drives one timed lap, the fastest starts on pole (+10 points).
- **Boost pads**: `/fmc pad add` (selected gate) or any block of `boost.pad-material`.
- **Item boxes** (`/fmc box add`): TURBO, SHIELD, BLIND (ink bomb), STUN (ice cannon) — right-click to use, rubber-banded odds.
- **Track weather** (`weather.*`, `/fmc weather <auto|clear|rain|storm|night>`): changes time of day, rain and the grip (drag).
- **Track Mastery**: a clickable link to `/kmctrack` after every race.

### Hole in the Wall: wall names from colours
Walls are named by the side they come from: **Rood** = west, **Blauw** = zuid, **Geel** = oost, **Groen** = noord, corners combine
two colours (e.g. **RoodBlauw** = south-west). Change the mapping in `walls.side-names` of the HITW config.

### Lobby minigames (no tournament points)
`/ttt <player>` tic-tac-toe duel (GUI, clickable accept), `/darts` snowball darts at Target blocks or `/lobbygames board add`,
fishing contest with a biggest-fish record, a timed **parkour** (`/lobbygames parkour start|checkpoint|finish|clear`,
`/lobbygames parkour` = back to checkpoint) and a **spleef** floor (`/lobbygames spleef pos1|pos2|material`, join with
`/lobbyspleef`). `/lobbygames top [ttt|darts|fish|parkour|spleef]`. Config: `lobby-games.*`.

## Platform (wave 5)

| Feature | What it does | Config / command |
|---|---|---|
| Live-data API + overlay | Read-only JSON (`/api/state`) and a ready OBS browser-source page (`/overlay`): round, multiplier, game, next game (surprise hidden), team + player standings, live game points, recent moments. Off by default, localhost only unless you set `bind` + `token`. | `live-api.*`, `/kmcapi status\|start\|stop`, `/overlay?hide=evcard&token=...` |
| Chunk preload + async paste | The arena chunks are loaded before the flyover; `SchematicManager.pasteSchematicAsync` reads the schematic off-thread and preloads chunks before pasting. | `start-flow.preload-chunks` |
| Safe teleports | Every plugin teleport that would put a player inside blocks is nudged to the first free spot; unsafe ones are listed with the calling class. | `safe-teleport.*`, `/kmcaudit [clear]` |
| Spline recorder | Fly a route and it writes itself (thinned with Ramer-Douglas-Peucker, timing preserved). | `/kmccamera record <route> [ticks]`, `record game <gameId>` (= `arena-<gameId>`), `record stop [tolerance]` |
| Language on first join | New players get the language menu (nl/en). | `language.ask-on-first-join` |
| HUD scale | Per-player sidebar density. | `/kmchud normal\|compact\|minimal` |
| Spectator kit | Compass teleport menu, follow mode and leader tracker for every game, automatically. | `spectator-kit.enabled` |
| Clip markers | Timecodes of game start/end, kills, clutch plays, track records, finishes, rounds; players add their own. Written to `clip-markers.csv`. | `/clip <text>`, `/clip list`, `/clip start` (stream clock), `clips.enabled` |
| Team emblems | A banner per team (colour from the team, pattern from its id, overridable). | `/kmcemblem [give\|wear\|<team>]`, `emblems.<team>.*` |
| Team profile | Emblem, rank, wins, best game, top scorer, members; click through the other teams. | `/teamprofile [team]` |
| Storyline | A chapter at every round start and a recap after each game (lead change, biggest climb, closest gap). Custom chapter text per round possible. | `story.*` |
| Lobby anti-grief | Item frames, armor stands, buckets, fire, farmland, containers, boats, spawn eggs, pearls. | `anti-grief.*`, permission `kmc.lobby.build` to build |

## Arcade games (wave 6)

Six new games, each its own jar, all on the shared `kmc-game-api` arcade framework
(`nl.kmc.game.api.arcade`: `ArcadeGameManager`, `ArcadeCommand`, `ArenaConfig`, and the unit-tested `ArcadeLogic`).
Common to all of them:

- `/<cmd> world | spawn add|clear|list | pos1 | pos2 | validate | status | start [dry] | stop` — `start dry` plays the
  whole game without booking points or statistics.
- `/kmctest <gameId>` builds a test arena and configures the game in one go.
- Every point goes through the normal KMC scoring (`api.points()`), keyed so a checkpoint/capture/bonus is paid once per run.
- The game loop stands still while the tournament is paused; combat games respawn with spawn protection.
- Game time of the energy/ability games (Crown Control, Core Charge) is game data — never coins.

| Game | Id | Command | Setup (besides world + spawns) |
|---|---|---|---|
| Rocket Rush | `rocket_rush` | `/rocket` | `checkpoint add` (in order), `ring add`, `pickup add`, `finish`, `floor` |
| Knockout Kings | `knockout_kings` | `/knockout` | `pos1`+`pos2` then `platform`, `respawn add`, `killy` |
| Crafting Countdown | `crafting_countdown` | `/craftcountdown` | per area `area center`, `area delivery`, `area supply` (look at the block); recipes in config |
| Zone Zero | `zone_zero` | `/zonezero` | `pos1`+`pos2` then `zone add <id>` (6+ zones for 2 open at a time) |
| Crown Control | `crown_control` | `/crown` | `pos1`+`pos2` then `zone`; optional `lockdown add <id>`; menu: nether star or `/crown use` |
| Core Charge | `core_charge` | `/corecharge` | `core add` (per team), `orb add`, `station add`; menu: nether star or `/corecharge use` |

Tuning (times, points, modifiers, kit) is in each game's `config.yml`; points tables follow the spec defaults.
Not built (optional in the spec): Crafting Countdown modifiers other than `DOUBLE_RECIPE`.

## Player commands

| Command | What it does |
|---|---|
| `/tutorial` (alias `/kmctutorial`) | Explains every system and links to the real GUI for each |
| `/tc <message>` | Team-only chat |
| `/kmcprofile [player]` | Your (or another player's) profile GUI |
| `/kmcstats` | Your personal stats GUI |
| `/kmcstats <player>` | Another player's stats |
| `/kmcstandings` | Live standings GUI |
| `/kmclb` | Tournament leaderboard |
| `/kmcmedals` | Medal cabinet + Most Decorated leaderboard |
| `/kmcmvp` | Game MVPs (this tournament + all-time) |
| `/kmcmomentum` | Biggest rise/fall + hot streaks |
| `/kmcpowerrank` | Team power rankings (ELO) |
| `/kmchof` | Hall of Fame GUI |
| `/kmcvote` | Vote for the next game (when vote is open) |
| `/kmcachievements` | Your unlocked achievements |
| `/kmclanguage` (aliases `/taal`, `/kmclang`) | Choose your personal UI language |
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

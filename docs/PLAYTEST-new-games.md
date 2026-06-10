# Playtest Runbook — New / Reworked Games

Covers the three games changed this cycle:
- **TGTTOS V2** (reworked) — 5-map rounds + dynamic 50% finish
- **Block Party** (new) — colour-elimination championship
- **Speed Build** (new) — 10-stage schematic-accuracy builder

All three are V2 `AbstractGamePlugin` modules: they auto-register into the
**Setup Dashboard** (`/kmcsetup`), the **Validation Center**, the voting GUI,
and the intro-card system. No manual registration needed.

> **Build & deploy:** stop the server first (jar locks), then
> `mvn clean install -DskipTests`. Copy each game's
> `target/<Game>-1.0.0.jar` to `plugins/`. WorldEdit (or FAWE) must be
> installed for Speed Build.

---

## 1. TGTTOS V2

**Concept:** one game = 5 maps in sequence. Each map ends dynamically: when
50% of the field finishes, the rest get a 2-minute countdown; at zero, anyone
not finished is a **DNF**. Points accumulate across all 5 maps; KMC points are
awarded only after map 5.

### Setup
1. `/kmcsetup → TGTTOS` — create ≥1 map (start spawns + finish corners).
2. `config.yml` knobs: `game.maps-per-game: 5`,
   `game.finish-threshold-percent: 50`, `game.finish-countdown-seconds: 120`,
   `game.max-map-seconds: 300`.

### Test checklist
- [ ] Start with ≥4 players → "5 maps" announce, Map 1/5 begins.
- [ ] First finishers get placement medals + internal points (no KMC points yet).
- [ ] When 50% finish → **"HELFT VAN HET VELD IS GEFINISHT"** + 2-min boss-bar countdown.
- [ ] Finishing during the countdown still scores normally.
- [ ] At 0:00 unfinished players → **DNF** (no points), map ends.
- [ ] Whole field finishing early ends the map immediately.
- [ ] If nobody hits 50%, `max-map-seconds` forces the countdown (no stall).
- [ ] After Map 5 → "EINDUITSLAG" top-5, KMC points awarded once, return to lobby.
- [ ] Action bar + boss bar show *Gefinisht X/Y · Racet Z · ⏱ time* for everyone.

---

## 2. Block Party

**Concept:** stand on the called colour before the floor drops; wrong colour =
fall + eliminated. Last player wins. Floor regenerates every round; difficulty
escalates by phase and as players are eliminated.

### Setup (`/kmcsetup → Block Party`, or `/blockparty …`)
1. `/blockparty pos1` and `pos2` — the two corners of the floor rectangle
   (the floor layer is the lowest Y of the two).
2. `/blockparty spectator` — where eliminated players watch.
3. `/blockparty voidy` — stand at the height below which a player counts as fallen.
4. `/blockparty status` — must say **klaar ✓**.

### Test checklist
- [ ] `/blockparty start` → "BLOCK PARTY" title, tutorial, 5→GO countdown.
- [ ] Each round shows the target colour via title + boss bar + action bar + chat.
- [ ] At 0s, non-target concrete vanishes; players not on the colour fall & spectate.
- [ ] **Colour safety:** the called colour always has enough blocks for everyone alive (no impossible rounds).
- [ ] Timers shorten by phase (8→3s); **Endgame** (<8 alive) and **Final Showdown** (<4) kick in.
- [ ] Chaos events appear from round 5 (Double/Fake/Colour-Blind/Rapid-Fire + potion effects).
- [ ] Stepping onto the colour in the final second → **"⚡ CLUTCH SAVE"**.
- [ ] Last player → winner ceremony, top-5 board, placement points (250 / −10 / floor 25), last-team bonus.
- [ ] Achievements unlock: Color Master (win), Clutch King, Untouchable, Survivor.

### Config highlights (`config.yml`)
`game.timer.*`, `game.colours.*`, `game.cluster-size.*`,
`game.endgame-threshold`, `game.final-showdown-threshold`,
`chaos.*`, `scoring.*`.

---

## 3. Speed Build

**Concept:** solo, 10 schematics in a row, copied in an auto-isolated slot.
Scoring is 100% deterministic (block-by-block schematic diff + time bonus −
block penalty). Score is banked to the player **and** their KMC team total.

### Prerequisites
- WorldEdit/FAWE installed (`/speedbuild status` shows WorldEdit availability).
- 10 `.schem` files in `plugins/KMCCore/schematics/` (shared KMC folder).

### Setup (`/kmcsetup → Speed Build`, or `/speedbuild …`)
1. `/speedbuild anchor` — stand at the **min corner** of player 0's build slot.
   Other players auto-tile along +X (slot size = largest schematic + `gap`).
2. `/speedbuild spawn` — start / return point.
3. `/speedbuild addbuild <id> <file.schem> [difficulty] [weight] [name…]` ×10.
4. `/speedbuild listbuilds` and `status` — must show **10/10** and **klaar ✓**.

### Test checklist
- [ ] `/speedbuild start` → each player teleported to their slot, blueprint pasted adjacently.
- [ ] Hotbar: **GREEN** = complete, **RED** = re-show blueprint, **GOLD** = finish (build 10 only).
- [ ] GREEN scores the current build (title shows accuracy % + points) and advances in order.
- [ ] **Anti-exploit:** can't place/break outside your own slot; can't skip or revisit builds.
- [ ] After build 10, COMPLETE arms the GOLD button; GOLD ends the run.
- [ ] End summary: total score, avg accuracy, best/worst build, total time.
- [ ] Points land on the player AND their team (check `/kmcteam` standings).
- [ ] Two players simultaneously never interfere (separate slots).

### Determinism sanity check
Build the *same* structure twice → identical accuracy %/score. Comparison is by
block **type** (orientation/state ignored), anchored at the slot's min corner.

---

## Known wiring notes
- Setup Dashboard, Validation Center, voting GUI, intro cards: **automatic** for all three.
- Achievements: `kmc-stats/.../achievements/blockparty.yml` (ids match the code's `grant()` calls). Deploy alongside the other `achievements/*.yml`.
- The new V2 games appear in the **V2 Setup Dashboard**; the legacy Flyover
  dashboard enumerates V1 games, so build flyover routes via the game's
  `arena-<gameId>` route id if needed (`arena-block_party`, `arena-speed_build`).
- Block Party / Speed Build use literal coloured strings for their custom HUD,
  so they need **no new lang keys** (only the shared `game.board.*` keys, which already exist).

# Testing Checklist — this session's changes

Nothing below has been tested on a live server yet — everything only
compiled. Check items off as you verify them. Grouped so you can test in a
sensible order (automated → setup → cross-game → per-game → full flow).

---

## 0. Automated (no server needed)

- [ ] `mvn test -pl KMCCore -am` — all 21 `PointsManagerTest` cases pass.
      (Already verified by me during building, but worth re-running after
      you pull/build fresh.)

---

## 1. Build & deploy sanity

- [ ] `mvn clean install` (or `build-all.bat`) succeeds for all 21 modules.
- [ ] Server starts cleanly with Java 25, no plugin fails to enable.
      Check `latest.log` for `[KMCCore] enabled`, `[BlockParty] enabled`, etc.
- [ ] `/kmcauto start` from console (no player) still works immediately,
      no menu (sanity check that the player-only gate didn't break console use).

---

## 2. `/kmcauto` setup menu (AutomationSetupGui)

Needs: ≥2 teams, ≥1 player online.

- [ ] `/kmcauto start` as a player → setup menu opens (doesn't start yet).
- [ ] Click a game tile → toggles off (greys out, "Overgeslagen dit toernooi").
      Click again → toggles back on.
- [ ] Toggle a game off, then **close the menu without starting**. Run
      `/kmcgame list` or start a vote (`/kmcvote`) — confirm the disabled
      game does NOT appear as an option.
- [ ] Re-open `/kmcauto start`, click the Tussenpauze tile left/right-click
      → value changes ±5s (watch the lore update).
- [ ] Same for Stemduur tile.
- [ ] Click "Stemmen op volgende game" toggle → label flips AAN/UIT.
- [ ] Click "Start inplannen" → chat prompt opens. Type `in 1` → confirms
      scheduled; re-open the menu → tile shows "Gepland over ~1m".
      Type `cancel` in a fresh prompt → schedule clears.
- [ ] Click **START TOERNOOI NU** → tournament actually starts with your
      chosen settings (disabled games never get picked for the whole run).
- [ ] `/kmcauto start force` → skips the menu entirely, starts immediately
      (use this once to confirm the escape hatch still works).

---

## 3. Shared systems (affect ALL 15 games — spot-check 2-3 games, not all 15)

### Game-start/end sound stinger (`GameSfx`)
- [ ] Start any game → hear a pling (or your custom `sounds.game-start`
      sound if you set one in that game's `config.yml`) right when it
      transitions from countdown/grace into the live round.
- [ ] End any game (win or `/<game> forceskip`) → hear a stinger
      (`sounds.game-end`) immediately.
- [ ] Set a custom `sounds.game-start: "BLOCK_NOTE_BLOCK_PLING:1.0:2.0"` in
      one game's `config.yml`, `/<game> reload` if it supports it (else
      restart), confirm the pitch/volume actually changed.

### MVP crown particle
- [ ] During any live game with ≥2 participants, have one player earn
      tournament points (any kill/placement in a PREVIOUS game is enough —
      points persist across games this tournament). Confirm a small
      rotating gold dust ring appears above that player's head, live,
      and follows them if they move.
- [ ] Before anyone has scored any points this tournament (round 1, first
      game), confirm NO crown appears on anyone (no false "leader" at 0-0).
- [ ] Have the lead change mid-tournament (lower-scoring player overtakes)
      — confirm the crown moves to the new leader within ~1 game.

### Scoreboard leadership flash
- [ ] With teams at 0-0 (very start), confirm the sidebar title does NOT
      flash (no noise before scoring starts).
- [ ] Get team B to overtake team A in total points → sidebar title
      flashes team B's colour "⬆ NIEUWE KOPLOPER" for ~3s, then reverts,
      plus a sound + chat broadcast.

### Comeback bonus
- [ ] Get one team's points at least 100 below the leader
      (`comeback-bonus.min-gap` in `config.yml`, default 100).
- [ ] Have a player on the trailing team earn points (kill or placement)
      → confirm the amount awarded is ×1.25 (default) of the base value,
      and the SAME boosted amount lands on both the player and their team.
- [ ] Confirm a team NOT in last place gets the normal, unboosted amount.

### Golden Hour
- [ ] Run a short tournament (`tournament.total-rounds: 2` temporarily in
      config, to force a quick hit) and watch for the "✨ GOLDEN HOUR ✨"
      broadcast+title on one of the rounds — not announced beforehand.
- [ ] During that round, confirm a KILL also scores ×multiplier (normally
      kills are flat — this is the one exception). Compare the awarded
      kill points to `kills.per-kill` × the announced Golden Hour multiplier.
- [ ] Confirm only ONE round per tournament triggers it (not every round).

---

## 4. Fan Favorite vote (post-tournament)

- [ ] Finish or `/kmctournament stop` a short tournament with ≥2 players online.
- [ ] ~6 seconds after the winner announcement, confirm a GUI auto-opens
      for every online player listing all online players as candidates.
- [ ] Click a candidate → "✔ Stem genoteerd." message.
- [ ] After ~20s, confirm a winner is broadcast with vote count, and that
      player gets a title + the server plays a sound.
- [ ] Check `/kmcachievements` (or the stats GUI) for the winner — confirm
      "Fan Favorite" (legendary) shows as unlocked.
- [ ] Run it again with ZERO votes cast (let the 20s expire untouched) —
      confirm it broadcasts "Geen stemmen..." instead of crashing/erroring.

---

### Note on "1/8 after 1 round" (not a bug)
A KMC "round" bundles **`automation.games-per-round`** games (default **3**)
before the round counter advances and the multiplier goes up — playing one
single minigame is 1 of 3 games toward round 1, not a whole round. If you
want to test round-advancement quickly, either play 3 games, or temporarily
lower `automation.games-per-round` to `1` in `config.yml` (restart needed —
it's only read once at startup).

### Fixed this round: Golden Hour no longer collides with the opening ceremony
Previously, Golden Hour's reveal fired instantly inside
`TournamentManager.start()`/`nextRound()` — before `/kmcauto`'s own opening
ceremony, team showcase, intermission, voting, and countdown had even
played, so the two titles/broadcasts visually overlapped. It now reveals
at the exact moment a round's first real game launches
(`AutomationManager.launchGame()` / the equivalent spot in
`/event simulate`), i.e. after the whole ceremony sequence has finished.
- [ ] Re-test: start a tournament, let a full round (3 games by default)
      play out with its ceremonies — confirm the Golden Hour title (if that
      round is the lucky one) appears cleanly on its own, not stacked on
      top of the opening/team-showcase titles.

## 5. `/event simulate` (now runs the REAL tournament pipeline)

⚠️ **Has real, lasting effects now** — it will bump your real KMC event
number and (on a full run) reset real scores. Test on a non-production
server or right before you intend to reset anyway.

- [ ] With a real tournament already active (`/kmctournament status` shows
      active), run `/event simulate 5 16` → confirm it REFUSES
      ("Er draait al een ECHT toernooi...") instead of hijacking it.
- [ ] With no tournament active, `/event simulate <total-rounds> 16` (use
      your actual `tournament.total-rounds` value) → watch each round:
  - [ ] Game name shown each round is never the same twice in a row across
        the whole run (no-repeat rotation) — unless you've disabled enough
        games that repeats become unavoidable.
  - [ ] Multiplier shown climbs across rounds matching your
        `tournament.multipliers` config (not stuck at ×1.0).
  - [ ] After the final simulated round, confirm the REAL end-of-tournament
        flow fires: winner title, top players, Fan Favorite vote, and —
        if you (the admin) are online — **you receive the stat book** in
        your inventory.
  - [ ] Check `/kmctournament status` afterward — event number incremented,
        points reset to 0.
- [ ] Now run `/event simulate 2 16` (fewer rounds than your configured
      total) → confirm it ends via a plain stop: NO book, and
      `/kmctournament status` shows points NOT reset (still holding the
      simulated bots' contributions — expected, matches a real early stop).
- [ ] `/event rollback <the sim-pre-... label printed at the start>` →
      confirms you can manually undo a simulation's real effects if needed.

---

## 6. Block Party

Needs: arena set up (`/blockparty pos1/pos2/spectator/voidy`, area ≥64 blocks).

⚠️ **pos1/pos2 off-by-one-block fix**: `/blockparty pos1`/`pos2` (and the
Setup Dashboard's floor-corner steps) now capture the block you're
STANDING ON, not the block your feet are inside (which was one block too
high). If you had an arena set up before this fix, re-run `/blockparty
pos1` and `pos2` while standing exactly on your intended floor corners —
the old stored Y will be one too high otherwise.

⚠️ **Config restructured this round** — all Block Party settings now live
nested under `block-party:` in `config.yml` instead of flat (`arena:`,
`game:`, `chaos:`, `scoring:` at the top level). If you already had an
arena set up from earlier testing, a one-time automatic migration copies
it over the first time the plugin loads with the new jar — check the
console log for `[BlockParty] Migrating old arena.* config layout...`,
and `/blockparty status` to confirm your pos1/pos2/spectator/preset
survived. If you'd rather start clean, just re-run the setup commands.

- [ ] `/blockparty start` with NO preset set → round 1 floor uses only
      Yellow/White/Light Gray/Black concrete (visually confirm — count the
      colours on the floor).
- [ ] Round 2 onward → floor uses any/all of the 16 concrete colours,
      different pattern each round (take a screenshot each round and compare).
- [ ] Build a custom pattern on the floor, run `/blockparty presetfloor` →
      confirm it saves (status shows "preset (schematic)").
- [ ] Start a new game → round 1 now uses YOUR captured pattern exactly,
      not the random 4-colour default.
- [ ] `/blockparty clearpreset` → next game's round 1 goes back to the
      random 4-colour default.
- [ ] Standing on the "safe" colour survives the round; everyone else
      falls. Confirm this still works correctly with both the preset and
      the random round 1.
- [ ] Keep playing rounds until you see a "⇄ MIRROR" round (chaos events
      start from round 5, random chance) — confirm the announced colour is
      now the one that KILLS you, and every other colour is safe (inverted
      from normal). Check the bossbar/actionbar/scoreboard all say "VERMIJD"
      clearly during that round.
- [ ] During the countdown/grace period (before round 1 truly starts),
      confirm players do NOT fall through the void (the original bug this
      session started with) — floor should already be painted when you land.

### New this round — spec alignment

- [ ] **Colour item**: at the start of every round, confirm every alive
      player gets a concrete block in hotbar slot 5 (index 4), named just
      the colour (e.g. "BLAUW", never "BLAUW BETON"/`BLUE_CONCRETE`).
- [ ] Try to move it in your inventory, drop it (`Q`), place it, or
      swap-hand it (`F`) → all cancelled, item stays put.
- [ ] Get eliminated → confirm the colour item disappears from your hotbar.
- [ ] **Participation filter**: have a player join the server WITHOUT being
      on any KMC team, stand in the arena when the game starts → confirm
      they do NOT get pulled into the match (no colour item, not teleported).
- [ ] **No-repeat colour**: watch several consecutive rounds' target colours
      — the same colour should not appear two rounds in a row (unless the
      floor is down to very few viable colours, in which case a repeat is
      the documented fallback).
- [ ] **Big countdown numbers**: during a round, confirm you see a large
      on-screen number counting down every second (not just the
      actionbar/bossbar text), with a distinct warning sound at "1", and a
      "VERDWIJN!" title the instant the timer hits zero.
- [ ] **Tiered scoring**: check `/kmcpoints` or chat for a winner — should
      roughly match `first` (200) + `survival-per-round` (10) × rounds they
      survived, not the old continuous curve. Compare 2nd/3rd/4th-5th/6th-10th
      against `second`/`third`/`top-5`/`top-10` in `config.yml`.
- [ ] **Custom events**: if you (or I) wire a test listener, confirm
      `BlockPartyRoundStartEvent`, `BlockPartyPlayerEliminateEvent`,
      `BlockPartyPlayerSurviveEvent`, `BlockPartyRoundEndEvent`, and
      `BlockPartyGameEndEvent` all fire with sensible data — otherwise just
      trust the compile + code review, these have no visible in-game effect
      on their own (they're for other systems to hook into later).

---

## 7. TGTTOS

Needs: ≥1 map configured.

- [ ] Play a map, watch for an occasional "🌫 FOG OF WAR" announcement
      (`game.fog-of-war-chance`, default 15%) — confirm affected racers get
      genuinely blurry/dark vision (refreshed Blindness) for the whole map.
- [ ] With ≥3 players racing, confirm whoever is furthest from the finish
      gets a visible speed boost (Speed II particles/movement) that follows
      whoever is ACTUALLY last — have the last-place player catch up and
      confirm the boost moves to the new last-place player.
- [ ] Watch the bossbar during a race — confirm "🏁 <name>" appears showing
      the current leader, and updates live as positions change.
- [ ] Check your own actionbar — confirms either "👑 KOPLOPER" (if you're
      1st) or "-Xm" (your gap to the leader in blocks).

---

## 8. TNT Tag

Needs: arena with center + border radius configured.

- [ ] Play several rounds, watch `/tnttag status` or just observe the
      world border — confirm it's VISIBLY smaller after each round (not
      just during chaos events or the 2-player showdown).
- [ ] Confirm it doesn't shrink below `progressive-shrink-min-radius`
      (default 12) even after many rounds.
- [ ] Reach the Final Showdown (2 players) — confirm the dedicated
      showdown shrink takes over properly (progressive shrink should not
      fight it).
- [ ] Start a fresh game after a previous one — confirm the border resets
      to full size at the start (doesn't carry over shrunk from last game).

---

## 9. Survival Games

Needs: arena with cornucopia center configured.

- [ ] Let the game run down to the deathmatch trigger
      (`game.deathmatch-trigger-seconds`) — confirm a "🍗 THE FEAST"
      broadcast + title fires exactly once, and a chest physically appears
      just above the cornucopia centre.
- [ ] Open that chest — confirm it's stocked with notably better loot than
      a normal cornucopia chest (diamond gear, 2-4 golden apples, etc. —
      see `loot.feast` in `config.yml`).
- [ ] Set `game.feast-enabled: false`, replay — confirm no chest/broadcast.

---

## 10. Mob Mayhem — full rework (previously totally broken, see below)

⚠️ **Context**: Mob Mayhem used to hang forever on "GO! Wave 1 begins!" —
the world-cloning step that gives each team their own arena was never
actually triggered, and even if it had been, wave 2 never would have
started after wave 1 (that start-next-wave call was simply missing). Both
are fixed now. Can be tested solo — one player = one team = one cloned
arena, the per-team architecture doesn't need multiple teams to work.

Needs: `/mm status` shows template world ✔, player spawn ✔, ≥4 mob spawns.

### Critical path (the actual bugfixes)
- [ ] `/kmcteam add <name>` yourself onto a team (or `/kmcrandomteams`),
      then `/mm start`. Confirm you're teleported into a **cloned** world
      (not the template) — check `/mv list` or just look at the world name
      you land in, should be `mm_game_<team>_<random>`, not your template name.
- [ ] Confirm mobs ACTUALLY spawn for wave 1 (the core thing that was
      broken) — you should see zombies within a couple seconds of the
      "GO!" title.
- [ ] Kill all wave-1 mobs → confirm "Team ... cleared wave 1!" broadcast
      + points, THEN after ~5s (`game.intermission-seconds`) confirm
      **wave 2 actually starts** (this never happened before the fix —
      watch closely, this is the second critical bug).
- [ ] Let it run a few more waves this way to confirm progression keeps
      going wave after wave, not just 1→2.
- [ ] Die mid-wave → confirm spectator mode, and that you can no longer
      damage mobs.
- [ ] `/mm stop` mid-game → confirm the cloned world is deleted shortly
      after (check the server's world folder list), no leftover `mm_game_*`
      folder.

### Config-driven waves (optional)
- [ ] Uncomment the example `waves:` section in `config.yml`, edit mob
      counts, restart/`/mm reload` + a fresh `/mm start` → confirm YOUR
      wave content is used instead of the built-in 10-wave progression.
- [ ] Remove/comment the `waves:` section again → confirm it falls back to
      the built-in waves without errors.

### Achievements + Moments (new)
- [ ] Reach wave 10 solo (or lower a test `max-waves` temporarily) →
      check console log for `[KMC/Achievements] ... unlocked:
      mobmayhem_survivor` and confirm `/kmcprofile`'s achievement counter
      goes up.
- [ ] Survive to wave 5 without ever dying → `mobmayhem_untouchable`
      should unlock.
- [ ] Kill a boss mob (wave 7 or 10) → `mobmayhem_boss_slayer` unlocks
      immediately (don't need to grind — one boss kill is enough).
- [ ] (Optional, slow) Rack up 50 kills in one game → `mobmayhem_exterminator`.
- [ ] If testing with 2+ teams: eliminate every team but one down to a
      single remaining player → `mobmayhem_last_stand` unlocks for that player.
- [ ] Unrelated sanity check: your regular mob kills now show up in
      lifetime kill stats too (`StatisticsService`) — not just points.

### Powerups (new, optional — only active if you set spawn points)
- [ ] `/mm addpowerupspawn` a couple of times in your template arena
      (stand where you want one, repeat for 2-3 spots).
- [ ] Start a game → within `powerups.interval-seconds` (default 30s) a
      glowing item should appear at one of those spots.
- [ ] Pick it up → confirm a chat message + sound, and the matching potion
      effect (speed/strength/regen/resistance/absorption) or instant heal
      actually applies.
- [ ] Leave one unclaimed → confirm it despawns after
      `powerups.despawn-seconds` (default 45s) and that spot can spawn a
      new one afterward.
- [ ] `/mm clearpowerupspawns` → confirm no more powerups appear in a new game.

---

## 11. Assist points (SkyWars only so far)

Needs: 3+ players, SkyWars arena set up.

- [ ] Player A hits player B, player B hits player A back — then a THIRD
      player C finishes player A off. Confirm player C gets the full kill
      credit MINUS the assist share, and player B gets the assist share
      (`points.assist-fraction`, default 0.2 = 20%) — check `/kmcpoints`
      or chat messages for both amounts.
- [ ] Confirm the assist message only appears when the assist hit was
      within the last ~10 seconds before the kill (hit someone, wait a
      long time, then someone else kills them — no assist credit).
- [ ] Note: QuakeCraft, Survival Games, Spleef, Lucky Block, The Bridge,
      TNT Tag, and Mob Mayhem do NOT have assist points yet — don't expect
      it there until that follow-up work happens.

---

## 12. Easter egg NPCs — "find them all"

Rebuilt this round: each NPC now has a FIXED line (not random) and the
server tracks, per player, which ones you've found.

- [ ] `/kmclobbynpc spawn easter_egg` a few times in different spots →
      confirm each spawned villager has **no floating name**.
- [ ] Right-click NPC #1 → always the SAME line every time (not random).
      Right-click NPC #2 → a DIFFERENT, also-fixed line.
- [ ] First time finding a given NPC → "✨ Nieuwe easter egg gevonden!
      (1/3)" (or whatever your total is) + a level-up sound.
- [ ] Right-click that SAME NPC again → just the line, no "nieuwe"
      message again (already-found NPCs don't re-trigger the fanfare).
- [ ] Click again within 3 seconds of any click → nothing happens (cooldown).
- [ ] Find every spawned NPC → server-wide broadcast "... heeft ALLE
      verborgen easter eggs gevonden!" + check `/kmcachievements` for
      "Easter Egg Hunter" (legendary) unlocked.
- [ ] Restart the server, right-click an already-found NPC → still
      recognised as found (no duplicate fanfare) — confirms it's
      persisted, not just in-memory.
- [ ] Edit `easter-egg-npc.lines` in KMCCore's `config.yml`, restart →
      confirm your custom lines show up (still one fixed line per NPC, in
      spawn order).

---

## 13. `/tutorial`

- [ ] `/tutorial` opens a hoofdmenu with 8 categories (Basis & Teams,
      Puntensysteem, Speciale momenten, Spellen, Stemmen & rondes,
      Achievements & Records, Jouw voortgang, Taal / Language).
- [ ] "Spellen" → opens the real `/kmchelp` menu (not a duplicate).
- [ ] "Taal / Language" → explains `/kmclanguage`, and its button actually
      opens the real language picker (not just text).
- [ ] Every other category's buttons (Standings, Profiel, PowerRank, Hall
      of Fame, Medals, MVP, Momentum) open the REAL corresponding GUI, not
      a dead end.
- [ ] "Terug naar /tutorial" from any topic page returns to the main menu.

---

## Known gaps (not bugs — just not built yet, don't file these as issues)

- Assist points: only SkyWars wired.
- Live race-gap indicator: only TGTTOS wired (Elytra Endrium, Parkour
  Warrior need their own checkpoint-based version).
- Dynamic "big moment" commentary lines: not started.

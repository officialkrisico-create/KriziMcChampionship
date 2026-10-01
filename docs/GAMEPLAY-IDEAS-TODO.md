# Gameplay Ideas — TODO

Shortlist picked from a brainstorm session. Grouped by how much new
infrastructure each needs, roughly in build order (top of each group first).
Check items off as they land; add a one-line note on where the code lives
once built, so this stays useful as a changelog too.

---

## Quick wins (reuse existing systems)

- [x] **Fan Favorite-stemming** — gebouwd als een GUI-stemming op favoriete
      SPELER (niet een specifiek "moment" — daarvoor is nog geen
      moment-detectie, zie de highlight-reel hieronder). Haakt in op
      `TournamentManager.endTournament()` (6s na einde, 20s durend), kent de
      winnaar een nieuwe `fan_favorite`-achievement toe (LEGENDARY).
      Code: `tournament/FanFavoriteManager.java`, `gui/FanFavoriteVoteGui.java`,
      `achievements/AchievementRegistry.java`.
- [x] **Unieke muzikale stinger per game** (start + einde) — generiek gemaakt
      in `kmc-game-api` zodat alle 15 games het gratis krijgen (ze extenden
      allemaal `BaseGameManager`, bleek tijdens het bouwen). Config-key
      `sounds.game-start` / `sounds.game-end` in elk game's eigen
      `config.yml`, zelfde `NAME[:volume[:pitch]]`-formaat als QuakeCraft's
      `Sfx`. Code: `kmc-game-api/.../GameSfx.java`,
      `BaseGameManager.java` (hooks in `beginGrace()` en `end()`).
- [x] **Scoreboard flitst van kleur** zodra je team de leiding overneemt —
      3s titel-flash in de kleur van de nieuwe koploper + geluid + broadcast,
      alleen op de lobby-sidebar (niet tijdens een actief game-scorebord).
      Geen flash zolang iedereen nog op 0 punten staat (voorkomt ruis bij
      toernooistart). Code: `ScoreboardManager.checkLeaderChange()`.
- [x] **Verborgen grappige NPC** in de lobby met easter-egg dialogen —
      nieuw `NPCType.EASTER_EGG` op de bestaande `LobbyNPCManager` (niet de
      leaderboard-`NPCManager`, dat is een ander systeem). Geen zwevende
      naam (dus echt "verstopt"), rechtsklik = willekeurige regel uit
      `easter-egg-npc.lines` in `config.yml`, 3s cooldown tegen spam-klikken.
      Plaatsen: `/kmclobbynpc spawn easter_egg`.
- [x] **Block Party spiegel-ronde** — nieuwe `ChaosEvent.MIRROR`: de
      aangekondigde kleur is nu de dodelijke, elke ANDERE kleur op de vloer
      overleeft (`keepColours` = palet minus target i.p.v. alleen target).
      Aparte "VERMIJD"-teksten op title/bossbar/actionbar/scorebord zodat het
      niet verward wordt met FAKE_COLOR. Code: `ChaosEvent.java`,
      `BlockPartyGameManagerV2.startRound()`.
- [~] **Fysieke recordmuur** in de lobby — overgeslagen: `HallOfFameManager`
      + `/kmchof setnpc` doet dit al functioneel (records per categorie,
      permanent, in de lobby), alleen via NPC's i.p.v. signs/item-frames.
      Zie dit als gedekt tenzij alsnog een signs-only (geen FancyNpcs-
      afhankelijkheid) variant gewenst is.

## Medium (nieuwe per-game state-tracking)

- [x] **Comeback-bonus** — gehaakt in `PointsManager.awardPlayerPoints()`
      (de centrale "single source of truth") en `awardTeamPlacement()`, dus
      kills/plaatsingen/teamplaatsingen profiteren er allemaal automatisch
      van. Boost (`comeback-bonus.multiplier`, default 1.25×) alleen voor
      het huidige laatste team, en alleen als de kloof met de koploper
      minstens `min-gap` (default 100) punten is — geen ruis vroeg in het
      toernooi. Config in `config.yml`. 4 nieuwe tests in
      `PointsManagerTest.java` (19 totaal, allemaal groen).
- [~] **Assist-punten (80/20-split)** — deels gebouwd, zie hieronder.
      Belangrijke ontdekking tijdens het bouwen: de gedeelde
      `PlayerKillListener` in KMCCore blijkt bijna nooit te vuren voor de
      echte PvP-games — SkyWars, Survival Games, Spleef, QuakeCraft, Lucky
      Block, The Bridge, TNT Tag en Mob Mayhem crediten kills allemaal
      **zelf** via hun eigen `api.points().givePoints(...)`-aanroep, niet via
      die listener. Dus: een nieuwe gedeelde `AssistTracker`-utility gebouwd
      in `kmc-game-api` (trackt de laatste 2 verschillende aanvallers per
      slachtoffer, 10s-venster, `AssistTracker.split()` voor de 80/20-berekening),
      plus een nieuwe `PointAward.Reason.ASSIST` (behandeld als "flat", net
      als KILL, in `V1KMCApi`). **Alleen SkyWars is al aangesloten** als
      referentie-implementatie (`points.assist-fraction: 0.2` in zijn
      `config.yml`). De andere 7 PvP-games hebben dezelfde, mechanische
      wiring nog nodig (zelfde patroon: vervang hun eigen
      `lastAttacker`-tracking door `AssistTracker`, splits de kill-punten bij
      het crediten). Volgende sessie: QuakeCraft, Survival Games, Spleef,
      Lucky Block, The Bridge, TNT Tag, Mob Mayhem.
- [x] **Golden Hour** — bij `/kmctournament start` wordt stiekem één ronde
      (1..totaal) geloot als de Golden Hour-ronde; pas onthuld zodra die
      ronde daadwerkelijk begint (`TournamentManager.checkGoldenHour()`,
      aangeroepen vanuit `start()` en `nextRound()`). Overschrijft de
      multiplier voor ALLE puntenbronnen die ronde, inclusief kills (die
      normaal bewust plat zijn — Golden Hour is een bewuste uitzondering
      daarop). Config: `golden-hour.enabled`/`multiplier` (default 2.0×) in
      `config.yml`. `TournamentManager.getMultiplier()` gecorrigeerd om ook
      via deze override te lopen (was eerst een losse, inconsistente
      berekening t.o.v. `PointsManager.getCurrentMultiplier()`). 2 nieuwe
      tests, 21/21 groen.
- [x] **Bloedmaan-ronde** — nieuwe `WaveModifier.BLOOD_MOON`, apart en
      zeldzamer gerold dan de normale modifier-pool (`modifiers.blood-moon-
      chance`, default 8%). Effect: dwingt nacht + onweer in de arena-
      wereld af, ×3 mob-HP + permanent Speed I, rode dust-deeltjes die op
      spelers neerregenen de hele golf. Weer/tijd wordt hersteld zodra de
      golf eindigt (ook bij force-stop). Code: `WaveModifier.java`,
      `WaveExecutor.java` (`startBloodMoon`/`endBloodMoon`).
      **Bijvangst:** `MobMayhem/config.yml` bleek per ongeluk de complete
      Parkour Warrior-config te bevatten (verkeerd bestand gekopieerd bij
      het opzetten van de module) — geen van de echte Mob Mayhem-sleutels
      (`game.max-waves`, `modifiers.*`, `points.per-wave`, `world.template-
      name`, ...) stond er ooit in, alles liep op de Java-hardcoded
      fallback-defaults. Herschreven met alle sleutels die de code
      daadwerkelijk leest.
- [x] **TGTTOS: fog of war** — niet letterlijk "10 blokken" (TGTTOS heeft
      geen padmodel/checkpoints om dat aan te meten), maar wel een echt
      werkende benadering: per map een kans (`game.fog-of-war-chance`,
      15%) op refreshte, korte Blindness voor alle nog-racende spelers —
      zelfde bewezen patroon als Block Party's DARKNESS-chaos-event.
- [x] **TGTTOS: rubber-banding** — elke seconde herberekend wie van de nog
      racende spelers het verst van het finish-gebied af staat (via
      `Map.getFinishPos1/2()` als middelpunt), en geeft die een korte
      Speed II. Volgt dus altijd de ACTUELE laatste plek, plakt nooit aan
      iemand die al is ingehaald. Pas actief bij 3+ nog-racende spelers.
      Config: `game.rubber-banding` (aan/uit).
- [x] **TNT Tag: krimpende arena** — er bestond al een tijdelijke chaos-
      event-squeeze (gaat na 20s weer open) en een showdown-only shrink
      (laatste 2 spelers) — geen van beide is een blijvende, geleidelijke
      krimp door de hele match. Nieuw: `applyProgressiveShrink()` krimpt de
      grens na ELKE ronde permanent (exponentieel, `game.progressive-
      shrink-percent`, default 8% van wat er nog over is), met een
      bodemwaarde (`progressive-shrink-min-radius`, default 12) en wijkt
      voor de showdown-shrink zodra die actief wordt.
- [x] **Survival Games: Feast-moment** — nieuwe `ChestStocker.Tier.FEAST` +
      `spawnFeastChest(center)`, getriggerd vanuit `startDeathmatch()`
      (precies wanneer de border begint te sluiten). Plaatst één kist met
      hoge-kans-toploot (`loot.feast` in config.yml) direct bij de
      cornucopia, met aankondiging + deeltjeseffect. Aan/uit via
      `game.feast-enabled`.
- [x] **Mob Mayhem: boss-golf** — bleek al (grotendeels) te bestaan:
      `WaveDefinition.isBossWave()` + `WaveExecutor` geven boss-mobs al
      +50% HP, een "★ BOSS ★"-naam en een eigen aankondigingsgeluid.
      Zit alleen niet op een strikt "elke 5 waves"-cadans — de bestaande
      `WaveLibrary` heeft een handgemaakte 10-golvenprogressie met een
      mini-boss op golf 7 en een eindbaas op golf 10. Dat vond ik een
      bewuste ontwerpkeuze (verhaal-opbouw) die ik niet wilde downgraden
      naar een generieke "elke 5e golf"-regel — laat het weten als je
      toch die strikte cadans wilt.
- [x] **Zwevend MVP-kroon-deeltjeseffect** — net als de geluids-stinger in
      `BaseGameManager` gebouwd, dus gratis voor alle 15 games. Elke 0.1s
      herberekend wie van de deelnemers de meeste TOERNOOIPUNTEN heeft
      (`api.stats().getPoints(uuid)`, dus live, niet in-game-score), en
      toont een roterende gouden dust-ring boven diens hoofd. Geen kroon
      zolang niemand nog punten heeft (voorkomt willekeurige "leider" bij
      0-0). Code: `BaseGameManager.startMvpCrown()`/`stopMvpCrown()`.
- [~] **Live "dichtstbijzijnde race"-indicator** — alleen **TGTTOS** gedaan:
      bossbar toont nu de live koploper (🏁 naam), en elke speler ziet op
      zijn eigen actionbar "👑 KOPLOPER" of "-Xm" (afstand tot de koploper),
      herberekend elke seconde via de nieuwe `liveRaceOrder()`-helper (ook
      hergebruikt door rubber-banding). **Elytra Endrium en Parkour Warrior
      nog niet** — die games werken met checkpoints/stages in plaats van
      rechte-lijn-afstand tot een vast finish-punt, dus daar is een ander
      progress-model nodig (checkpoint-volgorde i.p.v. Euclidische afstand)
      — geen kopieerwerk, apart te bouwen.
- [ ] **Automatische dynamische commentaarregels** bij grote momenten —
      nog niet gebouwd. Bleek tijdens verkenning een écht verspreid
      probleem: multi-kill-detectie bestaat vandaag alleen losstaand in
      QuakeCraft's eigen `KillBonusManager` (met zijn eigen 20/40-punten-
      bonussen, los van `PointsManager.getDoubleKillBonus()`/
      `getTripleKillBonus()`/`getMegaKillBonus()` — die drie bestaan wél in
      points.yml/`PointsManager` maar worden NERGENS aangeroepen, dode
      getters). Elke game heeft bovendien al zijn eigen losse "groot
      moment"-aankondigingen (TNT Tag's clutch/stage-titels, Block Party's
      clutch-saves, SkyWars' survival-bonus). Genuine cross-game
      commentaarlaag bouwen vereist eerst een gedeelde "moment"-detectie
      (vergelijkbaar met hoe `AssistTracker`/`GameSfx` nu al gedeeld zijn),
      en is het grootste overgebleven stuk werk — apart oppakken.

## Groot (nieuwe subsystemen, meerdere sessies werk)

- [ ] **Slow-motion killcam-replay** van de winnende laatste klap — vereist
      opname van combat/beweging + afspelen via het camera/cinematic-systeem.
- [ ] **Automatisch gegenereerde highlight-reel** — "interessant moment"-
      detectie + samengestelde camera-routes; bouwt voort op de killcam.
- [ ] **Live drama-dashboard voor de host** — kleinste scoregaten, grootste
      comeback-potentie, real-time over alle actieve games heen.

---

## Geparkeerd (niet vergeten, maar nog geen prioriteit)

- Bounty-systeem ("Gouden Doelwit") — eerder voorgesteld, user wilde dit
  parkeren voor later.

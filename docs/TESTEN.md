# TESTEN — het ene testbestand

Alles wat nog niet live is getest, in één lijst, in de volgorde waarin je het het slimst doet.
Dit bestand vervangt alle oudere testlijsten (die staan nu in `docs/archief/`).

**Zo gebruik je het**
1. Werk van boven naar beneden. Blok A en B eerst: alles daarna bouwt erop.
2. Elke regel is één handeling: `wat je doet` → **wat je moet zien**. Klopt het niet? Laat het `[ ]` staan en noteer de fout (zie onderaan).
3. Solo testen kan bijna overal: `/kmcbot add 8` geeft fake spelers, `/kmctest <gameId>` bouwt een testarena, `/kmcauto skip` slaat een ceremonie over.
4. ⚠ = hier verwacht ik het eerst problemen (nooit live gedraaid, hangt af van Minecraft-gedrag).

| Blok | Wat | Tijd | Solo? |
|---|---|---|---|
| A | Bouwen, deployen, opstarten | 10 min | ja |
| B | HUD en tablist (net aangepast) | 5 min | ja |
| C | Toernooi starten + setup-menu | 10 min | ja |
| D | Gedeelde systemen (geluid, kroon, bonussen) | 15 min | met bots |
| E | Regie: pauze, rerun, finale, dryrun | 15 min | met bots |
| F | Punten en data | 10 min | ja |
| G | Presentatie: NPC's, ceremonies, tablist-FX | 15 min | ja |
| H | Games (alle 20+) | 2-3 uur | per game |
| I | Zes arcade-games | 45 min | per game |
| J | Platform: API, clips, camera, anti-grief | 15 min | ja |
| K | Hele toernooi: `/event simulate`, einde | 20 min | ja |
| L | Losse dingen: easter eggs, `/tutorial` | 10 min | ja |

> Een **ronde** is `automation.games-per-round` games (standaard 3). "1/8 na één game" is dus geen fout.

---

## A. Bouwen, deployen, opstarten

- [ ] `mvn test` → alle tests groen (o.a. `ArcadeLogicTest`, `CraftingLogicTest`, `HudFitTest`, `TabLayoutTest`).
- [ ] `.\deploy.ps1 -Plugins "<server>\plugins"` → 28 jars gekopieerd, `kmc-storage` zit er **niet** bij.
- [ ] Server herstarten (niet `/reload`) → console zonder rode regels, elke plugin meldt "enabled".
- [ ] WorldEdit 7.3.0 staat in `plugins/` (nodig voor schematics, Speed Build en testarena's).
- [ ] `/gg`, `/clip list`, `/kmchud`, `/teamprofile`, `/kmcaudit` bestaan → geen "onbekend commando".
- [ ] `/kmcauto start` vanuit de console → start meteen, zonder menu.

## B. HUD en tablist

Open de tablist met **Tab** (houd ingedrukt). Hierboven stond je eigen naam nog onderaan en team 1 stond te ver links.

- [ ] ⚠ Je **eigen naam staat niet meer onder de tabel** (alleen de tabel zelf, geen hoofd/ping-balkjes).
- [ ] Rij **"1."** begint op dezelfde plek als rij 2 t/m 8 (jouw eigen team is vet, dat mag de uitlijning niet verschuiven).
- [ ] Titel, "TUSSENSTAND" en de footer staan **in het midden** van de tabel, niet naar rechts verschoven.
- [ ] Team met **1 speler** → naam gecentreerd onder de teamregel.
- [ ] Team met **2 en 3 spelers** (`/kmcbot add 3`, daarna in één team) → namen op één regel, met een `•` ertussen, gecentreerd.
- [ ] Team met 6+ lange namen → maximaal 2 regels, laatste eindigt op `+N`.
- [ ] Teams zonder spelers nemen geen lege regels in.
- [ ] De rode getallen (13, 12, 11 …) rechts in de sidebar zijn weg.
- [ ] `/kmchud test` → 15 seconden lang zie je **alles tegelijk**:
  - [ ] sidebar rechts met titel *HUD-TEST*; KORT en MIDDEL staan er heel, LANG en VET eindigen op `…`
  - [ ] bossbar boven met een lange tekst, netjes afgekapt voor de rand
  - [ ] actionbar onder in beeld
  - [ ] drie grote titels na elkaar; in de ondertitel staat de breedte en "past op GUI-schaal ≤ N"
  - [ ] Noteer: welke titel past nog volledig op jouw scherm? (bij GUI-schaal 4 is dat alleen nr. 1 en 2)
- [ ] `/kmchud compact` en `/kmchud minimal` → sidebar wordt korter, geen overlap met de rest; `/kmchud normal` zet het terug.
- [ ] Na de test staat de gewone sidebar er vanzelf weer.

## C. Toernooi starten en het setup-menu

Nodig: minstens 2 teams en 1 speler online.

- [ ] `/kmcauto start` als speler → setup-menu opent (er start nog niets).
- [ ] Game-tegel aanklikken → grijs ("overgeslagen"); nog een keer → weer aan.
- [ ] Menu sluiten zonder te starten, `/kmcvote` → de uitgezette game is geen optie.
- [ ] Tegel Tussenpauze en Stemduur: links/rechts klikken → ±5 s, de lore verandert mee.
- [ ] "Stemmen op volgende game" → label wisselt AAN/UIT.
- [ ] "Start inplannen": typ `in 1` → menu toont "Gepland over ~1m"; typ `cancel` → plan is weg.
- [ ] **START TOERNOOI NU** → toernooi start met jouw instellingen, uitgezette games komen nooit voorbij.
- [ ] `/kmcauto start force` → slaat het menu over en start direct.
- [ ] `/kmcauto start` → openingsceremonie in 5 fases, leestempo schaalt met de tekst, geen dubbele WelcomeBroadcaster.
- [ ] `/kmcauto skip` slaat de ceremonie over; `/kmcauto stop` stopt hem echt.
- [ ] Nieuwe speler (of `/kmclanguage`) → taalmenu verschijnt; NL en EN werken, ontbrekende sleutels vallen terug op de standaard.

## D. Gedeelde systemen (steekproef in 2-3 games)

- [ ] **Start- en eindgeluid:** bij het begin van een game een pling, bij het einde een stinger; een eigen `sounds.game-start` in een game-config wijzigt toonhoogte/volume.
- [ ] **MVP-kroon:** zodra iemand punten heeft zweeft een gouden ring boven de leider en volgt die. Bij 0-0 heeft **niemand** een kroon.
- [ ] **Kroon verspringt** naar de nieuwe leider binnen ~1 game.
- [ ] **Koploper-flits:** bij 0-0 knippert de sidebar-titel niet; als team B team A inhaalt, flitst "⬆ NIEUWE KOPLOPER" ~3 s, met geluid en chatbericht.
- [ ] **Comeback-bonus:** team ≥100 punten achter → hun punten zijn ×1.25, zelfde bedrag voor speler én team; een team dat niet laatste is krijgt het gewone bedrag.
- [ ] **Golden Hour:** `tournament.total-rounds: 2` zetten → in één ronde verschijnt "✨ GOLDEN HOUR ✨", pas als de eerste echte game van die ronde start (niet over de openingsceremonie heen). Kills tellen dan ook ×multiplier. Maar één keer per toernooi.
- [ ] **Assist-punten (SkyWars):** A slaat B, B slaat A, C maakt A af → C krijgt kill minus 20%, B krijgt de assist (20%). Een hit van >10 s geleden telt niet. (Andere games hebben nog geen assists.)

## E. Regie (golf 1)

- [ ] `/kmcauto preset …` en `/kmcauto dryrun` → een korte cyclus draait **zonder punten te boeken**.
- [ ] `/kmcauto games` en `/kmcauto time` → aantal games en tijdschatting kloppen met het startmenu.
- [ ] Team ready-check → iedereen moet bevestigen voor de start.
- [ ] `/pause` midden in een game → wacht tot het veilig is, timers staan stil; `/resume` hervat. ⚠ Test dit in minstens één arcade-game en in Block Party.
- [ ] `/kmcauto rerun` → punten van de game worden teruggedraaid en de game wordt opnieuw gespeeld.
- [ ] Director-GUI (`/kmcauto director`) opent en de knoppen doen wat ze zeggen.
- [ ] Finale (top 2): Dodgebolt start, best-of-3 alleen als finale, team-eliminatie, einde sluit het toernooi netjes af.
- [ ] Automatic Overtime bij gelijkspel in TheBridge en QuakeCraft.
- [ ] `/kmcvalidate` (TournamentValidator) meldt een ontbrekende arena of spawn.

## F. Punten en data (golf 2)

- [ ] Na een game: score-uitsplitsing per team in chat; `ledger.csv` bevat elke award.
- [ ] Einde van het event: archief, podium, awards, credits, feedback, statsboek, reset (`/kmcauto skip` slaat over).
- [ ] `/kmctrack` toont baanrecords, `/kmcseason` het seizoen, `/kmcfeedback` neemt een bericht aan.
- [ ] `/kmctournament status` na een volledig toernooi: event-nummer +1, punten op 0.

## G. Presentatie (golf 3)

- [ ] Tablist in MCC-stijl met **live** game-punten tijdens een game, toernooistand daarbuiten.
- [ ] `/kmcnpc rank set 1..10` en `rank own` → rank-NPC's tonen top 10 en jouw eigen plek.
- [ ] `/kmcnpc presenter set`, daarna `presenter say <tekst>` → presenter praat.
- [ ] Intro-FX van een game, leaderboard-animatie met pijltjes, houten lepel (bij 3+ teams), teamjingles.
- [ ] Chat is dicht tijdens uitleg en ceremonie, **ook voor OP**.
- [ ] GG-knop klikbaar na een game; verrassingsgame: naam verborgen in de stem-GUI en de line-up.
- [ ] Flyovers lopen vloeiend, geen ruk bij waypoints.

## H. Games

Per game: `/kmctest <gameId>` (arena + config in één keer) of de eigen setup, dan `/<cmd> start`.

### Block Party
Setup: `/blockparty pos1`, `pos2` (blok waar je OP staat), `spectator`, `voidy`, `status` → "klaar ✓".
- [ ] Ronde 1 gebruikt alleen geel/wit/lichtgrijs/zwart (of jouw `presetfloor`); vanaf ronde 2 alle 16 kleuren.
- [ ] `/blockparty presetfloor` slaat het patroon op; `clearpreset` zet het terug.
- [ ] Doelkleur komt via titel + bossbar + actionbar + chat; groot aftellend cijfer, "VERDWIJN!" op 0.
- [ ] Kleurblok in hotbar-slot 5, heet alleen "BLAUW"; verplaatsen/droppen/plaatsen/F is geblokkeerd; weg na eliminatie.
- [ ] Niet-teamspelers in de arena worden niet meegenomen.
- [ ] Zelfde kleur nooit twee rondes achter elkaar; de doelkleur heeft altijd genoeg blokken voor iedereen.
- [ ] Vanaf ronde 5: chaos-events, o.a. "⇄ MIRROR" (doelkleur is dodelijk), Double/Fake/Colour-Blind/Rapid-Fire.
- [ ] Laatste seconde nog op de kleur stappen → "⚡ CLUTCH SAVE".
- [ ] Niemand valt door de void tijdens countdown/grace; vloer is al geverfd bij de landing.
- [ ] Punten: 1e ≈ 200 + 10 per overleefde ronde, daarna 2e/3e/top-5/top-10 volgens `config.yml`.
- [ ] Vloer wordt na de game gereset.

### TGTTOS V2
Setup: `/kmcsetup` → TGTTOS, minstens 1 map.
- [ ] Start met ≥4 spelers → "5 maps", Map 1/5.
- [ ] Eerste finishers krijgen medailles, nog **geen** KMC-punten.
- [ ] 50% klaar → "HELFT VAN HET VELD IS GEFINISHT" + 2 minuten bossbar-aftelling; finishen telt nog mee.
- [ ] Op 0:00 → DNF zonder punten; heel veld klaar → map eindigt meteen; niemand op 50% → `max-map-seconds` dwingt de aftelling.
- [ ] Na map 5: EINDUITSLAG top-5, KMC-punten één keer, terug naar lobby.
- [ ] "🌫 FOG OF WAR" verschijnt af en toe (15%) en maakt het zicht echt donker.
- [ ] Voorlaatste krijgt Speed II en dat verhuist als iemand anders laatste wordt.
- [ ] Bossbar "🏁 naam" toont de leider; actionbar toont "👑 KOPLOPER" of "-Xm".

### TNT Tag
- [ ] Border is zichtbaar kleiner na elke ronde, niet kleiner dan `progressive-shrink-min-radius` (12).
- [ ] Final Showdown (2 spelers) neemt het krimpen over; nieuwe game start met volle border.

### Survival Games
- [ ] "🍗 THE FEAST" één keer, kist verschijnt boven het midden met duidelijk betere loot; `feast-enabled: false` → geen kist.
- [ ] Supply drop: lichtstraal + kist valt, cornucopia-refill, rarity staat in de lore.

### Mob Mayhem
Setup: `/mm status` → template-wereld ✔, spawn ✔, ≥4 mob-spawns. `/mm pos1/pos2` slaat hele blokken op en weigert buiten de template-wereld.
- [ ] Je staat in een **gekloonde** wereld `mm_game_<team>_<random>`, niet in de template.
- [ ] Wave 1 spawnt echt mobs; alles gedood → "wave cleared", na ~5 s start wave 2 ⚠ (dit hing eerder).
- [ ] Meerdere waves achter elkaar; sterven → spectator, kan niet meer slaan.
- [ ] `/mm stop` → gekloonde wereld wordt verwijderd, geen `mm_game_*` map over.
- [ ] Achievements: wave 10 → `survivor`, wave 5 zonder dood → `untouchable`, bossmob → `boss_slayer`.
- [ ] Powerups (optioneel): `/mm addpowerupspawn` ×2-3, start → binnen 30 s een item; oppakken geeft effect; na 45 s verdwijnt hij; `clearpowerupspawns` werkt.

### Speed Build
Nodig: 10 `.schem` in `plugins/KMCCore/schematics/`. Setup: `/speedbuild anchor`, `spawn`, `addbuild …` ×10, `status` → 10/10.
- [ ] Start → elke speler in eigen slot, blauwdruk ernaast.
- [ ] Hotbar: GROEN = klaar, ROOD = blauwdruk opnieuw, GOUD = einde (alleen bij build 10).
- [ ] GROEN toont nauwkeurigheid % + punten; builds gaan op volgorde; buiten je slot bouwen kan niet.
- [ ] Zelfde bouwsel twee keer → identiek %.
- [ ] Eindoverzicht klopt; punten landen bij speler én team.

### Hole in the Wall
- [ ] `/kmctest hole_in_the_wall` → muren met gaten komen aan; vallen = eruit; plaatsingspunten per ronde.
- [ ] Legenda bij ronde 1, actionbar "muur vanuit Rood/Blauw/Geel/Groen" met Rood=West, Blauw=Zuid, Geel=Oost, Groen=Noord.
- [ ] `/hitw testwall` toont een testmuur; events (bv. snelheid) werken.

### Formula MC
- [ ] `/kmctest formula_mc`: kwalificatie → pole → race (GRID/QUALI/RACE).
- [ ] Ronde-detectie, checkpoints, snelste-ronde-bonus; vastzittende boot wordt teruggezet.
- [ ] Botsen kan niet; andere racers zichtbaar binnen 10 blokken; `/fmc cam` flyover.
- [ ] ⚠ Boost pad en item box geven écht snelheid (server-`setVelocity` op boten is onzeker); stun en schild werken.
- [ ] `/fmc weather rain` verandert de baan; baanrecord via `/kmctrack`.

### Rising Inferno
- [ ] `/kmctest rising_inferno` → lava stijgt, spelers branden, bonus-orbs, finish; achievements `inferno.yml` ontgrendelen.
- [ ] ⚠ De lava (BlockDisplay) is zichtbaar en doet schade op het juiste niveau.

### Overige games
- [ ] **Dodgebolt:** best-of-3 alleen als finale; team-eliminatie.
- [ ] **ParkourWarrior / ElytraEndrium / AdventureEscape:** finish-cam, baanrecords (`/kmctrack`), highlights.
- [ ] **Spleef, QuakeCraft, SkyWars, TheBridge, LuckyBlock, ParkourWarrior, ElytraEndrium:** `/kmctest list` toont welke een testarena hebben; `/kmctest <id>` → game start, speelt uit en geeft punten.
- [ ] **Lobby:** `/ttt`, `/darts` op een Target-blok, vissen, parkour, `/lobbyspleef` → geen toernooipunten.

## I. Zes arcade-games

Per game: `/kmctest <id>`, daarna `/<cmd> start dry` (speelt alles **zonder** punten) en daarna één keer echt.
Alle zes: `/<cmd> world | spawn | pos1 | pos2 | validate | status | start [dry] | stop`.

- [ ] **Rocket Rush** (`/rocket`): checkpoints in volgorde, ring maar één keer, pickup, vallen → checkpoint, finish 100/85/70.
- [ ] **Knockout Kings** (`/knockout`): ⚠ knockback voelt goed, val = eliminatie voor laatste aanvaller, farm-limiet, 3 modifiers.
- [ ] **Crafting Countdown** (`/craftcountdown`): kist gevuld, recept uitvoerbaar, stuk voor stuk inleveren, dubbel inleveren geeft niets.
- [ ] **Zone Zero** (`/zonezero`): capture, 2 teams = contested, zones wisselen, geen dubbele hold-punten.
- [ ] **Crown Control** (`/crown`): capture 5 s, lock 20 s, menu via nether star of `/crown use`, geweigerde activatie kost niets, lockdown.
- [ ] **Core Charge** (`/corecharge`): orbs/stations, vaardigheden met cooldown, Final Overload alleen in de laatste 30 s, eindbonus.
- [ ] Alle zes: `/pause` stopt de klok; `/<cmd> stop` midden in een game ruimt alles op (geen blijvende entities).
- [ ] Alle zes: dood = respawn met spawnbescherming (waar PvP).

## J. Platform (golf 5)

- [ ] `/kmcapi start` en `http://127.0.0.1:8765/overlay` → live standen in de OBS-overlay.
- [ ] `/kmccamera record game <gameId>` → vlieg → `record stop`; de flyover speelt de route.
- [ ] `/clip start`, speel een game; `clip-markers.csv` bevat game start/einde en kills; `/clip <tekst>` zet een marker (cooldown 5 s).
- [ ] ⚠ Spectator in een game: kompas, volgen, leider-tracker; na het einde weg.
- [ ] Anti-grief als **niet-OP**: item-frame, emmer, kist en enderpearl in de lobby geblokkeerd.
- [ ] `/kmcaudit` na een minuut spelen → geen onveilige teleports; `/kmcaudit clear` wist de lijst.
- [ ] `/kmcemblem`, `/teamprofile` openen; `/kmcemblem wear` zet een banner op.
- [ ] Verhaallijn: per event verschijnen de regels in chat + presenter.
- [ ] Grote arena's laden zonder lag-piek (async chunk-preload en paste).

## K. Hele toernooi

⚠ `/event simulate` heeft echte gevolgen (eventnummer +1, bij een volledige run reset hij punten). Doe het op een testserver.

- [ ] Met een echt toernooi actief: `/event simulate 5 16` → **weigert**.
- [ ] Zonder toernooi: `/event simulate <total-rounds> 16`:
  - [ ] nooit dezelfde game twee keer achter elkaar
  - [ ] multiplier loopt op volgens `tournament.multipliers`
  - [ ] einde: winnaartitel, topspelers, **Fan Favorite**-GUI, statsboek in je inventory
  - [ ] `/kmctournament status`: event +1, punten 0
- [ ] **Fan Favorite:** GUI opent ~6 s na de winnaar, stem → "✔ Stem genoteerd", na 20 s winnaar met titel + geluid; "Fan Favorite" staat in `/kmcachievements`; zonder stemmen komt "Geen stemmen…" (geen crash).
- [ ] `/event simulate 2 16` (minder rondes) → stopt zonder boek en zonder punten-reset.
- [ ] `/event rollback <sim-pre-… label>` maakt de simulatie ongedaan.

## L. Losse dingen

- [ ] **Easter eggs:** `/kmclobbynpc spawn easter_egg` ×3 → geen zwevende naam; elke NPC altijd dezelfde regel; eerste keer "Nieuwe easter egg gevonden (1/3)"; tweede keer niet; 3 s cooldown.
- [ ] Alle gevonden → broadcast + "Easter Egg Hunter" in `/kmcachievements`; na herstart nog steeds "gevonden".
- [ ] **`/tutorial`:** 8 categorieën; "Spellen" opent `/kmchelp`; "Taal" opent de echte taalkiezer; alle knoppen openen de echte GUI; "Terug" werkt.
- [ ] **Paper 26.3-valkuilen:** particle-kleuren, jump-boost-freeze, peaceful difficulty, geen honger in de lobby-radius.
- [ ] **PvP-poort:** PvP uit in de lobby, aan in games die `handlesPlayerPvp()` zeggen; `GameStopRequestEvent` stopt een game netjes.

---

## Nog niet gebouwd (geen fout, niet melden)

- Crafting Countdown: alleen de modifier `DOUBLE_RECIPE`.
- Opnieuw joinen na een disconnect in de arcade-games.
- Achievements voor de zes arcade-games.
- Potion-effecten lopen door tijdens `/pause`.
- Assist-punten alleen in SkyWars; race-gap-indicator alleen in TGTTOS.

## Een fout melden

Eén regel per fout, dan los ik alles in één ronde op:

```
Blok/regel:  H / Mob Mayhem / wave 2 start niet
Verwacht:    na 5 s start wave 2
Gebeurde:    niets, bossbar blijft staan
Console:     (plak de regel of "geen")
```

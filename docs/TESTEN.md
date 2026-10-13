# TESTEN — het ene testbestand

Alles wat nog niet live is getest, in één lijst. Afgevinkte dingen staan er niet meer in; nieuwe fixes en
nieuwe dingen staan onder **N** (eerst doen). Oudere lijsten staan in `docs/archief/`.

**Zo gebruik je het**
1. Werk van boven naar beneden. Blok N eerst: dat zijn de fixes van je vorige testronde.
2. Elke regel is één handeling: `wat je doet` → **wat je moet zien**. Klopt het niet? Laat het `[ ]` staan en noteer de fout (zie onderaan).
3. Solo testen kan bijna overal: `/kmcbot add 8` geeft fake spelers, `/kmctest <gameId>` bouwt een testarena, `/kmcauto skip` slaat een ceremonie over.
4. ⚠ = hier verwacht ik het eerst problemen (nooit live gedraaid, hangt af van Minecraft-gedrag).

| Blok | Wat | Tijd | Solo? |
|---|---|---|---|
| N | Nieuw na de vorige testronde (fixes + herbouwde games) | 40 min | met bots |
| A | Bouwen, deployen, opstarten | 5 min | ja |
| D | Gedeelde systemen (geluid, kroon, bonussen) | 15 min | met bots |
| E | Regie: pauze, rerun, finale, dryrun | 15 min | met bots |
| F | Punten en data | 10 min | ja |
| G | Presentatie: NPC's, ceremonies | 15 min | ja |
| H | Games (alle 20+) | 2-3 uur | per game |
| I | Zes arcade-games (opnieuw getest in N, hier de rest) | 30 min | per game |
| J | Platform: API, clips, camera, anti-grief | 15 min | ja |
| K | Hele toernooi: `/event simulate`, einde | 20 min | ja |
| L | Losse dingen: easter eggs, `/tutorial` | 10 min | ja |

> Een **ronde** is `automation.games-per-round` games (standaard 2 in `config.yml`). "1/8 na één game" is dus geen fout.

---

## N. Nieuw na de vorige testronde

Deploy eerst **alle** jars opnieuw (`.\deploy.ps1 -Plugins "<server>\plugins"`): KMCCore, kmc-game-api en kmc-stats
zijn aangepast, en alle arcade-games, Formula MC, SkyWars en nog een paar.

### N1. Tablist, HUD, teams
- [ ] Tab: teamnamen staan **gecentreerd en vet**, jouw team met een `➤` ervoor; spelersnamen eronder gecentreerd.
- [ ] Sidebar "Top Teams": #4 en #5 tonen netjes hun punten (geen `&…` meer).
- [ ] `/teamprofile`: onderin staan **alle 8 teams** (9 plekken).
- [ ] Team met 6+ lange namen in de tab → max. 2 regels, laatste eindigt op `+N` *(kon je niet testen: alleen als dat met bots lukt)*.

### N2. Toernooi-start en ceremonie
- [ ] Taalbericht (`/kmcauto start`): nette kaart met streep, titel, twee klikbare regels (NL/EN), geen actionbar eroverheen.
- [ ] Teamshowcase: spelers staan **naast elkaar** (ook als er minder podium-spots zijn dan spelers), kijken recht de camera in, en staan in beeld.
- [ ] Game-lineup: regel onderaan klopt met de werkelijkheid ("Alle X games komen aan bod…" of "Er worden X van de Y games gespeeld…").
- [ ] Setup-menu: nieuwe tegel **Verrassingsgames** (0-3). Op 1: bij de start wordt een **willekeurige** game uit de rotatie geheim gehouden (`???` in stemmen en lineup) en pas onthuld als hij aan de beurt is. Geen games meer voorgeselecteerd.
- [ ] Setup-menu: nergens meer twee games met dezelfde (boog-)icoon; Dodgebolt heeft een spectral arrow.

### N3. Na een game
- [ ] Volgorde: score-uitsplitsing → GG-knop → leaderboard-animatie → **pas dan** "Halverwege het toernooi!" (indien van toepassing) en "Tussenpauze" met de timer. De timer start pas na de animatie.
- [ ] "Jouw game": `Je werd #X/32 | je team werd #Y/8` (echte aantallen, niet #1/1 · #1/4).
- [ ] Labels in de uitsplitsing: `klassering`, `teambonus`, `game-doelen` (doelen = punten voor taken, zones, checkpoints, orbs…).
- [ ] Tab-punten van de lopende game = de punten in de sidebar van de game = de score-uitsplitsing (alles inclusief ronde-multiplier).

### N4. Einde van het toernooi
- [ ] Podium: bij 3e, 2e en 1e plaats wordt een blokken-podium in chat opgebouwd dat vol loopt; daarna MVP en records.
- [ ] `/event simulate 3 16`: teams krijgen nu punten (ook als alle teams vol zitten met `/kmcbot`-spelers), de top-3 toont echte teamscores.
- [ ] Fan Favorite-GUI: past voor 32 spelers (zes rijen, 45 plekken).
- [ ] **Advancements:** druk **L** → tab *KMC Achievements* met een vak per groep en daaronder de achievements. Behaal er een → toast. `/kmcachievements` (als speler) wijst naar L en toont je aantal. Console bij start: `Advancement tab: N registered, 0 failed`.
  - [ ] Staat er `failed` > 0 of geen tab? Plak de console-regel `[KMC/Achievements] …` (die noemt de eerste foutreden).
  - [ ] Staat `settings.achievement-system` op `v1` dan werkt de tab met de V1-achievements; op `v2` met alle game-achievements (kmc-stats). Alleen `v2` geeft de games-yml-achievements (Inferno, BlockParty, …).

### N5. Formula MC
- [ ] Racetijd in de sidebar tijdens qualifying telt vanaf je eigen ronde-start (geen `2986406:59.72` meer).
- [ ] ⚠ Boten gaan **door elkaar heen** zodra ze elkaar raken (spook-botsingen, `boat.ghost-collisions`); niemand zakt door de vloer en je komt niet vast te zitten. Uit te zetten met `boat.ghost-collisions: false`.
- [ ] Na de finish: `/kmcspec` (of klik op de melding in chat) opent het kijk-menu; `/kmcspec follow` volgt de volgende speler; `/kmcspec stop`.
- [ ] Punten (Ace Race-stijl): winnaar 320, -10 per plek; DNF 0; snelste ronde +25; elk teamlid +20/+15/+10 voor het beste gemiddelde. Geen aparte teamplaats meer; de intro-kaart noemt dezelfde getallen.

### N6. Arcade-games (alle zes: `/kmctest <id>` → verwacht meteen "✔ … is klaar om te starten")
- [ ] **Spawns:** geen enkele speler staat in een ander (teamgenoten naast elkaar, ook met meer spelers dan spawns). Controleer ook: Rocket Rush, Knockout Kings, Crafting Countdown.
- [ ] **PvP:** je kunt bots slaan in Knockout Kings (stick), Zone Zero, Crown Control en Core Charge; teamgenoten niet.
- [ ] **Punten zijn van de speler:** in Zone Zero krijg alleen jij (en wie nog meer in de zone staat) de capture-punten; bots die er niet bij zijn krijgen niets.
- [ ] **Rocket Rush:** `/kmctest rocket_rush` meldt of hij klaar is; zo niet staat er wat ontbreekt. *(Wat gebeurde er eerder precies? Noteer de melding.)*
- [ ] **Core Charge:** boven je kern groeit een gekleurde pilaar mee met de energie, met een balk + percentage erboven.
- [ ] **Crown Control (herbouwd):**
  - [ ] Er liggen **3 kronen** in de zone (`crowns.count`); loop erheen om er een op te zetten (gouden helm, gloeit).
  - [ ] Een kroondrager scoort elke 10 s zelf punten; zijn team verdient energie.
  - [ ] Een vijand pakt de kroon door 3× te slaan (actionbar telt mee) of door hem uit te schakelen; bij een kill gaat hij direct naar de killer.
  - [ ] Teamgenoten kunnen elkaars kroon niet pakken; helm uitdoen via inventory kan niet.
  - [ ] Vaardigheden (nether star) werken alleen als je team minstens één kroon draagt.
- [ ] **Crafting Countdown (opnieuw herbouwd naar Build Mart, zie N8).**
- [ ] **SkyWars:** kisten openen kan pas als het spel echt begint (niet tijdens intro/tutorial/countdown). Check ook Survival Games en Lucky Block.
- [ ] Arcade-sidebars tonen de punten van je team inclusief multiplier, gelijk aan de tablist.

### N7. Game-intro-kaarten
- [ ] De punten in de intro-kaart (uitleg voor de game) kloppen met de config: SkyWars, Survival Games, Spleef, TNT Tag, The Bridge, Bingo, Parkour Warrior, Elytra Endrium, Mob Mayhem en Formula MC noemen nu de echte waarden; "1e / 2e / 3e eindplaats" komt uit `points.yml`.

---

### N8. Crafting Countdown — verzamel-game (geen craften meer)
Eerst: het oude `plugins/CraftingCountdown/config.yml` krijgt bij het opstarten vanzelf de nieuwe secties (stapel-typen, item-pool, orders); controleer de console op "New default pile types…". Bouw daarna `/kmctest crafting_countdown`.
- [ ] `/craftcountdown validate` na de test-arena → "✔ Configuratie is geldig"; haal je alle stapels weg (`pile clear`) → melding "geen stapels".
- [ ] Arena: 8 inleverpunten (vaten) op de rand, 12 stapels (6 soorten, elk twee keer) in de binnenring, elk met een zwevend label met de voorraad.
- [ ] Start: titel "VERZAMEL!", elk team ziet zijn bestelling in chat, op het bord boven het inleverpunt en in de sidebar.
- [ ] Rechtsklik op een stapel → menu met de items, voorraad en "je draagt x/128"; een klik geeft `per-take` items (zeldzame stapels minder).
- [ ] Cooldown: twee keer snel klikken → "nog Xs wachten"; leeg item → melding; boven het maximum dragen → "je draagt al het maximum".
- [ ] De stapel vult zich aan elke `piles.restock-seconds` aan (label verandert).
- [ ] Rechtsklik op **het inleverpunt van je eigen team** → gevraagde items verdwijnen uit je inventory, bord en sidebar tellen mee voor het hele team; klik op het inleverpunt van een ander team → melding van wie het is.
- [ ] Bestelling compleet → opsplitsing in chat (basis + items + snelheid + reeks = totaal), titel `✔ #n +punten`, meteen een nieuwe bestelling; de punten gaan naar wie heeft ingeleverd, naar rato van het aantal items.
- [ ] Na 3 bestellingen worden ze groter/zeldzamer (3 regels, tier 2; daarna 4 regels en tier 3).
- [ ] Snel achter elkaar voltooide bestellingen geven een reeks-bonus (+5 per stap, max +25).
- [ ] De laatste 30 s: titel "LAATSTE FASE", bossbar rood, piepjes de laatste 10 s.
- [ ] Einde: uitslag per team met bestellingen, items, snelheids- en reeks-punten; opruimen laat geen labels of borden achter; `/craftcountdown stop` midden in het spel ook niet.
- [ ] `/pause` stopt de klok en de herstock; opnieuw spelen (rerun) geeft geen dubbele punten.
- [ ] Geen item uit de pool dat nergens op een geplaatste stapel zit: haal het type `rare` weg → er komen geen diamanten, smaragden … meer in bestellingen.

## A. Bouwen, deployen, opstarten

- [ ] `mvn test` → alle tests groen (o.a. `ArcadeLogicTest`, `CraftingLogicTest`, `HudFitTest`, `TabLayoutTest`).
- [ ] `.\deploy.ps1 -Plugins "<server>\plugins"` → 28 jars gekopieerd, `kmc-storage` zit er **niet** bij.
- [ ] Server herstarten (niet `/reload`) → console zonder rode regels, elke plugin meldt "enabled".
- [ ] WorldEdit 7.3.0 staat in `plugins/`.
- [ ] `/kmcauto start` vanuit de console → start meteen, zonder menu.

## D. Gedeelde systemen (steekproef in 2-3 games)

- [ ] **Start- en eindgeluid:** bij het begin van een game een pling, bij het einde een stinger; een eigen `sounds.game-start` in een game-config wijzigt toonhoogte/volume.
- [ ] **MVP-kroon:** zodra iemand punten heeft zweeft een gouden ring boven de leider en volgt die. Bij 0-0 heeft **niemand** een kroon.
- [ ] **Kroon verspringt** naar de nieuwe leider binnen ~1 game.
- [ ] **Koploper-flits:** bij 0-0 knippert de sidebar-titel niet; als team B team A inhaalt, flitst "⬆ NIEUWE KOPLOPER" ~3 s, met geluid en chatbericht.
- [ ] **Comeback-bonus:** team ≥100 punten achter → hun punten zijn ×1.25, zelfde bedrag voor speler én team; een team dat niet laatste is krijgt het gewone bedrag.
- [ ] **Golden Hour:** `tournament.total-rounds: 2` zetten → in één ronde verschijnt "✨ GOLDEN HOUR ✨", pas als de eerste echte game van die ronde start. Kills tellen dan ook ×multiplier. Maar één keer per toernooi.
- [ ] **Assist-punten (SkyWars):** A slaat B, B slaat A, C maakt A af → C krijgt kill minus 20%, B krijgt de assist (20%). Een hit van >10 s geleden telt niet.

## E. Regie (golf 1)

- [ ] `/kmcauto preset …` en `/kmcauto dryrun` → een korte cyclus draait **zonder punten te boeken**.
- [ ] `/kmcauto games` en `/kmcauto time` → aantal games en tijdschatting kloppen met het startmenu.
- [ ] Team ready-check → iedereen moet bevestigen voor de start.
- [ ] `/pause` midden in een game → wacht tot het veilig is, timers staan stil; `/resume` hervat. ⚠ Test dit in minstens één arcade-game en in Block Party.
- [ ] `/pause` **tijdens de score-uitsplitsing/leaderboard** → de tussenpauze begint pas na `/resume`, niet dubbel.
- [ ] `/kmcauto rerun` → punten van de game worden teruggedraaid en de game wordt opnieuw gespeeld.
- [ ] Director-GUI (`/kmcauto director`) opent en de knoppen doen wat ze zeggen.
- [ ] Finale (top 2): Dodgebolt start, best-of-3 alleen als finale, team-eliminatie, einde sluit het toernooi netjes af.
- [ ] Automatic Overtime bij gelijkspel in TheBridge en QuakeCraft.
- [ ] `/kmcvalidate` meldt een ontbrekende arena of spawn.

## F. Punten en data (golf 2)

- [ ] Na een game: `ledger.csv` bevat elke award.
- [ ] Einde van het event: archief, podium, awards, credits, feedback, statsboek, reset (`/kmcauto skip` slaat over).
- [ ] `/kmctrack` toont baanrecords, `/kmcseason` het seizoen, `/kmcfeedback` neemt een bericht aan.
- [ ] `/kmctournament status` na een volledig toernooi: event-nummer +1, punten op 0.

## G. Presentatie (golf 3)

- [ ] `/kmcnpc rank set 1..10` en `rank own` → rank-NPC's tonen top 10 en jouw eigen plek.
- [ ] `/kmcnpc presenter set`, daarna `presenter say <tekst>` → presenter praat.
- [ ] Intro-FX van een game, leaderboard-animatie met pijltjes, houten lepel (bij 3+ teams), teamjingles.
- [ ] Chat is dicht tijdens uitleg en ceremonie, **ook voor OP**.
- [ ] GG-knop klikbaar na een game.
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
- [ ] Punten: 1e ≈ 250 + 10 per overleefde ronde, daarna volgens `config.yml`; de intro-kaart noemt hetzelfde.
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

### Formula MC (rest, zie ook N5)
- [ ] `/kmctest formula_mc`: kwalificatie → pole → race (GRID/QUALI/RACE).
- [ ] Ronde-detectie, checkpoints, snelste-ronde-bonus; vastzittende boot wordt teruggezet.
- [ ] Andere racers zichtbaar binnen 10 blokken; `/fmc cam` flyover.
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

## I. Arcade-games (rest)

Alle zes: `/<cmd> world | spawn | pos1 | pos2 | validate | status | start [dry] | stop`; `start dry` boekt geen punten.

- [ ] **Rocket Rush** (`/rocket`): checkpoints in volgorde, ring maar één keer, pickup, vallen → checkpoint, finish 100/85/70.
- [ ] **Knockout Kings** (`/knockout`): ⚠ knockback voelt goed, val = eliminatie voor laatste aanvaller, farm-limiet, 3 modifiers.
- [ ] **Zone Zero** (`/zonezero`): twee teams = contested, zones wisselen, geen dubbele hold-punten.
- [ ] **Core Charge** (`/corecharge`): vaardigheden met cooldown, Final Overload alleen in de laatste 30 s, eindbonus.
- [ ] Alle zes: `/pause` stopt de klok; `/<cmd> stop` midden in een game ruimt alles op (geen blijvende entities, ook geen kroon-/kern-displays).
- [ ] Alle zes: dood = respawn met spawnbescherming (waar PvP), en je respawnt naast je teamgenoten, niet erin.

## J. Platform (golf 5)

- [ ] `/kmcapi start` en `http://127.0.0.1:8765/overlay` → live standen in de OBS-overlay.
- [ ] `/kmccamera record game <gameId>` → vlieg → `record stop`; de flyover speelt de route.
- [ ] `/clip start`, speel een game; `clip-markers.csv` bevat game start/einde en kills; `/clip <tekst>` zet een marker (cooldown 5 s).
- [ ] ⚠ Spectator in een game: `/kmcspec` menu/volgen/leider; na het einde weg.
- [ ] Anti-grief als **niet-OP**: item-frame, emmer, kist en enderpearl in de lobby geblokkeerd.
- [ ] `/kmcaudit` na een minuut spelen → geen onveilige teleports; `/kmcaudit clear` wist de lijst.
- [ ] `/kmcemblem`, `/kmcemblem wear`.
- [ ] Verhaallijn: per event verschijnen de regels in chat + presenter.
- [ ] Grote arena's laden zonder lag-piek (async chunk-preload en paste).

## K. Hele toernooi

⚠ `/event simulate` heeft echte gevolgen (eventnummer +1, bij een volledige run reset hij punten). Doe het op een testserver.

- [ ] Met een echt toernooi actief: `/event simulate 5 16` → **weigert**.
- [ ] Zonder toernooi: `/event simulate <total-rounds> 16`:
  - [ ] nooit dezelfde game twee keer achter elkaar
  - [ ] multiplier loopt op volgens `tournament.multipliers`
  - [ ] einde: winnaartitel, podium (N4), **Fan Favorite**-GUI, statsboek in je inventory
  - [ ] `/kmctournament status`: event +1, punten 0
- [ ] **Fan Favorite:** stem → "✔ Stem genoteerd", na 20 s winnaar met titel + geluid; "Fan Favorite" staat in je advancements; zonder stemmen komt "Geen stemmen…" (geen crash).
- [ ] `/event simulate 2 16` (minder rondes) → stopt zonder boek en zonder punten-reset.
- [ ] `/event rollback <sim-pre-… label>` maakt de simulatie ongedaan.

## L. Losse dingen

- [ ] **Hall of Fame-NPC** (`/kmclobbynpc spawn hof`): rechtsklik opent het overzicht: top 3 met eigen skin en teamnaam, ranks 4-10 eronder, teams als banner in hun embleem (klik = teamprofiel), knop "Records aller tijden".
- [ ] Easter-egg "Easter Egg Hunter" staat nu als advancement in de KMC-tab (L).
- [ ] **Paper 26.3-valkuilen:** particle-kleuren, jump-boost-freeze, peaceful difficulty, geen honger in de lobby-radius.
- [ ] **PvP-poort:** PvP uit in de lobby, aan in games die `handlesPlayerPvp()` zeggen (nu ook alle arcade-games); `GameStopRequestEvent` stopt een game netjes.

---

## Nog niet gebouwd (geen fout, niet melden)

- Opnieuw joinen na een disconnect in de arcade-games.
- Achievements voor de zes arcade-games.
- Potion-effecten lopen door tijdens `/pause`.
- Assist-punten alleen in SkyWars; race-gap-indicator alleen in TGTTOS.
- Titels in games zijn niet aan GUI-schaal aangepast (alleen `/kmchud test` laat zien waar het misgaat).

## Een fout melden

Eén regel per fout, dan los ik alles in één ronde op:

```
Blok/regel:  N6 / Crown Control / kroon pakken
Verwacht:    na 3 klappen is de kroon van mij
Gebeurde:    niets, de teller blijft op 1/3
Console:     (plak de regel of "geen")
```

# TESTEN — alles wat getest moet worden voor een hoofdevent

Dit is de volledige lijst voor een echt event: elk systeem en elke game, ook wat eerder al een keer werkte. Loop hem van
boven naar beneden door, het liefst op een testserver met `/kmcbot add 8` (fake spelers) en `/kmctest <gameId>`.

**Afvinken:** `[V]` = werkt, `[X]` = werkt niet (zet er kort bij wat je ziet), `[?]` = twijfel of niet kunnen testen.
Bij een `[X]` of `[?]` zoek ik uit wat er misgaat; een regel mag een opmerking hebben achter het vinkje.

| Blok | Wat | Tijd |
|---|---|---|
| 0 | Bouwen, deployen, opstarten | 15 min |
| 1 | Beheer: validatie, checklist, teams, menu's, bots | 30 min |
| 2 | Host, lore, teamshowcase, podium, awards | 40 min |
| 3 | Toernooiverloop van start tot einde | 45 min |
| 4 | Alle games (27), een voor een | 4-6 uur |
| 5 | Lobby, data, platform, stream | 30 min |
| 6 | Randgevallen: pauze, stoppen, uitval, veel spelers | 30 min |

> Een **ronde** is `automation.games-per-round` games (standaard 1). "1/8 na één game" is dus geen fout.

---

## 0. Bouwen, deployen, opstarten

- [ ] `mvn test` → alle tests groen.
- [ ] `.\deploy.ps1 -Plugins "<server>\plugins"` → alleen jars zonder `original-`; `kmc-storage` zit er **niet** bij.
- [ ] 32 jars: KMCCore, kmc-core (KMC Core V2), kmc-game-api, kmc-stats, kmc-tournament-engine en alle games (LuckyBlock en TheBridge zijn weg).
- [ ] WorldEdit staat in `plugins/` (nodig voor Mob Mayhem, Speed Build, schematics).
- [ ] Server herstarten (niet `/reload`) → console zonder rode regels, elke plugin meldt "enabled".
- [ ] Heb je een eigen `plugins/KMCCore/host.yml`: het oude `kmc-lore`-blok verwijderd (of het bestand) en `/kmchost reload` gedaan.
- [ ] `plugins/KMCCore/config.yml` bevat `automation.games-per-round: 1` (een oude server houdt zijn oude waarde).
- [ ] Console toont `[Cinematic]`/`[LiveApi]`-regels zonder fouten.

## 1. Beheer

### 1.1 Validatie en checklist
- [ ] `/kmcvalidate` toont per game "geconfigureerd" of wat er mist.
- [ ] `/kmcvalidate config` toont per bestand "OK" of afwijkingen; `arena.*` / `course.*` / `director` zijn **geen** "onbekende sleutel" meer; een bewuste typefout (`prestart-secnds`) → "bedoelde je 'prestart-seconds'?".
- [ ] `/kmcchecklist` opent het menu met de standaard-voorbereidingen; klik vinkt af en het blijft bewaard na een herstart.
- [ ] `/kmcchecklist add <tekst>`, `remove <nr>`, `toggle <nr>`, `reset`, `defaults`, `list` werken.
- [ ] Config-doctor/validatie in het Validation Center (`/kmcauto` → Event-checklist).

### 1.2 Teams en spelers
- [ ] `/kmcteam` aanmaken, spelers toevoegen/verwijderen, teamgrootte (`teams.max-players-per-team`) wordt gehandhaafd.
- [ ] `/kmcteam add <naam> <team>` voor iemand die nog nooit online was: Mojang-opzoeking, echte UUID.
- [ ] `/kmcrandomteams` verdeelt iedereen eerlijk; `/teamprofile` toont alle 8 teams.
- [ ] Teamkleuren, armor en emblemen (`/kmcemblem`) kloppen overal (tablist, chat, sidebar).
- [ ] `/kmcbot add 8`, `/kmcbot info`: `invulnerable=false`; staat er `true`, plak de hele regel (hij noemt de "load"-leden van de server). `/kmcbot remove` ruimt op.

### 1.3 Menu's
- [ ] `/kmcauto` → setup-menu: aantal games, rondes, pauzes, finale, ready-check, verrassingsgames (0-3), per game aan/uit.
- [ ] Presets: er zijn geen standaard-presets; "Maak een preset" slaat je instellingen op, kiezen past ze toe, `/kmcauto preset delete <naam>` verwijdert.
- [ ] `/kmcdirector`: pauze, rerun, finale, dry-run, tijdschatting.
- [ ] Taalmenu bij de eerste join; na een keuze sluit het menu meteen; `/kmclanguage` werkt.
- [ ] Tablist (MCC-stijl, teamnamen gecentreerd en vet, `➤` voor je team), sidebar "Top Teams", `/kmchud test` (15 s).

## 2. Host, lore, teamshowcase, podium, awards

### 2.1 De host zelf
- [ ] `/kmchost setspawn` plaatst hem; hij praat **niet** voor `/kmcauto start`; `/kmchost info` toont `IDLE`.
- [ ] Rechtsklik vóór de start: menu met alleen "Wat is KMC?", "Hoe werkt het toernooi?" en "Lore"; tekst alleen voor jou.
- [ ] `/kmchost test` toont een paar regels boven zijn hoofd (alleen voor jou); lange zinnen splitsen aan het einde van een zin, nooit midden in een zin.
- [ ] Hij is onkwetsbaar: een vuist in creative, `/kill` en vallen doen niets; verdwijnt hij toch, dan staat hij binnen 10 s terug.
- [ ] Hij loopt niet van het platform of door muren en staat aan het eind weer op zijn plek.
- [ ] Bubbel: hoogte goed (`host.npc.dialogue-height`), leesbaar op afstand, met 32 spelers geen lag.
- [ ] Skin: `/kmchost skin <naam>` en de rank-NPC's dragen de skin van de speler op die plek (1e, 5e, ...), ook offline spelers.

### 2.2 Alles met de host testen (zonder toernooi)
Elk commando speelt dat onderdeel met testdata waar echte data ontbreekt; `/kmchost skip` stopt hem.
- [ ] `/kmchost lore` (verhaal: neergestort op een eiland, stoelen = teamgrootte, camera + lopende host + hartslag).
- [ ] `/kmchost opening`, `rules`, `overview`, `teams` (met team-intro), `lineup`.
- [ ] `/kmchost results`, `standings`.
- [ ] `/kmchost finale`, `third`, `second`, `champion`, `awards` (de host staat naast het podium als dat er is).
- [ ] `/kmchost podium` zet hem een moment op het podium en weer terug.
- [ ] `/kmchost intro <game>` toont de volledige introductie (lore, regels, besturing, punten, speelduur) van een game.
- [ ] `/kmchost dialogue <id>`, `announce <tekst>`, `enable`/`disable`, `reload` (meldt waarschuwingen in host.yml).

### 2.3 Lore en opening (`/kmcauto start`)
- [ ] De lore speelt eerst: titel NEERGESTORT, fly-over, lopende en springende host, hartslag, bliksem bij "HET TOERNOOI BEGINT!".
- [ ] Zet de teamgrootte op 3: "drie stoelen"; een regel met `{editions}`/`{last_champion}` valt weg zonder edities.
- [ ] Daarna direct de teamshowcase, dan opening, spelregels, rondes & punten en de game-lineup (kaarten zonder ondertitel, juiste aantallen).
- [ ] Taalbericht na de lore: nette kaart, twee klikbare regels, geen actionbar eroverheen.
- [ ] `/kmcauto skip` slaat lore/opening over en gaat naar de tussenpauze; `/kmcauto stop` ruimt alles op (geen camera die blijft hangen).
- [ ] Een tweede `/kmcauto start` tijdens de sessie doet niets extra.
- [ ] De afteltijd naar het stemmen begint pas als de host is uitgesproken.

### 2.4 Teamshowcase en podium
- [ ] `/kmcpodium set` bouwt goud/ijzer/koper; `/kmcpodium test` zet je op plek 1.
- [ ] Showcase: flyover over het podium, daarna elk team samen bovenop de gouden kolom (kijkt naar de camera), de host staat in het midden erachter, teams worden niet dubbel genoemd.
- [ ] Teams > 9 spelers vallen terug op een rij; zonder podium werkt de oude opstelling.
- [ ] Na de showcase staat iedereen weer waar hij stond.

### 2.5 Uitslag en awards
- [ ] Finale-aankondiging komt ná de leaderboard-animatie; 3e, 2e en kampioen staan echt op het podium; de rest kijkt via een camera.
- [ ] Slotceremonie toont de echte winnaar, MVP en records.
- [ ] Awards: dezelfde podium-camera, vuurwerk boven het podium, host in beeld, echte data.
- [ ] Fan Favorite-GUI past voor 32+ spelers; credits, feedback en reset lopen netjes door.
- [ ] Na het einde staat de host weer op zijn eigen plek (`/kmchost info`).

## 3. Toernooiverloop

- [ ] Start → (lore) → showcase → opening → eerste tussenpauze → stemmen (`/kmcvote`) → pre-start → game.
- [ ] Verrassingsgames: aantal in het menu, willekeurig gekozen, `???` bij het stemmen en in de lineup, onthuld bij de beurt; de uitleg verschijnt na de onthulling.
- [ ] Pre-start: host noemt game, doel en sluit af; de uitlegkaart in de chat toont doel, "zo werkt het" en punten (ook Laser Logic en Factory Frenzy).
- [ ] Na een game: score-uitsplitsing → GG-knop → leaderboard-animatie → pas dan "Halverwege" en "Tussenpauze" met timer.
- [ ] "Jouw game": `Je werd #X/32 | je team werd #Y/8` met echte aantallen; tab-punten = sidebar-punten = uitsplitsing (inclusief multiplier).
- [ ] Multipliers per ronde kloppen; Golden Hour verschijnt maar één keer en pas bij de eerste echte game van die ronde.
- [ ] Halverwege-pauze, ready-check (`/kmcready`), pauze (`/pause`, `/resume`, `/pause now`), rerun, dry-run zonder punten.
- [ ] Finale (Dodgebolt, top 2, best-of-3), daarna het eindscherm.
- [ ] Een uitgeschakelde of overgeslagen game wordt netjes vervangen (`pickNextReadyGame`).
- [ ] `/event simulate 3 16` draait de echte pipeline: teams én persoonlijke punten, daarna reset (alleen op een testserver!).
- [ ] Team-bonuspunten (TGTTOS, Formula, Adventure Escape) gaan naar het team en staan niet bij de persoonlijke punten.
- [ ] Achievements: tab per categorie (L), toasts, vanilla-advancements verborgen, `/kmcachievements`.
- [ ] Scoreboard/ledger: `ledger.csv` klopt met de tablist; `/kmcseason` en edities in het archief.

## 4. Games

Per game: `/kmctest <gameId>` (testarena + config in één keer) of de eigen setup, dan `/<cmd> start [dry]`. Voor alle games:
sidebar in dezelfde stijl (game-regels, dan "Jouw Team" met teampunten en jouw punten), uitleg-kaart voor de start,
plek bij uitschakeling `(36)`, `(35)`, … waar je kunt afvallen, `/pause` stopt de klok, `/<cmd> stop` ruimt alles op.

### Block Party
- [ ] Setup: `/blockparty pos1/pos2/spectator/voidy/status` → "klaar ✓"; niet-teamspelers worden niet meegenomen.
- [ ] Zelfde kleur nooit twee rondes achter elkaar; de doelkleur heeft altijd genoeg blokken; ronde 1 gebruikt alleen geel/wit/grijs/zwart (of `presetfloor`).
- [ ] Doelkleur via titel + bossbar + actionbar + chat; kleurblok in slot 5 en vast; "VERDWIJN!" op 0.
- [ ] Vanaf ronde 5 chaos-events (Mirror, Double, Fake, Colour-Blind, Rapid-Fire); "⚡ CLUTCH SAVE" in de laatste seconde.
- [ ] Niemand valt door de void in countdown/grace; vloer geverfd bij de landing en na de game gereset.
- [ ] Uitschakeling toont de plek in de chat; punten 1e ≈ 250 + 10 per ronde en de uitlegkaart noemt hetzelfde.

### TGTTOS
- [ ] Setup via `/kmcsetup`, minstens 1 map; start met ≥4 spelers → "5 maps".
- [ ] Iedereen start naast elkaar op de eerste map (ook bij meer spelers dan startpunten). ↻ Wat bedoelde je met "met alle spelers tegelijk"? Noteer wat je ziet.
- [ ] Eerste finishers krijgen medailles; KMC-punten pas aan het einde; 50% klaar → aftelling van 2 minuten; DNF zonder punten.
- [ ] Fog of War (15%), Speed II voor de voorlaatste, bossbar met de leider, actionbar "KOPLOPER" of "-Xm".
- [ ] Einduitslag top-5, team-finishbonus naar het team, terug naar de lobby.

### TNT Tag
- [ ] Setup via `/kmcsetup`; bom doorgeven door te raken; nieuwe game start met volle border.
- [ ] Chaos-events: Low Gravity, Speed Frenzy, Darkness, Tracker Failure, Teleport Madness, Shrinking Arena; **Double Trouble geeft meteen minstens 2 extra bommen**.
- [ ] Powerups; de **decoy** rent binnen een vierkant van 3×3 rond en laat een klap verloren gaan.
- [ ] Final Showdown (2 spelers) neemt het krimpen over; uitschakeling toont de plek `(n)`.

### Survival Games
- [ ] Kisten vol loot, supply drops (lichtstraal + kist), "🍗 THE FEAST" één keer; kisten openen pas als het spel begint.
- [ ] Deathmatch aan het eind; assist-punten; uitschakeling toont de plek `(n)`.

### SkyWars
- [ ] Eilanden per team, kisten pas open na de start, void-kills; assist-punten; deathmatch; plek bij uitschakeling.

### Spleef
- [ ] Vloer breken geeft punten, vallen = eruit met plek `(n)`; laatste overlevende wint; arena wordt hersteld.

### QuakeCraft
- [ ] Railgun, powerups, hologram-decoy (de railgun pakt hem, geen kill), respawn met bescherming, automatische overtime bij gelijkspel.

### Bingo
- [ ] Kaart met opdrachten; rij/kolom/volle kaart geeft punten; tegenstanders zien jouw voortgang niet; einde na de tijd.

### Speed Build
- [ ] 10 `.schem` in `plugins/KMCCore/schematics/`; elke speler eigen slot met blauwdruk; GROEN = klaar (toont %), ROOD = opnieuw, GOUD = einde.
- [ ] Zelfde bouwsel geeft hetzelfde %; buiten je slot bouwen kan niet; eindoverzicht klopt, punten bij speler én team.

### Hole in the Wall
- [ ] `/kmctest hole_in_the_wall`: muren met gaten, vallen = eruit, plaatspunten per ronde; legenda met Rood=West, Blauw=Zuid, Geel=Oost, Groen=Noord.
- [ ] `/hitw testwall`, events (tempo), wanden vanuit de goede kant.

### Dodgebolt (finale)
- [ ] Alleen als finale (top 2), best-of-3; boog en pijlen per platform; trekken aan de boog **voor** de ronde kost geen pijl; pijl = eruit, val = eruit.
- [ ] Pijlen die blijven liggen kunnen weer opgepakt worden; team-eliminatie beslist de ronde.

### Elytra Endrium
- [ ] Parcours met ringen, boost, finish-cam en baanrecord (`/kmctrack`); finishers krijgen `#n`; `/kmctest elytra_endrium`.
- [ ] Grace-periode na de eerste finisher; val = respawn bij checkpoint.

### Parkour Warrior
- [ ] Stages en checkpoints in volgorde, skip-regels, finish toont `#n`, finish-cam en baanrecord; `/kmctest parkour_warrior`.

### Adventure Escape
- [ ] Race over meerdere ronden (laps), checkpoints, finishers `#n`, team-bonus naar het team, finish-cam.

### Mob Mayhem
- [ ] Setup: `/mm status` → template-wereld ✔, spawn ✔, ≥4 mob-spawns; `/mm pos1/pos2` slaat hele blokken op en weigert buiten de template-wereld.
- [ ] Je staat in een **gekloonde** wereld `mm_game_<team>_<random>`; `/mm stop` ruimt hem op, geen `mm_game_*` map over.
- [ ] Eerste 3 waves zijn voor elk team gelijk (geen modifiers, geen baby-zombies); er lopen geen oude mobs rond bij de start.
- [ ] Meerdere waves achter elkaar; dood → spectator: je vliegt, raakt niets en kunt niet meer slaan. ↻ vorige keer werkte dit niet.
- [ ] Powerups (`/mm addpowerupspawn`), achievements (wave 10, untouchable, boss_slayer), boss-wave.

### Formula MC
- [ ] `/kmctest formula_mc`: kwalificatie → pole → race; ronde-detectie, checkpoints, snelste-ronde-bonus; vastzittende boot wordt teruggezet.
- [ ] Klok met honderdsten (bossbar in de race, actiebalk in qualifying); meerdere qualifying-rondes, de snelste telt, resterende tijd wordt gemeld.
- [ ] ↻ Je glitcht niet meer door/tegen andere boten (ook niet tegen stilstaande boten van bots); hou je dat nog, maak dan een video.
- [ ] **Een stap van 1 blok omhoog lukt met je boot, ook op ijs.**
- [ ] Boost pad en item box geven echt snelheid; stun en schild werken; `/fmc weather rain`; `/kmcspec` na de finish; punten Ace Race-stijl; baanrecord via `/kmctrack`.

### Rising Inferno
- [ ] `/kmctest rising_inferno`: de lava is nu een magma-oppervlak (naast de deeltjes), stijgt mee en doet schade op het juiste niveau; ↻ vorige keer alleen deeltjes zichtbaar.
- [ ] Bonus-orbs, finish, achievements `inferno.yml`, "alle bonussen"-event.

### Rocket Rush (`/rocket`)
- [ ] `/kmctest rocket_rush` meldt "klaar om te starten"; checkpoints in volgorde, ring één keer, pickup, vallen → checkpoint, finish 100/85/70.
- [ ] Teams naast elkaar bij de start, kijken naar het midden; sidebar; `/pause` en `stop`.

### Knockout Kings (`/knockout`)
- [ ] Bots slaan met de stick (`/kmcbot info` → `invulnerable=false`) ↻ eerder onkwetsbaar; teamgenoten niet.
- [ ] Knockback voelt goed, val = eliminatie voor de laatste aanvaller, farm-limiet, 3 modifiers.

### Crafting Countdown (`/craftcountdown`)
- [ ] `/kmctest crafting_countdown`: 8 inleverpunten (vaten) met een crafting table ernaast, 12 **bergen** van blokken (6 soorten) op een ring.
- [ ] Geen portable crafting table en geen menu: je mijnt de blokken van een berg met je houweel of bijl en elk blok geeft het item waar het voor staat; een blok dat weg is komt niet terug.
- [ ] Craft bij de crafting table bij je inleverpunt; rechtsklik op het inleverpunt van je team levert de bestelde gecraftte items in; een ander team zijn punt → melding.
- [ ] Bestelling compleet → opsplitsing (basis + items + snelheid + reeks), nieuwe bestelling, moeilijker na 3; reeks-bonus; laatste 30 s "LAATSTE FASE".
- [ ] Draaglimiet, geen onmogelijke bestellingen, `/craftcountdown validate`; na het spel zijn alle bergen weer weg (geen blokken of labels over).

### Zone Zero (`/zonezero`)
- [ ] Twee teams in een zone = contested, zones wisselen, geen dubbele hold-punten, alleen wie in de zone staat krijgt de punten.
- [ ] Bots slaan werkt, teamgenoten niet.

### Crown Control (`/crown`)
- [ ] 3 kronen: opzetten (gouden helm, gloeit), kroondrager scoort elke 10 s, team verdient energie.
- [ ] Vijand pakt de kroon door 3× te slaan of uit te schakelen (kill → direct naar de killer); teamgenoten kunnen niet; helm uitdoen kan niet; vaardigheden (nether star) alleen met een kroon.

### Core Charge (`/corecharge`)
- [ ] Gekleurde pilaar + balk met percentage boven je kern; boven elk station zweeft hoe lang er nog opgeladen moet worden (ook actiebalk).
- [ ] Vaardigheden met cooldown, Final Overload alleen in de laatste 30 s, eindbonus.
- [ ] ↻ PvP: bots slaan werkt (eerder onkwetsbaar); `stop` ruimt kern-displays op.

### Laser Logic (`/laser`)
- [ ] `/kmctest laser_logic`: 8 borden op een rij, bij de start een bord per team; spiegel draaien (geluid + deeltjes), straal volgt meteen, ontvangers lichten op.
- [ ] **Elke spiegel heeft per puzzel een eigen kleur**; hint 2 noemt "de rode spiegel op (x, y)"; hint 1 de ontvanger, hint 3 een stuk van de oplossing; "geen hints"-bonus verdwijnt na een hint.
- [ ] Het is een stuk moeilijker (puzzels een niveau hoger, meer bochten, afleidende muren en absorbers, de helft van de spiegels staat verkeerd).
- [ ] Opgelost bord geeft punten + eerste-bonus, daarna een volgende (moeilijkere); een niet-opgeloste puzzel verloopt op tijd.
- [ ] `/laser validate`, `puzzle list/show`; een onoplosbare eigen puzzel wordt afgekeurd.

### Blockball (`/blockball`) — Rocket League-stijl
- [ ] `/kmctest blockball`: 4 velden met doelen; wedstrijden van 4 tegen 4, heats/waves bij minder velden, "bye" bij oneven teams.
- [ ] **Eerst tot 3 doelpunten**; geen winnaar na de tijd (5 min): meeste goals, bij gelijkspel golden goal.
- [ ] Grote, levendige bal die op de grond en tegen de muren stuitert en doorrolt; kijk je omhoog bij het schoppen dan gaat hij de lucht in, kijk je omlaag dan blijft hij laag.
- [ ] Doelpunt: titel, explosie, geluid, bal terug op de middenstip; niemand kan schoppen tijdens de reset.
- [ ] Schop, geladen schot (sneak), pass, dribbelen; assist-venster; punten voor doelpunt/assist/save.
- [ ] **Keepers kiezen ipv random:** voor elke wedstrijd opent een menu met je teamgenoten; de meeste stemmen wint (gelijk of niemand = automatisch, de rotatie telt mee). De keeper krijgt de titel en vangt/gooit/rolt/slaat weg in zijn gebied.

### Factory Frenzy (`/factory`) — nu een Overcooked-keuken
- [ ] `/kmctest factory_frenzy`: 8 keukens met kratten (tomaat, ui, sla, gehakt, broodjes), snijplanken, twee potten, een pan, vier aanrechten, bordenrek, wasbak, serveerluik en prullenbak; `/factory validate` zegt dat elk gerecht te maken is.
- [ ] Je draagt maar één ding: het zit in je eerste hotbar-slot, je kunt niets droppen of verplaatsen.
- [ ] Snijden: ingrediënt op een snijplank, 5× klikken met een lege hand (balk telt mee), dan oppakken.
- [ ] Koken: 3 gesneden tomaten of uien in een pot → soep na 14 s; gehakt in de pan → burgerpatty na 9 s. Laat je het 10 s staan dan verbrandt het (rook, "VERBRAND!") en moet je de pot legen.
- [ ] Soep haal je uit de pot met een bord; salade (tomaat + sla) en burger (broodje + patty + sla) stel je samen op een bord op een aanrecht; een fout onderdeel wordt geweigerd.
- [ ] Serveren bij het gouden blok: alleen een gerecht dat besteld is; punten bij wie meewerkte; tip voor snel serveren, combo voor gerechten achter elkaar; een bestelling die verloopt breekt de combo.
- [ ] Het bord komt na 8 s vies terug bij de wasbak; 4× klikken met een lege hand maakt het schoon en het ligt weer in het bordenrek; zonder schone borden loopt het vast.
- [ ] Prullenbak gooit alles weg (een bord komt vies terug); een andere keuken aanklikken → melding; `/pause` stopt kooktijden en bestellingen.
- [ ] Na het spel zijn alle stations, labels en borden weg en je inventory is leeg.

### Dungeon Draft (`/dungeon`)
- [ ] `/kmctest dungeon_draft`: 8 banen met negen kamers; draft-menu met drie opties, stemmen, timeout-fallback.
- [ ] **Gevecht: er spawnen mobs** (de wereld mag niet op peaceful blijven; het spel zet hem op normal en herstelt hem) en de kamer is klaar als alle golven dood zijn; ↻ eerder geen mobs.
- [ ] Parkour: vallen kost teamgezondheid en zet je terug bij het checkpoint; finish voor `required` spelers = klaar. ↻ werkte eerder niet: noteer wat je ziet.
- [ ] Puzzel, val, winkel (genees/extra leven), baas met telegraaf-slagen, modifiers; einde met uitslag per team en alles opgeruimd.

## 5. Lobby, data, platform, stream

- [ ] Lobby: `/ttt`, `/darts` (Target-blok), vissen, parkour, `/lobbyspleef` geven geen toernooipunten; anti-grief (frames, emmers, kisten, ender pearls).
- [ ] Rank-NPC's (`/kmclobbynpc rank set 1…`, `list`, `rank remove here`, `despawnall`) met de skin en punten van de speler op die plek, live bijgewerkt.
- [ ] Hall of Fame-NPC (`/kmclobbynpc spawn hof`): top 3, ranks 4-10, team-banners, "Records aller tijden".
- [ ] Easter eggs ("Easter Egg Hunter" staat als advancement), `/tutorial`.
- [ ] Spectator: `/kmcspec` (menu, follow, stop); een gestorven speler vliegt en kan niets raken.
- [ ] Statistieken: `/kmcstats`, `/kmcprofile`, `/kmclb`, `/kmcmvp`, `/kmcmomentum`, `/kmcpowerrank`, `/kmchof`, `/kmcmedals`.
- [ ] `/kmcapi start` toont de URL en de overlay (`/overlay`, `/api/state`) werkt; ↻ eerder werkte dit niet: noteer de melding (op een server elders: `live-api.bind: 0.0.0.0` + token).
- [ ] `/clip` (clip-markers), `/kmcaudit` (teleports), `/kmccamera` (routes opnemen), `/gg`.
- [ ] Story/lore-klikverhaaltjes via de host (herkomst, kampioenschap, trofee, teams, rivaliteiten, seizoenen, finales).

## 6. Randgevallen

- [ ] Speler disconnect en rejoin tijdens een game, in de lobby en tijdens de ceremonie.
- [ ] `/pause` midden in elke game: klok, potion-effecten, mobs en timers; `/resume` zet alles weer in gang.
- [ ] `/kmcauto stop` en `/kmcgame forceskip` tijdens de lore, de showcase, een game en het eindscherm: geen blijvende entities, camera's, effecten, bossbars of spectators.
- [ ] Zware server: 32 spelers + bots, geen lag in tablist, sidebar, host en camera (haperen? noteer bij welk shot).
- [ ] Reload/herstart midden in een sessie: de host komt terug als IDLE, niets praat uit zichzelf.
- [ ] Te weinig spelers of teams voor een game: de game wordt netjes overgeslagen.
- [ ] Rerun/snapshot na een crash: punten worden niet dubbel geboekt.

## Nog niet gebouwd (geen fout, niet melden)

- Opnieuw joinen na een disconnect in de arcade-games.
- Achievements voor de arcade-games en de vier nieuwe games.
- Potion-effecten lopen door tijdens `/pause`.
- Assist-punten alleen in SkyWars; race-gap-indicator alleen in TGTTOS.
- Titels in games zijn niet aan de GUI-schaal aangepast.
- Dungeon Draft: eigen mob-AI voor de baas. Factory Frenzy: obstakels per level (draaiend platform, brand) en meerdere keuken-indelingen.
- Vote-modifiers (veto, boost, spoiler, lock), custom spectator-modus, gesproken host.

## Een fout melden

Eén regel per fout, dan los ik alles in één ronde op:

```
Blok/regel:  4 / Crown Control / kroon pakken
Verwacht:    na 3 klappen is de kroon van mij
Gebeurde:    niets, de teller blijft op 1/3
Console:     (plak de regel of "geen")
```

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

> Een **ronde** is `automation.games-per-round` games (standaard 1 in `config.yml`). "1/8 na één game" is dus geen fout.

---

## N. Nieuw na de vorige testronde

Deploy eerst **alle** jars opnieuw (`.\deploy.ps1 -Plugins "<server>\plugins"`): KMCCore, kmc-game-api en kmc-stats
zijn aangepast, en alle arcade-games, Formula MC, SkyWars en nog een paar.

---

### N8. Crafting Countdown — grondstoffen halen, craften, inleveren
Eerst: het oude `plugins/CraftingCountdown/config.yml` krijgt bij het opstarten de nieuwe secties (stapel-typen, recepten, orders); controleer de console op "gather-and-craft". Je arena-gegevens blijven staan, maar stapels van een oud type (`stone`, `nether`) zet je opnieuw. Bouw daarna `/kmctest crafting_countdown`.
- [ ] `/craftcountdown validate` na de test-arena → "✔ Configuratie is geldig"; haal je alle stapels weg (`pile clear`) → melding "geen stapels".
- [ ] Arena: 8 inleverpunten (vaten) op de rand, 12 stapels (6 soorten, elk twee keer) in de binnenring, elk met een zwevend label met de voorraad.
- [ ] Start: titel "VERZAMEL EN CRAFT!", je hebt een **Werkbank**-item in slot 9; elk team ziet zijn bestelling (te craften items) in chat met een regel "Grondstoffen: …", op het bord en in de sidebar.
- [ ] Werkbank: rechtsklik op het item opent een crafting table; craft bijvoorbeeld fakkels of een kist uit de grondstoffen van de stapels. Het item kun je niet plaatsen of droppen; raak je het kwijt, dan krijg je het binnen 10 s terug.
- [ ] Rechtsklik op een stapel → menu met de items, voorraad en "je draagt x/160"; een klik kiest wat je mijnt, daarna geeft elk kapotgeslagen blok van die stapel `per-take` items (zeldzame stapels minder). Je hebt een houweel en bijl in je hotbar.
- [ ] Cooldown: twee keer snel klikken → "nog Xs wachten"; leeg item → melding; boven het maximum dragen → "je draagt al het maximum".
- [ ] De stapel vult zich aan elke `piles.restock-seconds` aan (label verandert).
- [ ] Rechtsklik op het inleverpunt van je eigen team → de bestelde **gecraftte** items verdwijnen uit je inventory, bord en sidebar tellen mee voor het hele team; grondstoffen worden niet aangenomen; klik op het inleverpunt van een ander team → melding van wie het is.
- [ ] Bestelling compleet → opsplitsing in chat (basis + items + snelheid + reeks = totaal), titel `✔ #n +punten`, meteen een nieuwe bestelling; de punten gaan naar wie heeft ingeleverd, naar rato van het aantal items.
- [ ] Na 3 bestellingen worden ze groter/zeldzamer (3 regels, tier 2; daarna 4 regels en tier 3).
- [ ] Snel achter elkaar voltooide bestellingen geven een reeks-bonus (+5 per stap, max +25).
- [ ] De laatste 30 s: titel "LAATSTE FASE", bossbar rood, piepjes de laatste 10 s.
- [ ] Einde: uitslag per team met bestellingen, items, snelheids- en reeks-punten; opruimen laat geen labels of borden achter; `/craftcountdown stop` midden in het spel ook niet.
- [ ] `/pause` stopt de klok en de herstock; opnieuw spelen (rerun) geeft geen dubbele punten.
- [ ] Geen onmogelijke bestellingen: haal het type `rare` weg → geen enchanting tables, diamantpikhouwelen of gouden appels meer in bestellingen; haal `mine` weg → geen fakkels (kolen) of emmers (ijzer).

---

### N9. Vier nieuwe games — algemeen (Laser Logic, Blockball, Factory Frenzy, Dungeon Draft)
Eerst: vier **nieuwe jars** in `plugins/` (`LaserLogic`, `Blockball`, `FactoryFrenzy`, `DungeonDraft`) plus de vernieuwde `KMCCore`, `kmc-game-api` en `kmc-stats`. De games komen vanzelf in de game-lijst van KMCCore (`games.list` in `config.yml` is aangevuld).
- [ ] `/kmctest list` toont alle vier; `/kmctest laser_logic`, `blockball`, `factory_frenzy` en `dungeon_draft` geven elk "✔ … is klaar om te starten".
- [ ] `/<cmd> validate` (`/laser`, `/blockball`, `/factory`, `/dungeon`) geeft na de testarena "geldig"; haal je een baan/bord/veld weg, dan staat er wat er mist.
- [ ] Elke game: intro-kaart met doel + puntenverdeling, flyover, tutorial, aftelling; `/pause` stopt de klok; `/<cmd> stop` midden in het spel ruimt alles op (geen blokken, mobs, borden of bossbars blijven staan).
- [ ] Een tweede run (rerun) geeft geen dubbele punten; `/<cmd> start dry` boekt geen punten.

### N10. Laser Logic — spiegels draaien tot de straal de ontvangers raakt
- [ ] `/kmctest laser_logic`: 8 borden (vloer + emitter, spiegels, ontvangers) op een rij; bij start staat bij elk team een bord.
- [ ] Rechtsklik op een spiegel draait hem (geluid + deeltjes); de straal volgt meteen (deeltjesbaan, ontvangers lichten op bij een raak).
- [ ] Het papier in slot 9: eerste hint toont een fout bord-deel, tweede en derde zijn duidelijker; "geen hints"-bonus verdwijnt na een hint.
- [ ] Een opgelost bord geeft punten + "eerste-bonus" voor het snelste team; daarna komt de volgende puzzel (moeilijker); een puzzel die niet af is verloopt op tijd en het team gaat door.
- [ ] `/laser puzzle list` en `/laser puzzle show <id>` werken; een eigen puzzel in `config.yml` die onoplosbaar is wordt bij `validate` afgekeurd.

### N11. Blockball — voetbal met een blokbal
- [ ] `/kmctest blockball`: 4 velden met doelen, middenstip en spawns; teams worden in wedstrijden van 4 tegen 4 gezet (heats/waves bij minder velden dan wedstrijden, "bye" bij oneven aantal teams).
- [ ] Linksklik schopt de bal, sneak + linksklik (vasthouden) geeft een geladen schot, rechtsklik is een pass; de bal rolt, stuitert tegen muren en blijft binnen het veld.
- [ ] Een doelpunt: titel, geluid, bal terug op de middenstip na `goal-reset-seconds`, niemand kan in die tijd schoppen.
- [ ] Gelijkspel gaat naar golden goal (overtime); einde wedstrijd: winst/gelijk/verlies-punten + per doelpunt en assist; teams die niet spelen wachten op het wachtpunt.
- [ ] ⚠ Voelt de bal goed (snelheid, sleeën tegen een muur)? Noteer wat te snel/te traag is; waarden staan in `config.yml` onder `ball:`.

### N11b. Blockball — keepers
- [ ] Bij de start van een wedstrijd: chat "Keeper van <team>: <naam>" voor beide teams; de keeper krijgt de titel "Jij bent de keeper". Na de wedstrijd krijgt iemand anders de keeper-rol bij het volgende duel (rotatie).
- [ ] Buiten het eigen doelgebied (6 blokken rond het doel) gedraagt de keeper zich als veldspeler; erbinnen schopt hij verder (+1,4 bereik).
- [ ] Rechtsklik in het gebied bij een langzame bal → "Gevangen!"; de bal volgt zijn handen; linksklik gooit, rechtsklik rolt; na 5 s of bij het verlaten van het gebied laat hij los.
- [ ] Een geladen schot (sneak + linksklik van een tegenstander) kan niet gevangen worden maar wel weggeslagen met linksklik ("punch").
- [ ] Een gestopt schot op zijn doel geeft de titel "REDDING!" en `points.save`; hetzelfde schot twee keer in dezelfde tick geeft niet dubbele punten.
- [ ] Een bal die vastgehouden wordt telt nooit als doelpunt, ook niet in het doel; tegenstanders kunnen hem niet pakken.
- [ ] ⚠ Voelt het eerlijk? Te sterk als een keeper alles kan vangen: verlaag `keeper.catch-max-speed` of `reach-bonus`.

### N12. Factory Frenzy — grondstoffen, machines, bestellingen
- [ ] `/kmctest factory_frenzy`: 8 fabrieken; bij start staan er stations (grondstoffen), machines (ovens/…), een bord en een goudblok om in te leveren.
- [ ] Rechtsklik op een station → grondstoffen in je inventory (cooldown, maximum, voorraad loopt leeg en vult zich aan).
- [ ] Rechtsklik op een machine → menu: "Alles invoegen", "Output ophalen", "Invoer terugnemen"; de machine werkt een tijd en meldt "klaar"; een machine met vol outputvak staat op "geblokkeerd".
- [ ] Rechtsklik op het goudblok levert producten in; een volledige bestelling geeft punten (gesplitst over wie heeft ingeleverd) + deadline-bonus; een deels afgeleverde bestelling blijft staan; de volgende bestelling is moeilijker.
- [ ] Een andere fabriek of ander team zijn machine aanklikken → "dit is de fabriek van …"; items van een vorige run (andere `runId`) werken niet.
- [ ] Einde: uitslag per team met bestellingen, efficiëntie; alle blokken en borden zijn weg.

### N13. Dungeon Draft — draft je route door de dungeon
- [ ] `/kmctest dungeon_draft`: 8 banen, per baan een wachtruimte en negen kamers op 14 blokken afstand; parkour-kamers hebben stenen met gaten.
- [ ] Start: draft-menu met drie opties (type, moeilijkheid, beloning, risico, stemmen); klik = stem (✔ bij jouw keuze); niemand kiest → na `draft-seconds` een willekeurige optie; iedereen gestemd → meteen door.
- [ ] Gevecht: golven mobs, de kamer is klaar als alle golven dood zijn; mobs laten geen drops achter.
- [ ] Parkour: vallen kost teamgezondheid en zet je terug bij het checkpoint; finish voor `required` spelers = klaar.
- [ ] Valkuil: gele vloer + rode deeltjes waarschuwen, daarna schade; druk beide goudblokken in = klaar. Schat: kisten (sommige bevallen) geven munten. Puzzel: lichtjes tonen de volgorde, fout = opnieuw + schade.
- [ ] Teamgezondheid op = "extra leven" (één keer) en daarna "RUN MISLUKT"; de baas heeft telegraaf-slagen (rode cirkel) en roept hulp op bij 50%.
- [ ] Draft-menu: genees het team of koop een extra leven met munten; de laatste kamer is altijd de baas.
- [ ] Einde: uitslag per team (kamers, baas, kisten, gezondheid, tijd); alle mobs, bossbars en markeerblokken zijn weg.
- [ ] Eigen modus testen: `health.mode: INDIVIDUAL` en `dungeon.vote-mode: LEADER` in `config.yml`, `/dungeon reload`.

---

### N14. KMC Host — presentator met tekst boven zijn hoofd
Eerst: nieuwe jars `KMCCore`, `kmc-core` (nieuwe objective-types) en `kmc-game-api`. Bij het opstarten schrijft KMCCore `plugins/KMCCore/host.yml`; controleer de console op "host.yml:"-waarschuwingen (onbekende placeholders). Zet de host neer met `/kmchost setspawn`.
- [ ] **Stil vóór de start**: server herstarten, joinen, `/kmchost setspawn` → de host staat er, praat niet en geen enkele tekst verschijnt. `/kmchost info` toont `IDLE`.
- [?] Pre-start van een game: de host noemt de game, het doel en sluit af met een zin; wat hij zegt klopt met de game (doel/punten uit de game zelf). Duurt langer dan de countdown? Dan wordt de speech ingekort, niet afgebroken.
- [ ] `/kmchost intro blockball` (en de andere drie nieuwe games) → volledige introductie: lore, regels, besturing, puntentelling, speelduur. Klopt alles met hoe de game echt werkt?
- [ ] Tijdens een game: doelpunt (Blockball), puzzel (Laser Logic), bestelling (Factory Frenzy), kamer/baas (Dungeon Draft) geeft een korte opmerking; niet vaker dan elke ±8 s; spelers die ver van de host staan zien de tekst in hun action bar.
- [ ] `/pause` midden in een game: geen nieuwe live-opmerkingen tot `/resume`.
- [ ] Na een game: eerst jouw score-uitsplitsing/ledger klopt, dan zegt de host de winnaar (echte naam), beste speler en daarna na de leaderboard-animatie de tussenstand; wisseling aan de top levert "wissel aan de top" op, een kleine voorsprong "spannend", geen verzonnen verschillen.
- [ ] Finale: aankondiging met de echte finalisten; podium 3e → 2e → kampioen met de echte teamnamen en punten; daarna awards (alleen als er een winnaar is).
- [ ] Einde van de end-sequence: `/kmchost info` → `IDLE`; de host zegt niets meer tot de volgende `/kmcauto start`.
- [ ] `/kmcauto stop` midden in een speech: de bubble verdwijnt direct, er komt niets meer uit, ook niet later.
- [ ] `/kmchost skip` slaat de huidige speech over; `/kmchost announce hallo` laat hem iets zeggen (ook in IDLE); `/kmchost disable` → hij blijft stil, `enable` zet hem weer aan.
- [ ] `/kmchost reload` herlaadt host.yml; daarna geen dubbele bubbles of dubbele speeches; een kapotte regel in host.yml geeft een waarschuwing, geen crash.
- [ ] Cutscene-focus: bij de finale-aankondiging en het kampioen-moment zoomt de camera rustig in op de host terwijl hij praat en zet iedereen daarna terug (gamemode en plek goed). Nooit midden in een game, en niet als er al een route (`/kmcpresentation`) speelt.
- [ ] Eigen route: neem `host-champion` op met `/kmccamera`; bij de kampioen-onthulling speelt die eerst, daarna praat de host. Zonder route gebeurt er niets extra.

### N16. Rank-NPC's beheren via /kmclobbynpc
- [ ] `/kmclobbynpc rank set 1` (en 2, 3 ...) zet een rank-NPC neer; `/kmclobbynpc list` toont ze met de plek; tab-aanvulling werkt.
- [ ] Sta naast een rank-NPC en gebruik `/kmclobbynpc rank remove here` → hij (en zijn tekst) verdwijnt binnen 4 blokken; ook een NPC die het systeem niet meer kent verdwijnt.
- [ ] `/kmclobbynpc rank remove 3` en `... remove own` werken; `/kmclobbynpc rank clean` ruimt losgeraakte rank-NPC's op (bijvoorbeeld na een /reload).
- [ ] `/kmclobbynpc despawnall` haalt de lobby-NPC's én alle rank-NPC's weg; na een herstart komen ze niet terug.
- [ ] `/kmcnpc rank ...` werkt nog steeds (zelfde beheer), nu ook met `remove here` en `clean`.

### N15. Config-doctor
- [ ] `/kmcvalidate config` toont per bestand "OK" of de afwijkingen; maak in een game-config bewust een typefout (`prestart-secnds`) → melding met "bedoelde je 'prestart-seconds'?".
- [ ] Een negatieve duur, een kans boven 1 of `min` boven `max` wordt gemeld; een nieuw team onder `teams:` of een negende ronde onder `tournament.multipliers` geeft GEEN melding.
- [ ] Verwijder een game-jar uit `plugins/` en laat het id in `games.list` staan → "geen game-plugin geladen voor dit id".
- [ ] Maak een bestand ongeldige YAML (inspringfout) → fout "geen geldige YAML"; `/kmcauto start` wordt dan geblokkeerd. Andere afwijkingen blokkeren niet.
- [ ] `plugins/KMCCore/config-doctor.txt` bevat de volledige lijst; `/kmcvalidate` (GUI) toont het onderdeel "Configuratie".


### N17. Fixes van testronde 14 oktober
- [?] Game-kaarten in de lineup hebben geen ondertitel meer; de chat en de titel vallen niet meer over elkaar. De host-actiebalk komt alleen tijdens een game bij live-opmerkingen.
- [ ] Finale: de aankondiging komt ná de leaderboard-animatie en niet er middenin; de intermission start daarna.
- [ ] Slotceremonie toont de echte winnaar (geen "(winner)" meer).
- [ ] Nieuw toernooi (`/kmcauto start`): het KMC-boek van het vorige toernooi verdwijnt uit de inventory.
- [ ] `/event simulate`: ook jouw eigen punten (en die van bots) lopen mee, niet alleen de teampunten.
- [ ] `/kmcteam add <naam> <team>` voor iemand die nog nooit online was: Mojang-opzoeking, daarna zit hij in het team met zijn echte UUID.
- [ ] Teamshowcase: alle teamleden staan op een rij, kijken naar de camera en blijven staan tot hun beurt voorbij is.
- [ ] Podium: `/kmcpodium set` (kijk naar de toeschouwers) bouwt goud/ijzer/koper; bij de uitslag staan 3e, 2e en kampioen er echt op en kijkt de rest via een camera. De chat-podium is weg.
- [ ] Advancements: vanilla-advancements geven geen toast meer; per categorie een eigen tab met achtergrond en rarity-secties; `plugins/KMCCore/vanilla-advancements.yml` bevat de regels om de vanilla-tabs in spigot.yml weg te halen.
- [ ] Team-bonuspunten (TGTTOS, Formula MC, Adventure Escape) gaan naar het team en staan niet meer bij de persoonlijke punten.
- [ ] `/kmctest blockball`, `/kmctest rocket_rush` en `/kmctest block_party` melden meteen "klaar om te starten".
- [ ] Bots: `/kmcbot info` toont `clientLoaded=true` en `invulnerable=false`; bots kunnen geslagen worden en krijgen schade in alle zes de arcade-games.
- [ ] Na elke spawn kijken spelers naar het midden van de arena; na stop/einde zijn glow, vuur, snelheid, vlucht, titel en actiebalk weg.
- [ ] Arcade-sidebars: dun streepje bovenaan, game-regels, dan TEAM met teampunten en jouw punten (zelfde woorden in elke game).
- [ ] Laser Logic en Factory Frenzy: voor de start staat een kaart met doel, "zo werkt het" en punten in de chat.
- [ ] Core Charge: boven elk station zweeft hoe lang er nog opgeladen moet worden (balk + seconden); wie in de zone staat ziet het ook in de actiebalk.
- [ ] Crafting Countdown: je hebt een houweel, bijl en Werkbank in je hotbar; rechtsklik op een stapel kiest wat je mijnt, het blok kapotslaan geeft de grondstof.
- [ ] Formula MC: de klok loopt met honderdsten (bossbar tijdens de race, actiebalk in qualifying); je kunt meerdere qualifying-rondes rijden en de snelste telt; resterende tijd wordt gemeld.
- [ ] Formula MC: een boot waar je tegenaan rijdt verdwijnt kort voor je (geen botsing meer, ook niet tegen stilstaande boten).

### N18. KMC-lore: de host vertelt het verhaal bij de start
Voorbereiding: host geplaatst (`/kmchost setspawn`), jij staat op 5-15 blokken afstand; `/kmcauto start` met minstens 2 spelers.
**Eerst:** staat er een eigen `plugins/KMCCore/host.yml` op de server, verwijder daar dan het oude `kmc-lore`-blok (en de regels in `how-it-works` en `finale-champion` over de stoelen) of het hele bestand, daarna `/kmchost reload`. Anders hoor je de oude lore.
- [ ] Wacht tot de opening klaar is: pas daarna begint de tussenpauze ("Volgende game wordt gekozen over ...") en dus de afteltijd naar het stemmen. De host praat daar niet meer doorheen.
- [ ] `/kmcauto skip` tijdens de lore slaat hem over (camera stopt, host staat weer op zijn plek) en gaat naar de tussenpauze; `/kmcauto stop` ruimt alles op, geen camera blijft hangen.
- [ ] `/kmchost lore` speelt alleen de lore (zonder toernooi); `/kmchost skip` stopt hem. `host.lore-intro.enabled: false` slaat hem over; `host.scene.enabled: false` laat de host stilstaan.
- [Beetje] Muren of een laag plafond: de camera blijft vóór de muur en de host loopt niet door blokken.

### N19. Eén podium voor host, teamshowcase en awards
Voorbereiding: `/kmcpodium set` is gedaan (kijk naar de toeschouwers) en de host is geplaatst.
- [ ] Vanaf `/kmcauto start` staat de host naast het podium (links van de 2e plek, kijkt naar het publiek) en blijft daar voor de lore, opening, teamshowcase, finale en awards. Zonder podium blijft hij op zijn eigen plek.
- [ ] Teamshowcase: elk team staat samen bovenop de gouden kolom (rijen van max. 3, kijkt naar de camera), de camera kijkt vanaf het publiek; daarna weer terug. Teams > 9 spelers vallen terug op een rij.
- [ ] Uitslag en awards: dezelfde camera en hetzelfde podium; bij elke award een vuurwerk boven het podium en de host staat in beeld.
- [ ] Na `/kmcauto stop` of het einde van het eindscherm staat de host weer op zijn oorspronkelijke plek (`/kmchost info` toont `placed: true`).
- [ ] `host.podium.host-on-podium: false` laat de host op zijn eigen plek.

### N20. Testronde 15 oktober: fixes en nieuwe dingen
**Eerst:** verwijder het oude `kmc-lore`-blok uit je eigen `plugins/KMCCore/host.yml` (of het bestand) en `/kmchost reload`; deploy alle jars opnieuw (KMCCore, kmc-game-api en de aangepaste games).
- [ ] `/kmcbot add 4`, dan `/kmcbot info`: `invulnerable=false`; staat er nog `invulnerable=true`, plak dan de hele regel (hij noemt nu alle "load"-leden van de server).
- [ ] De host kun je niet meer doden, ook niet met een vuist in creative; verdwijnt hij toch, dan staat hij binnen 10 s terug.
- [ ] Bubbel: een zin wordt niet meer midden in doorgeknipt; past hij niet op de pagina, dan komt de hele zin op een nieuwe bubbel.
- [ ] De host loopt in de lore niet meer van het platform af (hij stopt voor een rand of muur).
- [ ] Camera (lore, showcase, podium) loopt vloeiender; blijft hij haperen, noteer waar (welke shot).
- [ ] Na de lore volgt direct de teamshowcase: eerst een flyover over het podium, dan elk team samen bovenop de gouden kolom, de host staat in het midden erachter. Daarna opening, spelregels, overzicht en game-lineup.
- [ ] Taalkeuze staat nu aan het begin van de eerste fase (na de lore).
- [ ] `/kmcauto start`-menu: geen standaard-presets meer; "Maak een preset" slaat je huidige instellingen op onder een naam die je typt; `/kmcauto preset delete <naam>` verwijdert er een.
- [ ] `/kmcchecklist` opent de checklist: klik om af te vinken (blijft bewaard in `checklist.yml`), `add <tekst>`, `remove <nr>`, `reset`, `defaults`.
- [ ] Game-kaarten in de lineup en de pre-game-kaart tonen voor Laser Logic en Factory Frenzy beschrijving, doel en punten (komt nu ook uit host.yml `games.<id>.description/objective`). Zie je nog niets: noteer wat er precies staat bij "GAME n / m".
- [ ] TGTTOS: iedereen start naast elkaar op de eerste map, ook als er meer spelers dan startpunten zijn. ↻ Wat bedoelde je met "met alle spelers tegelijk"? Noteer wat je ziet.
- [ ] Spectator na een dood (Mob Mayhem e.a.): je kunt vliegen, ziet iedereen en kunt geen mobs of spelers raken; je staat waar je overleed.
- [ ] Mob Mayhem: de eerste 3 waves zijn voor elk team gelijk (geen modifiers, geen baby-zombies) en er lopen geen oude mobs rond bij de start.
- [ ] TNT Tag: Double Trouble geeft meteen extra bommen (minstens 2 erbij); de decoy rent binnen een vierkant van 3×3 rond.
- [ ] Dodgebolt: trekken aan de boog vóór de ronde start kost geen pijlen meer.
- [ ] Rising Inferno: de lava is nu zichtbaar als een magma-oppervlak (naast de deeltjes) en stijgt mee.
- [ ] Formula MC: je zakt of glitcht niet meer door andere boten; ze worden niet meer in "no physics" gezet.
- [ ] Plek bij uitschakeling: Spleef, SkyWars, Survival Games, TNT Tag en Block Party tonen "(36)", "(35)", …; Parkour toont "#n" bij finishen.
- [ ] `/kmcapi start` toont de URL, en meldt waarom het niet start (poort bezet/bind) of dat 127.0.0.1 alleen op de servermachine zelf werkt.

## A. Bouwen, deployen, opstarten

- [is niet nodig] `/kmcauto start` vanuit de console → start meteen, zonder menu.

## D. Gedeelde systemen (steekproef in 2-3 games)

- [ ] **Start- en eindgeluid:** bij het begin van een game een pling, bij het einde een stinger; een eigen `sounds.game-start` in een game-config wijzigt toonhoogte/volume.
- [ ] **Kroon verspringt** naar de nieuwe leider binnen ~1 game.
- [ ] **Koploper-flits:** bij 0-0 knippert de sidebar-titel niet; als team B team A inhaalt, flitst "⬆ NIEUWE KOPLOPER" ~3 s, met geluid en chatbericht.
- [ ] **Comeback-bonus:** team ≥100 punten achter → hun punten zijn ×1.25, zelfde bedrag voor speler én team; een team dat niet laatste is krijgt het gewone bedrag.
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
- [ ] Automatic Overtime bij gelijkspel in QuakeCraft.
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
- [?] Final Showdown (2 spelers) neemt het krimpen over; nieuwe game start met volle border.

### Survival Games
- [ ] "🍗 THE FEAST" één keer, kist verschijnt boven het midden met duidelijk betere loot; `feast-enabled: false` → geen kist.

### Mob Mayhem
Setup: `/mm status` → template-wereld ✔, spawn ✔, ≥4 mob-spawns. `/mm pos1/pos2` slaat hele blokken op en weigert buiten de template-wereld.
- [?] Je staat in een **gekloonde** wereld `mm_game_<team>_<random>`, niet in de template.
- [ ] ↻ **Nog open (werkte niet):** Meerdere waves achter elkaar; sterven → spectator, kan niet meer slaan.
- [?] `/mm stop` → gekloonde wereld wordt verwijderd, geen `mm_game_*` map over.
- [ ] Achievements: wave 10 → `survivor`, wave 5 zonder dood → `untouchable`, bossmob → `boss_slayer`.

### Speed Build
Nodig: 10 `.schem` in `plugins/KMCCore/schematics/`. Setup: `/speedbuild anchor`, `spawn`, `addbuild …` ×10, `status` → 10/10.
- [ ] Start → elke speler in eigen slot, blauwdruk ernaast.
- [ ] Hotbar: GROEN = klaar, ROOD = blauwdruk opnieuw, GOUD = einde (alleen bij build 10).
- [ ] GROEN toont nauwkeurigheid % + punten; builds gaan op volgorde; buiten je slot bouwen kan niet.
- [ ] Zelfde bouwsel twee keer → identiek %.
- [ ] Eindoverzicht klopt; punten landen bij speler én team.

### Hole in the Wall
- [ ] `/hitw testwall` toont een testmuur; events (bv. snelheid) werken.

### Formula MC (rest, zie ook N5)
- [ ] Andere racers zichtbaar binnen 10 blokken; `/fmc cam` flyover.
- [ ] ⚠ Boost pad en item box geven écht snelheid (server-`setVelocity` op boten is onzeker); stun en schild werken.

### Rising Inferno
- [ ] ↻ **Nog open (werkte niet):** ⚠ De lava (BlockDisplay) is zichtbaar en doet schade op het juiste niveau.

### Rocket Rush (`/rocket`)
Setup: `/rocket world | spawn | …`, snel: `/kmctest rocket_rush` → "✔ Rocket Rush is klaar om te starten".
- [ ] Checkpoints in volgorde, ring maar één keer, pickup, vallen → terug naar checkpoint, finish 100/85/70.
- [ ] Teams staan bij de start naast elkaar, kijken naar het midden van het parcours en nemen geen schade van elkaar.
- [ ] Sidebar: dun streepje, game-regels, dan TEAM met teampunten en jouw punten (met multiplier).
- [ ] `/pause` stopt de klok; `/rocket stop` midden in het spel ruimt alles op (ook effecten, titel, actiebalk).

### Knockout Kings (`/knockout`)
- [ ] ↻ **PvP:** bots slaan met de stick werkt (`/kmcbot info` toont `invulnerable=false`); teamgenoten niet.
- [ ] ⚠ Knockback voelt goed, val = eliminatie voor de laatste aanvaller, farm-limiet, 3 modifiers.
- [ ] Sidebar zoals Rocket Rush; `/pause` en `/knockout stop` ruimen netjes op.

### Crafting Countdown (`/craftcountdown`)
- [ ] Zie N8 (mijnen met houweel/bijl, Werkbank-item, bestellingen inleveren).
- [ ] Sidebar en `/pause`/`stop` zoals de andere arcade-games.

### Zone Zero (`/zonezero`)
- [ ] Twee teams in een zone = contested, zones wisselen, geen dubbele hold-punten.
- [ ] ↻ PvP: bots slaan werkt; teamgenoten niet.
- [ ] Punten zijn van de speler: alleen wie in de zone staat krijgt de capture-punten.
- [ ] Sidebar, `/pause` en `/zonezero stop` zoals hierboven.

### Crown Control (`/crown`)
- [ ] Een kroondrager scoort elke 10 s zelf punten; zijn team verdient energie.
- [ ] Een vijand pakt de kroon door 3× te slaan (actionbar telt mee) of door hem uit te schakelen; bij een kill gaat hij direct naar de killer.
- [ ] Teamgenoten kunnen elkaars kroon niet pakken; helm uitdoen via inventory kan niet.
- [ ] Vaardigheden (nether star) werken alleen als je team minstens één kroon draagt.
- [ ] ↻ PvP: bots slaan werkt; sidebar, `/pause` en `/crown stop` ruimen netjes op (ook kroon-displays).

### Core Charge (`/corecharge`)
- [ ] Boven je kern groeit een gekleurde pilaar mee met de energie, met een balk + percentage erboven.
- [ ] Boven elk station zweeft hoe lang er nog opgeladen moet worden; wie in de zone staat ziet het ook in de actiebalk.
- [ ] Vaardigheden met cooldown, Final Overload alleen in de laatste 30 s, eindbonus.
- [ ] ↻ PvP: bots slaan werkt; sidebar, `/pause` en `/corecharge stop` ruimen netjes op (ook kern-displays).

### SkyWars (en Survival Games)
- [ ] Kisten openen kan pas als het spel echt begint (niet tijdens intro/tutorial/countdown).
- [ ] Elke uitschakeling toont je plek: "(36)", "(35)", …

### Overige games
- [ ] **ParkourWarrior / ElytraEndrium / AdventureEscape:** finish-cam, baanrecords (`/kmctrack`), highlights.
- [ ] **Spleef, QuakeCraft, SkyWars, ParkourWarrior, ElytraEndrium:** `/kmctest list` toont welke een testarena hebben; `/kmctest <id>` → game start, speelt uit en geeft punten.
- [ ] **Lobby:** `/ttt`, `/darts` op een Target-blok, vissen, parkour, `/lobbyspleef` → geen toernooipunten.

## I. Arcade-games

De zes arcade-games staan bij **H. Games** (eigen sectie per game). Gemeenschappelijk voor alle zes:

- [ ] `/<cmd> start dry` boekt geen punten; `/pause` stopt de klok; `/<cmd> stop` midden in een game ruimt alles op.
- [ ] Dood = respawn met spawnbescherming (waar PvP), en je respawnt naast je teamgenoten, niet erin.
- [ ] Na elke spawn kijkt iedereen naar het midden van de arena; na stop/einde zijn glow, vuur, snelheid, vlucht, titel en actiebalk weg.

## J. Platform (golf 5)

- [ ] ↻ **Nog open (werkte niet):** `/kmcapi start` en `http://127.0.0.1:8765/overlay` → live standen in de OBS-overlay.
- [ ] `/kmccamera record game <gameId>` → vlieg → `record stop`; de flyover speelt de route.
- [ ] `/clip start`, speel een game; `clip-markers.csv` bevat game start/einde en kills; `/clip <tekst>` zet een marker (cooldown 5 s).
- [ ] ⚠ Spectator in een game: `/kmcspec` menu/volgen/leider; na het einde weg.
- [ ] Anti-grief als **niet-OP**: item-frame, emmer, kist en enderpearl in de lobby geblokkeerd.
- [ ] `/kmcaudit` na een minuut spelen → geen onveilige teleports; `/kmcaudit clear` wist de lijst.
- [Dit wil ik niet als functie] `/kmcemblem`, `/kmcemblem wear`.
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

- [ ] **Paper 26.3-valkuilen:** particle-kleuren, jump-boost-freeze, peaceful difficulty, geen honger in de lobby-radius.
- [ ] **PvP-poort:** PvP uit in de lobby, aan in games die `handlesPlayerPvp()` zeggen (nu ook alle arcade-games); `GameStopRequestEvent` stopt een game netjes.

---

## Nog niet gebouwd (geen fout, niet melden)

- Opnieuw joinen na een disconnect in de arcade-games.
- Achievements voor de zes arcade-games.
- Potion-effecten lopen door tijdens `/pause`.
- Assist-punten alleen in SkyWars; race-gap-indicator alleen in TGTTOS.
- Titels in games zijn niet aan GUI-schaal aangepast (alleen `/kmchud test` laat zien waar het misgaat).
- Factory Frenzy: lopende banden en machine-upgrades; Blockball: opgeslagen keepersacties (er zijn geen keepers, wel een doel-zone).
- Dungeon Draft: eigen mob-AI voor de baas (hij gebruikt een gewone mob met telegraaf-slagen) en kamers die zich tijdens het spel opbouwen.
- Achievements voor de vier nieuwe games.

## Een fout melden

Eén regel per fout, dan los ik alles in één ronde op:

```
Blok/regel:  N6 / Crown Control / kroon pakken
Verwacht:    na 3 klappen is de kroon van mij
Gebeurde:    niets, de teller blijft op 1/3
Console:     (plak de regel of "geen")
```

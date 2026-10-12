# Testplan — alles wat nog niet live is getest

Drie bronnen, samen één volgorde. Vink af in de bron zelf; dit document zegt alleen WAT je in WELKE volgorde doet.

| Stap | Bron | Wat | Open items |
|---|---|---|---|
| A | `TESTING-CHECKLIST.md` §0-1 | Automatisch + build/deploy-sanity | ~10 |
| B | `TESTING-CHECKLIST.md` §2-5 | Setup-menu, gedeelde systemen, Fan Favorite, `/event simulate` | ~40 |
| C | `TESTING-ALL-WAVES.md` §0-3 | Basis, regie, punten, presentatie (golf 1-3) | ~25 |
| D | `TESTING-CHECKLIST.md` §6-13 | Block Party, TGTTOS, TNT Tag, SG, Mob Mayhem, assists, easter eggs, tutorial | ~55 |
| E | `PLAYTEST-new-games.md` | TGTTOS V2, Block Party, Speed Build (setup + test) | 26 |
| F | hieronder "Nergens in een lijst" | Games en systemen die nog in geen enkele lijst staan | ~25 |
| G | `TESTING-ALL-WAVES.md` §4-6 | Golf 4 (games), golf 5 (platform), golf 6 (arcade) | ~30 |

Volgorde-regel: eerst A en B (gedeelde systemen), want alles in C-G bouwt daarop. Stop bij een rode fout in A/B
en los die eerst op.

## Nergens in een lijst (F)

Deze onderdelen zijn gebouwd of aangepast in eerdere sessies en staan in geen van de oudere checklists:

### Nieuwe games uit eerdere sessies
- [ ] **Hole in the Wall** (`/kmctest hole_in_the_wall`): muren met gaten, valpartij = eruit, rondes, plaatsingspunten
- [ ] HITW: kleurnamen (RoodBlauw) in de actionbar, legenda bij ronde 1, events (bijv. snelheid), `/hitw testwall`
- [ ] **Formula MC** (`/kmctest formula_mc`): ronde-detectie, checkpoints, snelste ronde-bonus, vast zittende boot -> terug op baan
- [ ] Formula MC: botsen kan niet, andere racers pas zichtbaar binnen 10 blokken, `/fmc cam` flyover
- [ ] **Dodgebolt**: best-of-3 finale (alleen als finale), team-eliminatie
- [ ] **ParkourWarrior / ElytraEndrium / AdventureEscape**: finish-cam, baanrecords (`/kmctrack`), highlights

### Testgereedschap zelf
- [ ] `/kmctest list`, `/kmctest <game>` voor Spleef, TNTTag, QuakeCraft, SurvivalGames, SkyWars, TheBridge,
      AdventureEscape, ParkourWarrior, ElytraEndrium, TGTTOS, MobMayhem: arena verschijnt, game start
- [ ] `/kmcbot add|remove|list|here`: fake spelers tellen mee, vallen, krijgen schade, respawnen
- [ ] `/kmcauto skip` en `/kmcauto stop` stoppen de ceremonie echt

### Ceremonie en taal
- [ ] `/kmcauto start`: openingsceremonie in 5 fases, leestempo schaalt, geen dubbele WelcomeBroadcaster
- [ ] Taal: `/kmclang` (of taalmenu) nl en en; geen Turks meer; ontbrekende sleutels vallen terug op de standaard
- [ ] Flyovers: vloeiend (replay-mod-gevoel), geen ruk bij waypoints
- [ ] Mob Mayhem: `/mm pos1/pos2` slaat hele blokken op, weigert buiten de template-wereld

### Bekende kwetsbare plekken
- [ ] Paper 26.3: particle-kleuren (`Color`-data), jump-boost-freeze (`freezeJump`), peaceful difficulty, hunger in lobby-radius
- [ ] PvP-poort: PvP is uit in de lobby, aan in games die `handlesPlayerPvp()` zeggen; forced stop via `GameStopRequestEvent`
- [ ] Deployment: `kmc-storage` staat NIET in `plugins/`; 28 jars, geen dubbele plugin-namen

## Hoe te rapporteren
Per fout: de stap (bijv. "D / Block Party / vloer reset"), wat je verwachtte, wat er gebeurde en de console-regel.
Dan los ik ze in één ronde op.

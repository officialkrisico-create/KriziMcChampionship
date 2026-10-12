# Testlijst golf 1 t/m 6

Alles hieronder is gecompileerd en (waar mogelijk) unit-getest, maar nog nooit live gedraaid. Test in deze
volgorde: eerst de basis (anders is de rest niet te beoordelen), dan per game. Solo kan veel met `/kmcbot add 8`
(fake spelers) en `/kmctest <gameId>` (testarena). Kruis af wat werkt; noteer wat stuk is met de console-fout.

## 0. Opstarten
- [ ] Server start zonder rode fouten in de console (alle 28 jars; `kmc-storage` staat NIET in `plugins/`)
- [ ] `/kmcauto` opent het startmenu, taal-GUI verschijnt bij een nieuwe speler
- [ ] `/kmcaudit` toont geen onveilige teleports na een minuut spelen
- [ ] `/gg`, `/clip list`, `/kmchud test`, `/teamprofile` bestaan (geen "onbekend commando")

## 1. Toernooi-regie (golf 1)
- [ ] `/kmcauto preset ...` + `/kmcauto dryrun` speelt een korte cyclus zonder punten te boeken
- [ ] `/pause` tijdens een game wacht tot het veilig is, `/resume` hervat; timers van de arcade-games staan stil
- [ ] Rerun van een game draait de punten terug en speelt hem opnieuw
- [ ] Finale (top-2) met Dodgebolt start en eindigt

## 2. Punten en data (golf 2)
- [ ] Na een game: score-uitsplitsing per team, `ledger.csv` bevat de awards
- [ ] Einde-van-event: archief, podium, awards, credits, feedback, statsboek, reset (`/kmcauto skip` slaat over)
- [ ] `/kmctrack` toont baanrecords, `/kmcseason` het seizoen

## 3. Presentatie (golf 3)
- [ ] Tablist in MCC-stijl met live game-punten; `/kmchud compact|minimal` kapt lange regels netjes af
- [ ] `/kmcnpc rank set 1..10`, `rank own`, `presenter set` + `presenter say`
- [ ] Intro-FX van een game, leaderboard-animatie, houten lepel (3+ teams), teamjingles
- [ ] Chat is dicht tijdens uitleg/ceremonie, ook voor OP
- [ ] GG-knop klikbaar na een game; verrassingsgame: naam verborgen in vote en line-up

## 4. Games (golf 4)
- [ ] Survival Games: supply drop (lichtstraal + kist), cornucopia-refill, rarity in de lore
- [ ] Formula MC: kwalificatie -> pole -> race, boost pad, item box, weer (`/fmc weather rain`)
- [ ] Boot-boost/stun werkt echt (server `setVelocity` op boten is onzeker)
- [ ] Hole in the Wall: legenda bij ronde 1, "muur vanuit RoodBlauw" in de actionbar
- [ ] Rising Inferno (`/kmctest rising_inferno`): lava stijgt, verbranden, orbs, finish, achievements
- [ ] Lobby: `/ttt`, `/darts` op een Target-blok, vissen, parkour, `/lobbyspleef`

## 5. Platform (golf 5)
- [ ] `/kmcapi start` en `http://127.0.0.1:8765/overlay` toont live standen
- [ ] `/kmccamera record game <gameId>` -> vlieg -> `record stop`, flyover speelt de route
- [ ] `/clip start`, speel een game, `clip-markers.csv` bevat game start/einde en kills
- [ ] Spectator in een game krijgt kompas/follow/leider-tracker, weg na het einde
- [ ] Anti-grief als niet-OP: item-frame, emmer, kist, ender pearl in de lobby geblokkeerd

## 6. Arcade-games (golf 6) — per game `/kmctest <id>` + `/<cmd> start dry`
- [ ] **Rocket Rush**: checkpoints in volgorde, ring maar één keer, pickup, val -> checkpoint, finish 100/85/70
- [ ] **Knockout Kings**: knockback voelt goed, val = eliminatie voor laatste aanvaller, farm-limiet, 3 modifiers
- [ ] **Crafting Countdown**: kist wordt gevuld, recept uitvoerbaar, stuk voor stuk inleveren, dubbel inleveren geeft niets
- [ ] **Zone Zero**: capture, twee teams = contested, zones wisselen, geen dubbele hold-punten
- [ ] **Crown Control**: capture 5 s, lock 20 s, menu via nether star, geweigerde activatie kost niets, lockdown
- [ ] **Core Charge**: orbs/stations, vaardigheden + cooldown, Final Overload alleen in laatste 30 s, eindbonus
- [ ] Alle zes: `/pause` stopt de klok, `/<cmd> stop` midden in een game ruimt alles op (geen blijvende entities)

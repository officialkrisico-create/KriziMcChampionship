================================================================
KMC Tournament — Multi-Module Project Setup
================================================================

This ZIP contains the parent project structure with empty module
folders. You need to move your existing KMCCore, LuckyBlock, and
AdventureEscape project files INTO the matching module folders.

================================================================
STEP 1 — Unzip this file
================================================================

You'll get a folder called `kmc-tournament/` with this structure:

    kmc-tournament/
    ├── pom.xml
    ├── build-all.bat
    ├── build-all.sh
    ├── README.txt  (this file)
    ├── KMCCore/
    │   └── pom.xml       ← child POM, ready
    ├── LuckyBlock/
    │   └── pom.xml       ← child POM, ready
    └── AdventureEscape/
        └── pom.xml       ← child POM, ready

================================================================
STEP 2 — Move your existing src/ folders INTO the modules
================================================================

For each module, copy the `src/` folder from your existing
standalone project into that module:

  Your existing KMCCore project:
    MyProjects/KMCCore/src/main/java/...
    MyProjects/KMCCore/src/main/resources/...
    MyProjects/KMCCore/pom.xml                    ← DELETE this (replaced)

  After moving:
    kmc-tournament/KMCCore/
    ├── pom.xml                                   ← keep (from this zip)
    └── src/                                      ← moved from old project
        └── main/java/...

Do the same for LuckyBlock and AdventureEscape.

IMPORTANT: REPLACE the old pom.xml files with the ones from this
zip. The child POMs in this zip inherit from the parent and are
much shorter than your old standalone pom.xml files.

================================================================
STEP 3 — Build everything with ONE command
================================================================

From inside the kmc-tournament/ folder, run:

    mvn clean package

Or use the convenience script:
    Windows:   build-all.bat
    Linux/Mac: ./build-all.sh

Maven will:
  1. Read the parent pom.xml
  2. Build KMCCore first (it's listed first in <modules>)
  3. Build LuckyBlock next (depends on KMCCore — classes available now)
  4. Build AdventureEscape last (same reason)

The finished jars end up in each module's target/ folder:
  KMCCore/target/KMCCore-1.0.0.jar
  LuckyBlock/target/LuckyBlock-1.0.0.jar
  AdventureEscape/target/AdventureEscape-1.0.0.jar

The build-all scripts also copy all three jars into dist/
for convenient deployment.

================================================================
STEP 4 — Copy to server
================================================================

Copy the three finished jars into your server's plugins/ folder.
Restart (don't /reload) the server. KMCCore loads first (because
the other plugins have 'depend: - KMCCore' in plugin.yml).

================================================================
WHY A MULTI-MODULE PROJECT?
================================================================

- Build everything with ONE command
- KMCCore changes propagate instantly — change a method signature
  in KMCCore and both game plugins see it on their next compile
- No need to `mvn install` KMCCore separately to your local repo
- Adding a new game plugin = create a new folder + pom.xml, add
  it to the parent <modules> list, done
- Your IDE (IntelliJ, VS Code, Eclipse) will detect the parent
  pom and treat it as one project with three subprojects

================================================================
ADDING A NEW GAME LATER
================================================================

1. Create a folder next to the others:    kmc-tournament/Quake/
2. Copy LuckyBlock/pom.xml into it, rename the <artifactId>
3. Add your src/ folder
4. Open kmc-tournament/pom.xml and add:
       <modules>
           <module>KMCCore</module>
           <module>LuckyBlock</module>
           <module>AdventureEscape</module>
           <module>Quake</module>     ← new
       </modules>
5. mvn clean package

Done — Quake automatically has access to KMCCore's API.

================================================================
TROUBLESHOOTING
================================================================

"Could not resolve dependencies for nl.kmc:KMCCore"
  → You built LuckyBlock or AdventureEscape BEFORE KMCCore.
    Run `mvn clean package` from the PARENT folder, not the
    child module folder.

"Parent pom not found"
  → The <relativePath> is missing from child POMs (it's inferred
    by default — the pom.xml's I provided don't set it). Make
    sure your folder layout matches the expected structure.

"BUILD SUCCESS but jars don't work on the server"
  → Check you copied KMCCore-1.0.0.jar (not original-KMCCore).
    The shade plugin produces both — the unshaded `original-`
    prefix version is incomplete.

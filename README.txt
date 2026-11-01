Desert Vanguard - Forge 1.20.1 (47.x), JDK 17

1) Textures: already included.
2) Gradle wrapper is not included. Easiest: download the official Forge 1.20.1 MDK,
   copy gradlew, gradlew.bat and the gradle/ folder from it into this project
   (or just copy src/ into the MDK and set mod_id=desertvanguard).
3) ./gradlew runClient   (or ./gradlew build -> build/libs/desertvanguard-1.0.0.jar)
4) Creative -> Spawn Eggs tab -> "Desert Vanguard Spawn Egg",
   or /summon desertvanguard:desert_vanguard

If it faces backwards: set FLIP_FRONT = True in tools/convert_pmx.py, then
  python tools/convert_pmx.py tools/Mob_-_Skeleton_-_Desert_Vanguard.pmx src/main/resources/assets/desertvanguard/models/entity/desert_vanguard.mesh

GitHub build: push the project to a repo (textures included!). Actions tab -> "Build mod"
-> open the finished run -> Artifacts -> desertvanguard-jar. Use the jar WITHOUT "-sources"/"-slim" suffix if several appear.

Animations (idle / walk / stab):
They come from the Blockbench model tools/vanguard.gltf. To change them, edit the model in Blockbench, export glTF
over tools/vanguard.gltf and re-run
  python tools/convert_gltf_anim.py tools/vanguard.gltf tools/Mob_-_Skeleton_-_Desert_Vanguard.pmx src/main/resources/assets/desertvanguard/models/entity/desert_vanguard.anim
(needs python3 + numpy + scipy). The renderer (DesertVanguardRenderer.java) loops "idle" all the time and blends in "walk"
by how fast the mob moves. "stab" is already baked into the .anim file for the future attack AI, nothing plays it yet.
The static IDLE table in tools/convert_pmx.py is no longer used.

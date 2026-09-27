# NPC Studio

A Minecraft mod for **Fabric** (Minecraft 26.2) for building NPCs that talk, act
and play out scenes: branching dialogue, cutscenes with cameras and captions,
animations, skins — all edited inside the game, in a workspace of its own.

> **Status: in development.** The mod is not finished and has no release yet. It
> is planned to be published on **CurseForge** once it is ready; until then the
> only source is this repository, and things may change or break between commits.

> **Please do not upload this project to CurseForge, Modrinth or anywhere else
> under your own name.** The licence does not allow publishing the mod, or a
> modified version of it, as a separate project of your own (section 2(b) of
> [LICENSE](LICENSE)). The official CurseForge page will come from the author.

## What it does

- **NPC Studio workspace** — one screen with panels you can dock, detach and
  resize: the scene, its objects and assets, the selected character, and the
  editors below. Only an operator can open it.
- **Dialogue** — a node graph for branching conversations, edited in the game,
  shown through a dialogue interface that can be reskinned.
- **Cutscenes** — a timeline with cameras you place, aim and look through, and
  captions.
- **Characters** — skin, wardrobe, body build, pose, items in each hand, and
  animations in the EmoteCraft format.
- **Test bench** — watch what an NPC notices, remembers and pays attention to,
  and make it do something on the spot.

## Building

```
./gradlew build
```

Needs JDK 25. Gradle finds it by itself when it is installed; `JAVA_HOME` does
not have to change. The jar lands in `build/libs/`.

## Third-party material

### SPEmotes

Character animations come from the **SPEmotes** pack. It is somebody else's work,
and the mod only plays it: the files are included as they are, unchanged.

- The author's site: https://spemotes.com/
- Boosty: https://boosty.to/spemotes

The author distributes the pack freely for non-commercial use; only the part that
is publicly available is included. Paid Boosty exclusives are not. To support the
author, follow the links above.

The files are in the EmoteCraft format, which the mod reads too, so animations of
your own can sit beside them in an ordinary resource pack without touching the mod.

### Notice

All third-party material belongs to its authors. If you are the author of
something used here and object to it being in this project, write, and it will be
removed at once.

## Licence

NPC Studio License 1.1 — the full text is in [LICENSE](LICENSE), with a plain
summary in Russian. In short: playing, streaming, putting it on servers and
sharing the whole mod unchanged and free of charge is allowed; selling it or
publishing it under your own name is not.

Pieces of the source code may go into a mod of your own, a paid one included, as
long as that mod is its own work and not a republication of NPC Studio, and the
authorship is named — a condition, not a request. That covers code only, not
textures or models. Add-ons and integrations through the API are on any terms.

Ideas are not held by the licence: take them and build your own.

None of this extends to third-party material: see above.

---

## По-русски

Мод для Minecraft 26.2 (Fabric): NPC с ветвящимися диалогами, катсценами,
анимациями и скинами, которые настраиваются прямо в игре.

**Мод в разработке.** Готового выпуска пока нет. В будущем мод планируется
опубликовать на **CurseForge**; до тех пор он есть только здесь, и от коммита к
коммиту что-то может меняться или ломаться.

**Пожалуйста, не заливайте проект на CurseForge, Modrinth или куда-либо ещё под
своим именем.** Лицензия запрещает публиковать мод или его изменённую версию как
свой отдельный проект (пункт 2(b) в [LICENSE](LICENSE), там же краткое изложение
по-русски). Официальная страница на CurseForge появится от автора.

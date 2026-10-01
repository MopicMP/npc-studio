# NPC Studio

> **Early development.** The mod is at an early stage, has many bugs and rough
> edges, and will change between commits. Things may break. If you try it now,
> expect to rebuild characters after updates.

A Minecraft mod for **Fabric** (Minecraft 26.2) that adds NPCs with branching
dialogue, cutscenes, animations, skins and behaviour — all edited inside the
game, in a workspace of its own.

> **Please do not upload this project to CurseForge, Modrinth or anywhere else
> under your own name.** The licence forbids publishing the mod, or a modified
> version of it, as a separate project (section 2(b) of [LICENSE](LICENSE)).
> The mod is planned to be published on **CurseForge** by the author once it is
> ready; until then, the only source is this repository.

## Getting started

### Requirements

- Minecraft 26.2
- Fabric Loader 0.19.3+
- Fabric API
- JDK 25 (for building from source)

### Building

```
./gradlew build
```

The jar lands in `build/libs/`. Gradle finds JDK 25 by itself when it is
installed.

### Your first NPC

1. Make sure you are an operator (creative mode, or `/op` yourself).
2. Spawn an NPC:
   ```
   /npc spawn
   /npc spawn <SkinName>
   ```
   The NPC appears where you stand, facing you, wearing the named player's skin
   (default: Steve).
3. Press **N** to open the NPC Studio workspace.
4. Click the NPC or shift-click her to select her and open the character panel.

From here, everything is edited visually — no more commands needed.

## The workspace

Press **N** to open it. Only operators can. The workspace is a screen with
panels you can dock, detach, resize and rearrange:

| Panel | What it does |
|---|---|
| **Scene** | The 3D viewport, showing the world and its NPCs |
| **Objects** | Everything in the current scene |
| **Character** | The selected NPC: skin, dialogue, items in hands, settings |
| **Wardrobe** | Skin browser and skin layers |
| **Build** | Body shape parameters |
| **Pose** | Manual posing of each limb |
| **Animations** | EmoteCraft animation picker and preview with playback controls |
| **Dialogue** | The node graph — see below |
| **Timeline** | When things happen in a cutscene |
| **Camera** | Place, aim and look through cameras for cutscenes |
| **Captions** | Subtitles and on-screen text for scenes |
| **Music** | Background music for scenes |
| **Environment** | Time of day, weather, and world settings for scenes |
| **Shaders** | Visual effects |
| **Test bench** | What the NPC notices, remembers and pays attention to; fire commands at her |
| **Credits** | Attribution for third-party material |
| **Assets** | Shared resources |
| **Start** | The landing panel with quick actions |

Press **M** to enter the modelling mode (building NPC models from parts), and
**G** to open the action ring without the full workspace — useful while walking
around placing things.

## The dialogue graph

This is the core of what makes an NPC behave. It is a **visual node graph**
edited right inside the game: boxes connected by wires, laid out left to right in
the order the conversation is read.

### How it works

Open the **Dialogue** panel at the bottom of the workspace. The empty state
lists every dialogue in the world and lets you create a new one. Pick or create
one, and the graph appears.

Each **box** is a node. Each **wire** is a connection to the next node. You edit
nodes by clicking them — their fields appear in a side panel, and small edits
happen right in the box. There is no "Done" button: changes land as you type,
and **Save** sends the whole dialogue to the server.

### Node types

The graph has these building blocks:

| Node | What it does | Waits? |
|---|---|---|
| **Line** | The NPC says something. Speaker name, text, portrait, animation. | Yes — until the player clicks or the timer runs out |
| **Choice** | The NPC asks a question and the player picks an answer. Each answer leads to a different node. | Yes — until the player chooses |
| **Set** | Assigns a value to a variable. | No |
| **Branch** | Checks conditions and goes one way or another. Like an if/else. | No |
| **Chance** | Picks a random path from several. | No |
| **Act** | Makes something happen: play an animation, walk somewhere, give an item, run a command. | No |
| **Walk** | Sends the NPC along a route of waypoints. | Yes — until she arrives |
| **Every** | Pauses for a number of ticks, then continues. A timer. | Yes |
| **Until** | Waits until a condition becomes true. | Yes |
| **Pressed** | Waits for the player to press a shown button. | Yes |
| **Do** | Runs another dialogue as a subroutine, then comes back. | Depends |
| **Stop** | Stops a subroutine that is running. | No |
| **End** | The conversation is over. The NPC can go home or stay. | — |
| **Comment** | A note for the author, ignored at runtime. | — |

### Variables and conditions

Dialogues declare their variables up front — no silent creation on first use, so
a typo is caught as an error rather than becoming a second variable stuck at zero.
Variables can be scoped to one conversation, to one NPC, or to the whole world.

Conditions include: comparing variables, checking the player's inventory, whether
a node was visited, whether the player is inside an area, what block is at a
mark, and logical combinations (all, any, not).

### Effects (what Act nodes do)

An Act node triggers an effect — something that happens in the world:

- **Play animation** — the NPC plays an EmoteCraft animation
- **Walk to** / **Halt** / **Go home** — pathfinding to a named mark
- **Follow** — the NPC follows a route or the player
- **Look at** — turn to face something
- **Give item** / **Take item** — inventory changes
- **Run command** — execute a server command
- **Play sound** — with volume and pitch
- **Express** — facial expressions
- **Strike** / **Fire** — combat actions
- **Wall** — raise or lower a barrier (an area becomes passable or not)
- **Put block** — place a block at a named mark
- **Show** / **Unshow** — display or hide something at a point
- **Portrait** — show a picture on screen
- **Appear** — change the NPC's look
- **Guard** — take a defensive position
- **Send** — teleport the player

### Cutscenes

A dialogue can include cutscenes: place cameras in the world, aim them, set
keyframes on a timeline, and the player's view follows them while lines play and
captions appear.

### The test bench

The test bench panel lets you inspect a live NPC without playing through
dialogues: what she notices, what she is doing, her memory, her senses. You can
fire actions at her and reset her state — useful for debugging dialogue logic.

## Animations

Character animations use the **EmoteCraft** format. The mod includes the
**SPEmotes** pack (see credits below) and reads any EmoteCraft animation placed
beside it in a resource pack.

The animation picker in the workspace lets you browse, search, preview and assign
animations with playback controls (play, pause, speed, restart).

## Key bindings

| Key | Action |
|---|---|
| **N** | Open the NPC Studio workspace |
| **M** | Enter modelling mode |
| **G** | Open the action ring (quick actions without the full workspace) |

All three can be rebound in Minecraft's controls settings.

## Third-party material

### SPEmotes

Character animations come from the **SPEmotes** pack. It is somebody else's work,
and the mod only plays it: the files are included as they are, unchanged.

- The author's site: https://spemotes.com/
- Boosty: https://boosty.to/spemotes

The author distributes the pack freely for non-commercial use; only the part that
is publicly available is included. Paid Boosty exclusives are not. To support the
author, follow the links above.

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

> **Ранняя стадия разработки.** У мода много багов, он будет меняться и
> обновляться. Если попробуете сейчас, будьте готовы пересоздать персонажей
> после обновлений.

Мод для Minecraft 26.2 (Fabric): NPC с ветвящимися диалогами, катсценами,
анимациями и скинами, которые настраиваются прямо в игре.

**Пожалуйста, не заливайте проект на CurseForge, Modrinth или куда-либо ещё под
своим именем.** Лицензия запрещает публиковать мод или его изменённую версию как
свой отдельный проект (пункт 2(b) в [LICENSE](LICENSE)). Официальная страница на
CurseForge появится от автора.

### Быстрый старт

1. Убедитесь, что вы оператор.
2. Создайте NPC: `/npc spawn` или `/npc spawn <Ник>`.
3. Нажмите **N** — откроется рабочее пространство NPC Studio.
4. Нажмите на NPC, чтобы выбрать её и открыть панель персонажа.

### Граф диалогов

Главное в моде — визуальный редактор диалогов прямо в игре. Это блочное
программирование: каждый блок — шаг разговора или действие, соединённые
проводами. Разговор разворачивается слева направо.

Типы блоков:
- **Line** — NPC говорит реплику
- **Choice** — NPC задаёт вопрос, игрок выбирает ответ
- **Set** — присвоить значение переменной
- **Branch** — проверить условие и пойти по одной из веток
- **Act** — сделать что-то: анимация, ходьба, предмет, команда
- **Walk** — отправить NPC по маршруту
- **Every** — подождать N тиков
- **Until** — ждать, пока условие станет правдой
- **End** — конец разговора

Переменные объявляются заранее — опечатка в имени ловится как ошибка.

### Клавиши

| Клавиша | Действие |
|---|---|
| **N** | Рабочее пространство |
| **M** | Режим моделирования |
| **G** | Кольцо быстрых действий |

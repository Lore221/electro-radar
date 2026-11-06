# Protection-zone selection: Create comparison

Research baseline: Electro-Radar `interceptor` commit `ff22574`, before selector changes. Remote source reads used the connected GitHub app exclusively.

## Version evidence and primary sources

Create's [`mc1.21.1-6.0.10` tag](https://github.com/Creators-of-Create/Create/tree/mc1.21.1-6.0.10) resolves to `ac0c444d9828da3453ae8cc65338e8de063286fb`. The installed `create-1.21.1-6.0.10-280-sources.jar` manifest contains the same `Git-Hash`.

Sources:

- [Create SchematicAndQuillHandler](https://github.com/Creators-of-Create/Create/blob/ac0c444d9828da3453ae8cc65338e8de063286fb/src/main/java/com/simibubi/create/content/schematics/client/SchematicAndQuillHandler.java).
- [Create AllKeys](https://github.com/Creators-of-Create/Create/blob/ac0c444d9828da3453ae8cc65338e8de063286fb/src/main/java/com/simibubi/create/AllKeys.java).
- [Create ClientEvents](https://github.com/Creators-of-Create/Create/blob/ac0c444d9828da3453ae8cc65338e8de063286fb/src/main/java/com/simibubi/create/foundation/events/ClientEvents.java#L123-L149).
- [Catnip Outliner](https://github.com/Creators-of-Create/Ponder/blob/89819291b726708d119b118dea01667eae0b64c9/common/src/main/java/net/createmod/catnip/outliner/Outliner.java).
- [Catnip ChasingAABBOutline](https://github.com/Creators-of-Create/Ponder/blob/89819291b726708d119b118dea01667eae0b64c9/common/src/main/java/net/createmod/catnip/outliner/ChasingAABBOutline.java).
- [Catnip AABBOutline](https://github.com/Creators-of-Create/Ponder/blob/89819291b726708d119b118dea01667eae0b64c9/common/src/main/java/net/createmod/catnip/outliner/AABBOutline.java).
- [PonderClient tick/render integration](https://github.com/Creators-of-Create/Ponder/blob/89819291b726708d119b118dea01667eae0b64c9/common/src/main/java/net/createmod/ponder/PonderClient.java#L50-L75).

The three complete Catnip/Ponder classes above match the installed `ponder-neoforge-1.0.82+mc1.21.1-sources.jar` source text. The Ponder commit is a pinned source reference, not a claim that it is the release tag for that jar.

## Exact Create behavior

All interaction details below come from `SchematicAndQuillHandler`; modifier defaults come from `AllKeys`.

- Active only with the quill in the main hand, a level present, and no open screen.
- Right mouse press fixes the first corner, next press fixes the second, third press opens the schematic naming/save prompt. Shift + right mouse discards both corners. A missing target reports no target and does not advance selection.
- Before first click, preview is the target block. Between clicks it is the inclusive box from first corner to target; losing the target leaves a one-block box at the first corner. After the second click the box remains fixed.
- Without activation modifier, selection raycasts 75 blocks. A vertical face on a nonreplaceable block chooses the neighboring block in that face's direction. Horizontal faces use the hit block; replaceable blocks use the hit block.
- Holding `ACTIVATE_TOOL` (fixed left Ctrl) targets `floor(eyePosition(partialTicks) + lookAngle * range)`. Range begins at 10. Ctrl + wheel before the second corner adjusts `range = (int) clamp(range + delta, 1, 100)` and consumes scrolling. `ctrlDown()` checks the configurable Ctrl modifier binding, whereas `ACTIVATE_TOOL` is fixed left Ctrl in this source.
- After second corner, selected face comes from a 70-block predicate raycast against inclusive selection bounds inflated by 0.45. It accounts for camera inside/outside and reverses facing when inside.
- Ctrl + wheel resizes the highlighted face. Camera inside the **corner-coordinate AABB** reverses wheel delta. Delta rounds away from zero; the resize never moves minimum past maximum, so dimensions stay at least one block. For a camera outside, positive wheel delta contracts the highlighted face, negative expands; inside reverses this. This sign is confirmed directly by the arithmetic in `mouseScrolled`, not inferred from a tooltip.
- A stable `outlineSlot` object survives across ticks. Each active client POST tick calls `chaseAABB` with the latest box, color `0x6886c5`, checkerboard face textures, line width `1/16`, and selected-face highlight. Selection itself remains block-quantized.

## Electro-Radar baseline differences

Local sources: [`ZoneSelectionClientEvents.java`](../../src/main/java/com/electroradar/client/alarm/ZoneSelectionClientEvents.java), [`ZoneSelectionItem.java`](../../src/main/java/com/electroradar/radar/alarm/ZoneSelectionItem.java), [`ZoneBounds.java`](../../src/main/java/com/electroradar/radar/alarm/ZoneBounds.java), [`ZoneResizePayload.java`](../../src/main/java/com/electroradar/radar/alarm/ZoneResizePayload.java).

| Behavior | Electro-Radar at baseline | Create 6.0.10 |
| --- | --- | --- |
| First-corner preview | None before first click | One target block |
| Surface target | Vanilla hit result/client, clicked block/server | 75-block raycast with vertical-face replacement rule |
| Air target | Automatic fallback at fixed 16 blocks | Ctrl only, adjustable 1–100, starts at 10 |
| Second click | Saves zone into item immediately | Fixes second corner; later click opens prompt |
| Completed click | Reports ready; clicking alarm applies | Opens save prompt |
| Cancel | Shift + right-click resets item | Shift + right-click discards selection |
| Hand | Main hand or offhand | Main hand |
| Face targeting | Exact AABB clip up to 96 blocks | Inflated bounds, predicate raycast up to 70 |
| Wheel | Ctrl, completed box, sign only, positive expands regardless of inside | Ctrl changes range before completion; outside positive contracts and inside reverses |
| Outline lifetime | Removes both slots every POST tick before chasing | Retains stable outline entry while chasing |
| Face fill | No face textures | Checkered with brighter selected face |
| Authority | Server stores corners, validates and resizes; UUID guards stale resize requests | Local handler stores corners and exports schematic |

The naming/export flow serves a different artifact in Create. Whether Electro-Radar keeps immediate save after second corner is an explicit product decision, separate from matching Create's targeting and animation. Alarm application, item persistence, offhand support, server bounds/world validation, and stale-request protection are Electro-Radar features rather than requirements established by Create's code.

## Established animation defect and repair invariant

`ZoneSelectionClientEvents.tick` unconditionally calls `remove(SELECTION)` and `remove(APPLIED)` immediately before `chaseAABB`. Catnip creates a new `ChasingAABBOutline` for a missing slot. Its constructor starts previous/current/target bounds equal, so removing every tick destroys chase history. This directly explains why the existing chase call cannot interpolate movement between tick targets.

`chaseAABB` updates only `targetBB` and refreshes TTL. `showAABB` overwrites previous/current/target bounds together. Each Catnip tick copies current into previous and moves current halfway toward target; each render linearly interpolates previous/current with render partial ticks. Preserve slot identity and its entry across active ticks, update target once per client tick, and remove only when that visual is no longer applicable. Do not tick or replace the outline from render events. Ponder already owns ticking and rendering the global Outliner.

## Acceptance and regression checklist

- [ ] Before first click, block preview agrees with the eventual server-selected corner.
- [ ] First and second clicks produce an inclusive box in every corner ordering, including equal corners.
- [ ] Normal targeting follows Create's vertical-face/replaceable-block rule and 75-block limit.
- [ ] Ctrl air targeting and Ctrl + wheel range work before either corner, clamp to 1–100, and consume scrolling without hotbar movement.
- [ ] Missing normal target does not invent an automatic 16-block corner; after first corner the first block stays previewed.
- [ ] Completed selection highlights a face and resizes from all six faces, from inside and outside, including fractional wheel deltas and minimum dimensions.
- [ ] Shift + right-click clears selection; alarm application and server validation remain functional.
- [ ] Confirmation behavior matches the chosen product decision; no naming/export UI is assumed from Create by accident.
- [ ] The same actual Outliner entry survives consecutive preview/resize ticks and animates continuously; inactive, cleared, switched-item, and changed-dimension visuals disappear appropriately.
- [ ] Applied-zone outline also preserves its chase history while it remains applicable.

Real-library regression route: instantiate `new Outliner()` for isolation, call `chaseAABB(slot, start)`, retrieve `getOutlines().get(slot).getOutline()`, cast to `AABBOutline`, and read public `getBounds()`. Chase a new box with the same slot, assert identical outline instance, tick via `tickOutlines()`, and assert halfway bounds. Refresh each subsequent tick to avoid TTL expiry.

For rendering interpolation, subclass the actual `ChasingAABBOutline` and override protected `renderBox` to capture its AABB argument without invoking GPU rendering. Install it with `showOutline(slot, instance)`, use the real `chaseAABB` and `tickOutlines`, then call its real `render` at partial ticks 0, 0.5, and 1. Its overridden sink observes the actual interpolation path. `prevBB` and `targetBB` are package-private, so there is no need to access them or mirror interpolation math in production code. Whether plain JUnit can initialize these classes in this repository's runtime must be checked by executing the test; source inspection alone does not establish that.

# Protection-zone selector behavior

## Scope

Match Create 6.0.10 targeting, outline animation and editing controls, retaining the existing
Electro-Radar contract: two corners save an absolute dimension-bound zone into the item;
RMB inserts the selector into Shell Alarm (see interception-network.md). No schematic file naming/export or third confirmation.
Both hands remain supported. The server owns item data and all zone validation.

## Interaction

- Preview the first target block; after one click preview the inclusive box to the target.
- Normal raycast: 75 blocks; vertical nonreplaceable faces offset to the neighboring block.
- Ctrl: target in air at adjustable distance (default 10, limits 1–100); Ctrl+wheel adjusts distance
  until the second corner. Without a hit and without Ctrl, selection does not advance.
- Each use press fixes at most one corner. Holding use must not finish a zone automatically.
  Mouse and rebound keyboard release events rearm selection even between client ticks.
  The initial main-hand event dispatches the active selector hand and cancels Vanilla processing.
- After two corners, Ctrl+wheel resizes the highlighted face. Positive outside contracts,
  positive inside expands. Fractional deltas round away from zero; resize packets are limited
  to 100 blocks per action; contraction stops at one block.
- Face hit testing uses Create's 70-block predicate trace against bounds inflated by 0.45.
  Wheel inside/outside uses the inclusive zone bounds (including single-block zones).
- Shift+RMB clears item selection; the inserted selector must be extracted before editing.
- Selection and applied outlines preserve their chasing history between ticks and render frames.
  Remove stale visuals on deactivation, dimension, hand, slot and selection changes.
- Packets carry intent, not target coordinates. The server rejects invalid ranges, missing/wrong
  items, spectators, stale selection UUIDs and invalid world bounds. Applying to Alarm keeps
  the vanilla `canInteractWithBlock(pos, 1)` reach predicate. Existing resize UUID validation remains in place.

## Evidence

See ../research/protection-zone-selection.md for primary-source links. Automated checks cover
real Outliner tick/frame interpolation, removal of inactive visuals, wheel polarity, fractional
motion, distance bounds and minimum dimensions. In-game acceptance remains required for
input ordering, server/client target agreement and visual presentation.

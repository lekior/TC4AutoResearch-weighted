# Domain Context

## Research puzzle

A research puzzle is the current `ResearchNoteData` board. Its topology contains every usable hex and every fixed
anchor, but excludes ordinary player or solver placements. Placements may change while a task is executing without
changing puzzle identity.

## Fixed anchor

A fixed anchor is a type-1 hex with an aspect. Solvers must preserve anchors. The only permitted exception is a
verified repair for an exact incompatible forced corridor.

## Verified repair

A verified repair removes candidate occupied cells only from a note snapshot, solves the resulting board, and validates
the entire board before any packet is sent. This includes interrupted type-2 player/solver placements. A generic
`no_path` failure is never a repair request.

## Weight

Lower weight means earlier path preference. Disabled aspects cannot be generated as intermediate placements. The
inventory allocation preset uses positive-stock midrank percentiles and powers of two; missing aspects receive the
maximum weight 512.

## Weight GUI

Weight configuration uses the same neutral `GuiButtonExt`, default background, and gray list language as research
search and completion reports. Aspect icons retain their registered colors. `GuiWeightScreen` owns the shared page
chrome and `GuiThemeRenderer` owns lwjgl3ify-safe RGBA and GL state.

## Research plan

A research plan is the parent-first, de-duplicated closure of one target's `parents` and `parentsHidden`, followed by
the target. Each entry is classified from live client knowledge and research-table inventory as completed, ready to
learn, ready to solve, ready to generate, waiting for prerequisites, or unavailable. The preview is advisory; every
mutation still waits for original server confirmation.

## Research note transfer

A research note transfer is a shift-click between the research-table note slot and the player's 36-slot inventory.
The optimistic client slot mutation is not confirmation. A transfer is confirmed only when the server accepts the
matching window and transaction IDs, a five-tick settling window has elapsed, and the expected note-slot state is
visible. On rejection, the controller waits for the server's full window resynchronization, settles, and retries once.
A second rejection or timeout stops batch research; it never advances the queue from an optimistic client slot state.

## Board geometry

Board geometry is the immutable sorted set of usable hexes, their integer indices, and six-way adjacency. It excludes
anchors, placements, inventory, and solver settings, so the same bounded cache entry can be reused across notes and
verified-repair snapshots with identical physical layouts.

## Aspect compatibility graph

The aspect compatibility graph is built from the live aspect registry after load completion plus any unregistered
anchor aspects. Gameplay solving treats an unchanged registry size with all required anchor identities present as a
cache hit; a new required identity or registry-size change rebuilds the graph.

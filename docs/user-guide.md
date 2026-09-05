# Cropium user guide

Cropium is a Fabric 26.2 client automation suite for testing in a single-player world or on a server that explicitly permits it. It includes crop and mine harvesting, merchant handling, and an egg-hatching workflow.

For an overview, see the [README](../README.md). Commands below run from the repository root.

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API 0.158.0+26.2
- Java 25 to build from source

## Build

```powershell
.\gradlew.bat build
```

The playable mod is `build/libs/cropium-1.0.0.jar`. Put it and Fabric API in the instance's `mods` folder. Sync software can lock Gradle's generated `build` directory; build from a local non-synced copy if that occurs.

## Controls

- `B`: select the next field corner.
- Double-tap `Space`: vanilla flight only; it never starts Cropium.
- `H`: prepared start or stop for the crop harvester (saved map + usable tool in hotbar slot 1 required).
- `P`: pause or resume.
- `R`: clear the bounds.
- `J`: scan the selected field at farming height.
- `O`: open the Cropium dashboard.
- Middle click: open Cropium without the keyboard. Settings can switch this to Mouse 4/5 or disable it.
- Opening Cropium pauses harvesting; use Controls → Resume. During startup it may stop a mine entry safely instead. During an inventory workflow the menu request waits for a safe boundary.
- `Shift` or an unexpected menu: emergency stop. Workflow inventories also have mouse-operated **Stop mod** and **Controls** buttons. Controls closes the inventory only with an empty cursor; inspect unfinished Salvage/transactions before using it.

## Dashboard and modules

Controls adds prepared Farm/Mine/Egg starts, Pause/Resume, Stop all, Recover, preflight checks, and mining health. A prepared harvest start checks the saved map/world and an unbroken item in hotbar slot 1 before bounded teleport/entry/low-flight setup. Custom server tools do not need a vanilla TOOL component. A full inventory is a warning, not a startup blocker, because server drops may be virtual. Flight permission is checked at the destination before flight inputs; `/mine` is allowed to leave a lobby that does not grant flight. Unfinished container/cursor transactions still block startup. Blocked starts report the reason in chat and the menu header. Resume buttons use the same startup path and can restart an interrupted preparation if needed. Farm, mine and egg macros restore the first hotbar slot each active in-world tick if selection changes. The lock is suspended during GUIs, merchant transfers and NPC placement; normal merchant completion returns to slot 1. Paused/idle macros do not fight manual item selection.

The five-second health check samples attack **before** controllers reassert it and watches actual local block-break events. It shows last live aim/tool state and break age; it restores the slot-1 tool, releases/rearms vanilla attack, tries a bounded floor re-aim, then existing safe recovery. Repeated failed windows stop the macro. Pauses and recovery phases suspend the timer without erasing its retry budget. These client observations are not explicit server acknowledgements.

Settings provides Cautious, Balanced and Coverage movement presets. Variation is coherent and restrained near hazards/targets; hard clearance and the mine predictor are unchanged. Coverage favors less recently mined candidate routes. Advanced sliders are collapsed initially. The native GUI-scale button enlarges Minecraft's entire interface/text (0 means automatic), not only Cropium.

Coverage shows farm/mine routes, recent breaks, ice, glows/fossils, misses and ignored targets. While stopped or paused, drag a rectangle and apply an exclusion. It is saved per world and macro, includes whole blocks plus body padding, and must preserve entry/player clearance and at least one viable route. Removing the last exclusion is mouse-operated. Edits invalidate live navigation but never overwrite the original saved map. Recent coverage is a bounded one-minute client-tick history, not an exhaustive persistent heatmap.

Map colors refresh four times per second and equal-color cells draw as row spans instead of one rectangle per block every frame. Decorative farm tiles are cached at the same rate and capped at the nearest 192; 3D overlays are skipped behind menus and away from the saved farm. Automatic plot scans defer while far from the plot, and inactive farm glow scans defer unless an inspector/menu needs them. Only display/background discovery work is throttled: live collision and route safety checks are unchanged.

### Merchant workflow

Merchant accepts a typed income threshold (for example `750M` or `1.2Q`); press **Apply** to validate and save it. Invalid drafts keep the previous threshold. Denominations follow the [supplied server spreadsheet](https://docs.google.com/spreadsheets/d/1IZOq_b3Kec-gA_5P1ZKS8bPAr-Qv2y03UnkJ6cSuY5g/edit?gid=0): K, M, B, T, Q, Qi, Sx, Sp, Oc, No, Dc, UD, Dd, Td, Qad, Qid, Sxd, Spd, Ocd, Nod, V, UV, DV, TV, QdV, QiV, SxV, SpV, OcV, NoV. Each step is 1,000×. Historical QD/QN/D/AD/ID/VG aliases still parse without changing saved amounts. Higher unverified suffixes are rejected.

The rare budget is removed. NPC claims on this server are free, so verified offers may have no price line, `Cost: FREE`, or an explicit zero cash price. Positive or ambiguous prices are skipped. Top-row offers still require identifiable NPC/plot/income lore, one supported left-click purchase instruction, and inventory space. Panes and navigation/control items are excluded. Each identified offer gets one attempt per workflow, followed by a stable matching inventory-increase check. Unsupported or unverified offers are never blindly retried. Confirmed rares are protected from Salvage and can always be placed regardless of the normal threshold; unavailable plots leave them kept. Live tooltip compatibility still needs testing.

Workflow shows the merchant timeline, current waiting reason, confirmed rare claims and placement queue. A compact HUD step indicator remains visible during the workflow. Safe cancellation leaves uncertain inventory/cursor contents available for inspection and suppresses automatic restarts until a manual merchant run. It does not promise unattended recovery from every server/menu failure.

### Freehand routes

Open **Settings → Draw custom routes** (or **Routes** in the sidebar). Choose Farm or Mine, hold the left mouse button and draw directly on the 2D map. **Closed loop is the only mode.** Releasing the mouse connects the exact release point straight back to the drawing's start, even across a large gap. Only the drawn portion is smoothed; no offset return lane or extra turnaround is generated. Leave room for the entire preview, including the straight closing line. The map uses one aspect-preserving transform for drawing, exclusions and rendering. Older route saves retain their names and strokes and use the same closed-loop behavior. A single straight stroke with no enclosed area still needs redrawing.

Name and save multiple phases, then enable **Use drawn phases** for that macro. Saved routes are scoped to world, macro and map bounds. The phase-duration slider sets a 30–180-second base with ±25% timing variation. Mine phases switch after the current join/target pass; farm phases switch at lap boundaries. Multiple phases avoid immediate repeats. Single phases can repeat. Built-in routes remain the entry/recovery fallback and original farm center routes are never overwritten.

Red previews cannot be saved: the complete smoothed path and straight closing line must clear the mapped edge buffer and exclusions (five blocks plus extra center clearance in the mine; three around the farm footprint). Live obstacles, loaded chunks and feasible joins are checked again when running; the mine also predicts complete laps in both directions. If a new phase cannot be joined safely, the current route is retained. Mine custom candidates are checked one at a time to avoid validating all saved routes in one tick. Drawn phases retain heading/pitch easing but suppress lateral wander so variation does not pull them off the drawn line. Geometry is smoothed on release, not every render frame.

The controller approach is consistent with [Nav2's regulated pure-pursuit guidance](https://docs.nav2.org/rolling/configuration_and_development/configuration_guide/controller_plugins/configuring_regulated_pp/): speed-dependent lookahead and predicted collision checks. This is a motion-control reference, not a guarantee of game-server behavior or detection avoidance.

The custom dark dashboard replaces Cloth Config. Overview, Harvester, Mine, Egg Hatcher, Merchant, NPC Plot, Statistics, and Settings pages group macros separately from shared tools. Navigation and page content scroll independently on shorter windows. Each macro has its own start/stop controls and settings; Overview also provides direct page shortcuts. Movement values use draggable sliders. Controls refresh when module state changes, and starting another macro is blocked while Merchant owns the workflow.

### Mine harvesting

The Mine Harvester uses the verified Sales mine: interior X **321–401**, Z **1528–1608**, floor block Y **119** (standing feet Y 120). The downloaded floor is cobblestone, but its live material can change freely. The waxed cut-copper stair ring at X 320/402 and Z 1527/1609 is a permanent boundary, never an attack target. `/mine` arrival is checked near 431.271, 115, 1527.239; the module lands if necessary, walks up the slab ramp on a slight angle, and smoothly bends diagonally into the north mining lane without two stop-and-pivot corners. It enables flight inside with vanilla double-tap Space, then uses brief one-tick Shift descent pulses, at least six ticks apart. Actual floor collision height, flight speed, and remaining downward momentum determine whether another pulse is safe. Once settled 0.2–1.8 blocks above the floor, mining begins; it does not seek an exact Y or hold Shift to the ground. Descent is bounded to three seconds and stops safely if low flight cannot be confirmed. No entry-distance slider or manual anchor is needed for this map.

The entire ring must be loaded and verified before mining begins. Permanent geometry excludes a five-block edge buffer, with another two-block route inset. Loaded obstacles and unknown chunks are separate layers: ordinary ore replacement and temporary regeneration air cannot erase the route. Live obstacle probes inflate the player's body horizontally by one block. The local collision layer refreshes every half-second, and each proposed movement is checked against current loaded collision shapes.

Mine navigation reuses the farm's easing and restrained variation but has one shared predictor for route following, target passes, joins, and safety checks. It passively estimates cruise speed and momentum from observed movement, excluding large position corrections. Wider perimeter corners and momentum-dependent look-ahead initiate turns earlier. Joins project ahead along a route segment rather than chasing the nearest vertex. Each input is checked over 24 predicted ticks; the old straight-ahead veto is removed. Predictions remain approximations, not guarantees of server behavior.

The Mine page offers an **Automatic mix** or preferred Zamboni passes, long ovals, back-and-forth, perimeter, cross-cuts, or reversals. Mine figure eights are removed; saved figure-eight selections migrate to Zamboni passes (old varied-loop selections become long ovals). Automatic mix prefers end-to-end rounded passes, varying their axis, lane width, lateral position, inset, and starting point without self-crossings or tight hairpins. Perimeter/cuts/reversals remain fallbacks. The default change interval is **45–75 seconds** (60 seconds ±25%); drag the interval slider to choose a 30–180 second base. Automatic mix avoids repeating the previous style. The loop-variation slider controls additional whole-curve inset/stretch; zero does not disable preset lane/axis selection or the separate Natural movement setting. The proven entry loop is joined first, then the selected style is applied. Changes wait until the current join or target pass finishes. Complete predicted laps in both directions and the transition corridor must fit before switching. Unsafe variety is skipped, retaining the existing route and retrying after ten seconds. Farming's separate figure-eight option is unchanged.

Mining shares the farm's smooth heading/pitch easing, restrained lateral drift, and detached mouse-controlled chase camera; the camera never steers the actual mining aim. Natural movement, world overlay, and camera toggles on the Mine page are the same shared settings used by farming. Mine momentum prediction, wide turns, and mandatory five-block edge buffer remain mine-specific safety limits. The farm-only turn-speed/look-ahead/clearance controls do not weaken those limits. Local recovery first looks for a clear forward join without replacing the saved route. If necessary, it releases movement and realigns while hovering. Local retries are bounded; `/mine` is the fallback. **P** or **Pause route** pauses active mining; resuming tries to rejoin the retained route after settling and aligning, using `/mine` only if a safe local return is unavailable. Manual pause is unavailable during startup, recovery, or Merchant ownership. Only vanilla level-loading screens are tolerated during the bounded expected teleport phase; other screens stop the module and report their class and phase. Merchant can pause/resume mining independently. H or Shift stops the mine. The original camera is restored on pause, merchant handoff, stop, or disabling detached camera. This map is specific to the supplied layout, not an automatic detector for arbitrary mines.

World overlays show the cyan mine boundary, amber five-block safe-area boundary, current loop, orange join corridor, green predicted movement, nearby orange obstacle boxes, pink target pass (including its through-point), and the exact red block under active attack. Ignored fossils turn gray. Loaded-world previews can show these bounds without starting the macro; unverified saved-layout previews stay in the menu. Rendered obstacles are limited to 96 nearby cells to bound overhead. The route selector, sliders, and shared visual controls save with the existing config.

Reachable fossils take priority over ice regardless of relative distance score. Ice is selected only when there is no safe eligible fossil; an already committed pass finishes before retargeting to avoid abrupt reversals. Built-in route variants rank fossil coverage first, then long-pass shape, then ice coverage and recent coverage. This reuses cached target positions without another whole-area scan. Custom phases keep their drawn geometry and still use fossil-first interception. Neither target type overrides clearance or ignore rules. Target selection predicts the approach, closest pass, six-block exit runway, and rejoin before committing. Close-range pitch anticipates movement while easing toward the exact floor cell. A missed pass returns to the route, then checks the target about two seconds later; two observed misses ignore that block for the session. Risky predictions receive a cooldown. Only an exact local block-break event counts as harvested; disappearing glows do not. Ice mined during ordinary route coverage also counts in the ice total.

### NPC placement

NPC placement closes the merchant container, opens the actual player inventory, and uses ordinary left-click pickup/place steps rather than number-key swaps. Each expected source/hotbar/cursor state must stay stable for 12 ticks before the next click. A full hotbar moves the displaced item back into the vacated source slot, protecting tool slot 1. Unchanged rejected clicks wait four seconds before a bounded retry; unknown cursor states stop without closing the inventory or resuming the interrupted macro. After closing, it checks the held NPC again for a further second before walking, and checks it again while walking. Normal completion restores hotbar slot 1. These are paced state checks, not a claim of an explicit server acknowledgement; live server behavior still needs testing.

### Mine diagnostics

The Mine page includes a minimap, live route and prediction, target/miss statistics, and recent recovery decisions. **Preview route** performs no movement or attacks; if chunks cannot be verified it is explicitly a saved-layout-only preview. **Export diagnostics** saves the last 2,400 input samples and 20 navigation events to `config/cropium/mine-diagnostics-last.csv`; stops also save this bounded last-run file. It contains coordinates and movement decisions, not chat or inventory contents. To replay its model calculations with Java 25:

```powershell
java -cp build/libs/cropium-1.0.0.jar com.salesfarm.croppilot.MineTrace "path/to/mine-diagnostics-last.csv"
```

Replay checks predictor consistency and the fixed edge buffer; it does not emulate the server or reconstruct historical live obstacles. `check` runs offline navigation, both-direction/momentum, blocked-pass, loading-guard, trace-replay, flight-descent, and existing movement/workflow checks.

### Egg Hatcher

The Egg Hatcher runs `/egg`, walks to the configured egg stand, selects one of the 18 egg tiers, enables auto-delete only for Common through Epic pets, verifies that Auto Egg is ON, and starts the selected 3- or 9-egg hatch. When chat reports that pet storage is full, it opens `/pets`, visits forge machines in order up to the selected maximum tier, then returns to the chosen egg and resumes.

Advanced values remain in `config/crop-pilot.json`; existing settings and saved field data are preserved.

## Glowing plants

Cropium inspects loaded vanilla glowing entities across the imported farm and maps stationary ones to scanned crop cells. Enable the Glow Inspector in the dashboard to see the nearest glowing entity's type, network id, position, crop mapping, and routing status. Unmapped entities remain visible to the inspector, which helps identify servers that represent the effect differently. Minecraft does not send distant entities outside the loaded area, so the perimeter route doubles as a discovery patrol.

Targeting stays route-first. As the active route approaches a glowing plant's nearest safe point, Cropium validates the direct corridor, leaves the line, and aims through the plant rather than stopping at its center. A/D supplies most of the correction, small dynamic yaw changes handle alignment, and close-range yaw and pitch converge on the crop for the break. A target is not considered harvested until a matching block break is confirmed; missed passes return to the route and are checked afterward. Eligible targets can be retried on a later pass, with repeatedly missed targets ignored. Green outlines mark tracked plants; the active target, outbound line, and rejoin line are highlighted.

Every one to two minutes the perimeter can switch among five map-validated variations: a direct cross-farm cut, a full-speed reversed perimeter, a large figure eight, a preset back-and-forth corridor, or a randomized weave through the embedded safe routes. Timed patterns run for one to two minutes, and every candidate still validates the crop footprint, remembered obstacles, and player clearance before committing.

## Saved fields

- `/cropium profile save <name>`
- `/cropium profile load <name>`
- `/cropium profile list`
- `/cropium profile delete <name>`
- `/cropium scan`
- `/cropium anchor`

Profiles, scans, and learned obstacles remain in `config/crop-pilot-fields.json` for compatibility.

The Sales Minehut server and its downloaded world automatically load the embedded irregular farm map at its recorded anchor. No corner selection is required there. From any collision-free crop position, Cropium joins a visible high-clearance route directly or finds a crop-only path around mapped holes and obstacles.

## Runtime behavior

The HUD and Statistics page report BPS, total blocks, blocks/hour, confirmed shiny crops or fossils, and shinies/hour. Hourly rates use elapsed session time, including merchant/utility pauses, freeze when stopped, and reset on a new start. Shiny counters require a locally confirmed break at the exact tracked block, with a short duplicate-event cooldown; a disappearing glow or nearby ordinary block alone does not count. Every locally confirmed block break counts toward total blocks regardless of type or position. The optional inspector adds glow details.

After a menu closes, shared input handling leaves attack released for one game tick before the farming/mining controller holds it again. This clears vanilla's menu-induced attack suppression when resuming from Merchant without synthetic attack packets or interfering with egg-hatcher clicks.

If Merchant closes while Minecraft is unfocused, vanilla leaves the mouse uncaptured. Active crop/mine harvesting may still continue native block breaking, but only with no screen/overlay or utility workflow, the player inventory active with an empty cursor, attack held, and a permitted target/tool. It never captures the desktop cursor or synthesizes extra clicks. Returning focus rearms attack once because vanilla mouse capture also resets its attack suppression. Watchdog stops report their reason, last target, held input, and mouse-capture state in chat; stalled-mining and collision safeguards remain enabled.

Merchant keeps the interrupted macro paused while its menus are open. A clicked menu icon may temporarily appear on the cursor due to vanilla's local prediction: Merchant waits up to three seconds for that exact icon in the same menu to clear, without repeating the click. A different cursor item or a timeout leaves the inventory untouched and the macro paused for inspection. Unexpected menus still stop a running harvester, but do not cancel one that is already paused.

During active farming/mining, attack stays held through air gaps and crop regrowth. The block-attack callback checks the actual target against the saved work area, layer, and exclusions, instead of releasing input based on the previous render hit. Farm targets may use custom server block types; mapped obstacles, the ground below the crop layer, and stairs remain protected. Run `.\gradlew.bat check` for the offline behavior tests and client source-contract checks; these supplement, not replace, a live-server test.

A five-second input watchdog checks active farming and mining. If break input is missing or there have been no confirmed breaks, it rearms the normal held input with one released game tick. Healthy mining is not interrupted. It is disabled during menus, merchant work, startup, pause, and recovery, and only rearms while a permitted block is under the crosshair. Mine watchdog interventions appear in the diagnostic history; existing safety stops remain enabled.

Obstacle checks use vanilla collision shapes plus persistent learned obstacles. Safety defaults stop on an exact username mention, an unexpected screen, a world or dimension change, loss of flight, or repeated low-BPS windows. Cropium disables pause-on-lost-focus only while running and restores the original option afterward.

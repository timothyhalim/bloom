# Bloom UI spec (proposal, v3.3)

Status: for owner review. Nothing here is implemented. Mockup: `design/mockup.html` (open on a phone, tap through).
Builds on M0 (`MainActivity.kt`): the full-screen `PhotoView` stays as the canvas; the `BottomAppBar` with Open/Export is replaced by the floating bar, Open moves to Home, Export moves to the top bar. The existing `SnackbarHost` stays as the error channel.

In one line: a full-height photo, one floating bar that is the tool list, then the tool, then that tool's mask editor, with one slim row above it.

v3.3 rule: **the bar holds what you edit, the rows above hold how.** The bar's centre lists only modes or parameters. The selected item's slider, toggles, actions and Reset sit in the row above; a second slim chip row is added only when they do not fit.

## 1. Principles

| # | Principle | What it means in practice |
|---|---|---|
| 1 | Photo first, neutral chrome | The photo fills everything below the top bar. Tool chrome is about two rows tall: one floating bar and one slider row. All chrome is opaque neutral gray with rounded corners; nothing is translucent or tinted. Anything under the chrome can be panned clear of it. |
| 2 | One bar, three states; what below, how above | The same floating bar shows the tool list, the active tool, or that tool's mask editor. Its centre lists only what you edit (parameters, modes, brushes, step types). How you edit it (slider, toggles, actions, Reset) is in the row above. Never more than two rows above the bar, plus a prompt field in AI; `More` is the only thing that adds further rows. |
| 3 | The mask belongs to the layer | No selection tool, no global selection. Every layer-making tool has a Mask button; the mask is a list of add/subtract steps saved with that layer. |
| 4 | One edit, one layer, top-down | Apply creates exactly one layer. Each layer changes everything below it. The stack is always visible in the rail. Nothing is baked. |
| 5 | Free is complete, cloud is a quiet option | No banners, locks or upsell dialogs. Cost and place appear on the button that runs the action. |

## 2. Navigation and screen map

```
Home ──Open photo──▶ Editor ──Export──▶ Export sheet
 │                    ├─ Top bar: Back · Undo · Redo · Compare (hold) · Export
 │                    ├─ Floating bar
 │                    │    ├─ tool list:  Color · Detail · Liquify · Crop · AI
 │                    │    ├─ tool mode:  [tool icon = back] [modes or parameters ⇄] [Mask] [✓ Apply | run button]
 │                    │    │               + control row above: slider, toggles, Reset (+ one slim chip row when needed)
 │                    │    └─ mask mode:  [mask icon = back] [Add/Remove · step types ⇄] [Invert] [✓]
 │                    │                    + steps row above the selected step's settings row
 │                    ├─ On canvas: layer rail ─tap thumbnail─▶ layer bubble · navigator (when zoomed)
 │                    └─ Overlays: Consent sheet, Job pill, Report sheet
 └─ Settings ─▶ AI providers | Bloom Cloud | On-device models | Privacy | Appearance | Export defaults | About
```

| Screen / state | Content | Notes |
|---|---|---|
| Home, first run | Logo, one line of copy, `Open photo` CTA at the bottom, privacy line, Settings icon | No onboarding carousel. |
| Home, returning | Same, plus a 3-column grid of recent projects above the CTA | Needs saved projects. If projects are out of scope for now, ship first-run state only. |
| Editor, tool list | Top bar · full-height photo · layer rail · floating bar with 5 tools | Back with unsaved work: dialog `Keep project / Discard / Cancel`. |
| Tool mode | The bar transforms in place (tables below); the selected item's controls float in one or two rows above it | The pending layer shows dashed in the rail. |
| Mask mode | Same bar with Add/Remove and the step types; above it the steps row and the selected step's settings row | Photo shows the red tint. ✓ or the left slot returns to the tool; the photo then shows the mask's edge only. |
| Export sheet | Format, quality, size, remove location, size estimate, Share / Save | Quality hidden for PNG. |
| Settings › AI providers | One row per capability (Subject/Background masks, Erase, Fill, Background, Upscale, Ask) → provider radio list. `Add provider` | Reached from Settings or from `AI settings…` in any run-button menu. Provider forms: fal key · IOPaint URL · ComfyUI URL · OpenAI-compatible (base URL, model, key), each with `Test connection`. |
| Settings › Bloom Cloud | Sign in, credit balance, get credits / subscribe, usage list | The only place that sells anything. |
| Settings › Privacy | Reset consent choices · `Hide cloud features` switch | The switch removes cloud entries from every run-button menu. |
| Settings › On-device models | Name, size, delete | Models download on first use. |
| Settings › Appearance | Theme (Dark default / Light / System) · canvas background (Black / Dark / Mid gray / Light) | |
| Consent sheet | What is sent, where, what is kept, cost and balance, `Don't ask again for <provider>`, `Not now` / `Send and generate`, `Use a different provider` | Shown once per provider for cloud runs, and again if the destination changes. Never for on-device. |

### Tool mode, per tool

| Tool | Bar centre (what) | Rows above the bar (how), top to bottom | Right slot | Result |
|---|---|---|---|---|
| Color | Exposure · Contrast · Highlights · Shadows · Temp · Tint · Saturation · Vibrance · Curves | 1 row: slider + value + Reset (for that parameter). `Curves` swaps it for a compact graph (channel chips RGB / R / G / B, 120dp graph). | Mask · ✓ | Color layer |
| Detail | `Blur` · `Sharpen` | Blur, 1 row: `Gaussian` / `Lens` toggle + Amount slider + Reset. Sharpen, 2 rows: chip row `Amount` `Radius` `Clarity`, then that parameter's slider + Reset. | Mask · ✓ | Blur layer or Sharpen layer, never "Detail" |
| Liquify | `Push` · `Bloat` · `Pinch` · `Twirl` · `Restore` | 1 row: `Size` and `Strength` chips + one slider for the selected one + `More` (Softness) | Mask · ✓ | Liquify layer |
| Crop | `Crop` · `Straighten` · `Rotate` · `Perspective` · `Scale` | Depends on the mode (table below) | ✓ (no Mask) | Document transform, no layer; the rail does not change |
| AI | The mode chip only (opens the mode menu) | Depends on the mode (table below) | Mask (Erase, Fill) · run button in place of ✓ | Depends on the mode |

### AI modes

| Mode | Rows above the bar, top to bottom | Run button | Needs a mask | Result |
|---|---|---|---|---|
| Erase object (default) | 1 row: Size slider + `More` (Softness, Flow) | `Erase / on device ▾` | Yes. Painting on the photo paints the mask directly. | Retouch layer |
| Generative fill | Prompt field · Size slider + `More` | `Generate / 2 credits ▾` | Yes, same as Erase | Fill layer (tagged AI) |
| Upscale | 1 row: `2×` `4×` + "3000 × 4000 → 6000 × 8000 px · no new layer" | `Upscale / on device ▾` | No. Brush and Mask are hidden. | No layer: the document is resampled (one undo step) |
| Replace background | Chip row `Blur` `Color` `Image` `Prompt`, then one row for the source: Amount slider + Reset, color swatches, an info line, or the prompt field | `Replace / on device ▾` (Prompt: a generation provider) | No. Uses the Subject mask, found on device. | Background layer |
| Ask | Text field; after asking, a checklist of the layers it will create above it | `Ask / my server ▾`, then `Apply / n layers` | No | One normal layer per ticked step |

In AI, running is applying: there is no separate ✓. Each mode's menu lists only providers that support it, plus `AI settings…`.

### Crop modes

| Mode (bar) | Rows above the bar, top to bottom | On the photo |
|---|---|---|
| Crop | Chip row: `Free` `Original` `1:1` `4:5` `3:2` `16:9` · `Portrait` / `Landscape`. Then an info row: resulting size ("3000 × 3000 px") + Reset. | Crop rectangle with corner and edge handles; outside is dimmed; rule-of-thirds grid while dragging. Drag inside to move it. A locked aspect holds while resizing. |
| Straighten | 1 row: Angle slider (±45°) + `Auto` + Reset | Fine grid while the slider is held. The image is scaled up just enough that no corner is empty. |
| Rotate | 1 row: `90° left` · `90° right` · `Flip H` · `Flip V` + Reset. Steps, not a slider. | – |
| Perspective | Chip row: `Vertical` · `Horizontal` · `Free` · `Auto`. Then the slider (±100) for Vertical or Horizontal + Reset; in `Free`, an info line + Reset. | `Free`: four corner handles to drag. |
| Scale | 1 row: Scale slider (50–150%) + `Fill edges` toggle + Reset | With `Fill edges` on, the image is enlarged to cover the frame. Off: empty areas show a checkerboard. |

Rules:

- Crop is a document-level transform: one per document, re-editable, applied before the layer stack. It is not a layer and does not appear in the rail.
- A mode chip gets a dot when that mode is changed from its default, like any changed value.
- Reset is an icon button at the end of the mode's control row. It resets the open mode only.
- `Auto` in Straighten and Perspective runs on device.

### Layout by window size

| Window | Layout |
|---|---|
| Phone portrait (width < 600dp) | As mocked: top bar, full-height photo, bar floating at the bottom with its rows above it, rail on the trailing edge. |
| Phone landscape (height < 480dp) | The bar and its rows keep their shape but are capped at 420dp wide and sit at the bottom-trailing corner. Rail moves to the leading edge. Sheets become trailing side sheets. |
| Tablet (width ≥ 840dp) | Bar capped at 480dp, centred at the bottom. Sheets become dialogs, max 560dp wide. |

Use `BoxWithConstraints` for the three breakpoints; no adaptive library needed.

### Canvas gestures

| Gesture | Outside brush contexts | Brush contexts: mask mode, Liquify, AI Erase / Fill |
|---|---|---|
| One finger | Pan | Always paints (or drags a gradient handle; or one tap samples a color while the eyedropper is armed) |
| Two fingers | Pinch to zoom (Fit to 800%) and pan | Same, everywhere. A second finger landing ends the stroke. |
| Double-tap | Fit ↔ 200% at the tapped point | Off |
| Compare | Hold the Compare button in the top bar | Same: it works in every tool |

In Crop, one finger on a handle or inside the rectangle edits the crop; elsewhere it pans. Two fingers pinch and pan as everywhere.

### Layer rail gestures

| Gesture on a thumbnail | Result | Decided by |
|---|---|---|
| Tap | Open the layer bubble | No movement |
| Vertical drag | Scroll the rail | First 8dp of movement are mostly vertical |
| Horizontal drag past 24dp | Hide or show that layer | First 8dp of movement are mostly horizontal |
| Long-press 400 ms, then drag | Reorder | No movement during the press |

`Photo` and a pending layer cannot be hidden or reordered.

### Pan limits under floating chrome

| Case | Rule |
|---|---|
| Photo at Fit fits in the area above the floating chrome | Centred in that clear area. |
| Photo at Fit is taller than the clear area (many rows open) | It does not shrink. It rests at the top and can be panned up until its bottom edge sits just above the chrome. |
| Zoomed in | Normal edge limits, except the bottom limit is the top of the chrome, not the screen bottom. |

## 3. Masks

A mask is an ordered list of steps, evaluated first to last, starting from empty.

| Operation | Math | Chip |
|---|---|---|
| Add | `mask = max(mask, step)` | `+ Name` |
| Subtract | `mask = mask × (1 − step)` | `− Name` |

| Step type | How it is added | Main setting (row above the bar) | In `More` | Stored as |
|---|---|---|---|---|
| Brush | Paint on the photo. Consecutive strokes with the same operation merge into one Brush step. | Size | Softness, Flow, Feather | Stroke paths + brush settings |
| Subject | Tap `Subject` | – ("found on device") | Feather | AI mask bitmap, cached |
| Background | Tap `Background` | – | Feather | AI mask bitmap, cached |
| Gradient | Tap `Gradient` | `Linear` / `Radial` toggle; handles are on the photo | Feather | Type, centre, width, angle |
| Color | Tap `Color`, then tap the photo with the eyedropper | Sample swatches, `+` (one more sample), Range | Smoothness, Feather | Sampled colors, range, smoothness |
| Luminance | Tap `Luminance` | 4-handle strip in place of the slider, plus a preset button that cycles Shadows (0–25%, fades to 40%; the default) / Midtones (40–60%, fades 25–75%) / Highlights (75–100%, fades from 60%) | Feather | Four percentages |

Rules:

- The steps show as chips in their own slim row above the settings row; the bar holds only `Add` / `Remove` and the step types.
- The `Add` / `Remove` toggle sets the operation of the **next** step, whatever its type. In Remove mode, tapping `Subject` adds `− Subject` and painting adds `− Brush`. Every step-type chip shows the current sign.
- Each tap on a type adds a new step. Two gradients, or Subject and Background together, are allowed.
- Example: `+ Gradient · − Subject` is the gradient with the subject cut out.
- `Invert` is a global toggle applied after all steps. `Clear mask` (in `More`) removes all steps and Invert.
- `Copy mask from…` (in `More`) replaces the steps with a copy of another layer's steps.
- Steps are stored non-destructively as parameters, so every step stays editable and can be switched between + and −, hidden, deleted or reordered at any time.
- **Sampling source:** Color and Luminance steps read the composite of the layers **below** the layer that owns the mask (recommended over the original photo, so a "shadows" mask follows earlier exposure changes). They are recomputed when anything below changes. Subject and Background are computed from the same composite once and cached; they refresh on request.

## 4. Key flows

**A. Recolor the shadows, but not the subject**
1. Tap `Color`. The bar becomes the Color tool; a dashed Color thumbnail appears in the rail.
2. Tap `Mask` (right slot). The bar switches to mask mode: `Add` toggle and the six step types.
3. Tap `+ Luminance`. A steps row appears above with `+ Luminance` selected; the row under it shows the luminance strip set to Shadows; the shadows turn red on the photo. Drag the handles or tap the preset button if needed.
4. Tap the `Add` toggle so it reads `Remove`, then tap `− Subject`. The steps row reads `+ Luminance · − Subject`; the subject drops out of the tint.
5. Tap ✓. The bar is back on Color; the photo shows the mask's edge only; the Mask button shows a thumbnail and "2".
6. Tap `Temp` in the bar, drag the slider above it. Tap `Tint`, drag. Preview is live, inside the mask only.
7. Tap ✓. The Color layer is created with that mask.

**B. Darken the sky around the subject**
1. `Color` › `Mask` › `+ Gradient`. Drag the handles on the photo so the fade ends at the horizon.
2. `Add` → `Remove`, then `− Subject`. Steps row: `+ Gradient · − Subject`.
3. Fix an edge by painting (one finger). Pinch with two fingers to zoom; the loupe shows what is under the finger.
4. ✓, select `Exposure`, drag down, ✓.

**C. Erase an object**
1. Tap `AI`. The mode chip reads `Erase object`.
2. Paint over the object. This paints the layer's mask (red tint). For a precise area, tap `Mask` and use steps.
3. Tap `Erase / on device`. Job pill shows progress with Cancel. The Retouch layer appears in the rail.
4. Paint and erase again, or undo. The left slot returns to the tool list.

**D. Liquify**
1. Tap `Liquify`. Pick `Push`, `Bloat`, `Pinch`, `Twirl` or `Restore` in the bar.
2. In the row above, tap `Size` or `Strength` and drag the slider. `More` holds Softness. Optional: `Mask` to protect everything outside it.
3. Drag on the photo. Undo steps back one stroke.
4. ✓ keeps a Liquify layer. It warps everything below it.

**E. Generative fill**
1. `AI` › mode chip › `Generative fill`.
2. Paint the area, or build it in `Mask`. Type a prompt in the row above the slider, or leave it empty to fill from surroundings. The run button stays disabled until there is a mask.
3. The run button reads `Generate / 2 credits`. To run it elsewhere, tap its caret: Bloom Cloud, own key, own server, `AI settings…`.
4. First cloud run for this provider: consent sheet. `Send and generate` continues; `Not now` returns with nothing sent.
5. Job pill with Cancel. The photo stays zoomable; the bar is locked.
6. The bar becomes the result bar: retry · report · "Result 1 of 1, AI generated" · `Discard` · `Keep`.
7. `Keep` creates a Fill layer tagged AI; report stays available in its bubble.

**F. Natural-language edit (Ask)**
1. `AI` › mode chip › `Ask`. Type: "make it warmer and blur the background". Tap `Ask / my server`.
2. A checklist appears above the text field: `Color · Temp +18` and `Blur · Background · Lens 20`. Unknown requests are listed as "Can't do: …".
3. Untick steps if wanted. The run button now reads `Apply / 2 layers`; tapping it creates one normal layer per step.
4. Every created layer is editable by hand afterwards. One undo removes the whole plan.
5. Only the text and the list of available tools go to the LLM endpoint, not the photo. Masks such as "background" are built on device as ordinary steps. No consent sheet unless an image is ever attached.

**G. Straighten a building and fix its perspective**
1. Tap `Crop`. Tap `Straighten` in the bar, drag the Angle slider above it until the fine grid lines up with the horizon (or tap `Auto` on that row).
2. Tap `Perspective`, select `Vertical` in the chip row, drag until the walls are upright (or tap `Auto`). The Straighten and Perspective chips now carry a dot.
3. If edges look tight, tap `Scale` and lower it, or turn `Fill edges` off to see what is empty.
4. Tap `Crop`, pick `4:5` in the chip row, drag the rectangle into place.
5. ✓ applies the transform to the document. The rail does not change.

## 5. Components

| Component | Spec |
|---|---|
| Top bar | 56dp, docked. Back · Undo · Redo · Compare (48dp icon buttons) · spacer · Export (filled, petal shape, 40dp high, 48dp touch). Disabled Undo/Redo: outline color + `disabled` semantics. |
| Compare | Top-bar button. Press and hold shows the original with an `Original` tag at the top of the photo; release returns. Works in every tool, including while a brush is active. No gesture on the photo and no hint. With TalkBack it is a toggle. |
| Floating bar | 8dp from the side and bottom edges plus the bottom safe-area inset. 60dp high, 20dp radius, opaque `surface`, 1dp `outline` border. Three states: tool list, tool mode, mask mode. The swap between states is a 200 ms cross-fade in place. |
| Bar, tool list | 5 equal items: Color, Detail, Liquify, Crop, AI. 24dp icon over an 11sp label. |
| Bar, left slot | 54×48dp. Small back chevron + the tool's icon on a petal in `primaryContainer` (mask mode: the mask icon). Tool mode: tap cancels and returns to the tool list. If anything changed since the tool opened, a small bubble asks "Discard changes?" with `Keep editing` / `Discard`. Mask mode: tap returns to the tool, keeping the mask. System back does the same. |
| Bar, centre chip row | Holds only what you edit: Color's parameters, Detail's two filters, Liquify's brushes, Crop's modes, the AI mode chip, or (mask mode) Add/Remove and the step types. No dividers, no option chips, no actions. Fills the space between the slots; scrolls sideways inside the bar, never wraps, never scrolls the page. The selected chip is kept in view. Chips are 40dp high with a 60dp-tall touch area. |
| Parameter chip | Label only. Selected: `surfaceContainerHigh` fill + 2dp Pollen underline + its value after the label ("Exposure +35"). A changed value adds a small dot after the label, selected or not. Used in the bar (Color) and in chip rows above it (Sharpen, Perspective, Liquify's Size / Strength). |
| Choice chip | Outlined; the chosen one is filled `primaryContainer`. In the bar: Blur / Sharpen, liquify brushes, Crop modes (with a dot when changed), the AI mode chip (with a caret; opens a menu). In rows above: crop aspect, `Free`, `Fill edges`, 2× / 4×, background source. |
| Control row | The row directly above the bar: 48dp high, 16dp radius, same surface and border as the bar. It holds how the selected item is edited, in this order: optional leading toggle (e.g. `Gaussian` / `Lens`) · label (12sp) · slider · value (14sp tabular, with sign and unit) · the item's toggles and actions (`Auto`, `Fill edges`) · **Reset** (48dp icon button; resets this parameter or this mode) or `More`. Only one slider at a time. Everything must fit at 390dp; what does not fit moves to a chip row above. 4dp track, 22dp ring thumb; fill starts at the default (centre for ± ranges). Double-tapping the thumb also resets. `stateDescription` = value text; a11y actions step ±1. Built from M3 `Slider` with custom `thumb`/`track` slots. |
| Chip row | The optional second row, above the control row: one slim row (48dp) of chips that scrolls sideways. Used only when a mode has several sub-items: Sharpen's parameters, Crop aspects, Perspective's Vertical / Horizontal / Free / Auto, Replace-background sources, and the mask steps. |
| `More` expander | 48dp caret button at the end of the control row, in brush contexts only (mask mode, Liquify, AI Erase / Fill). Open: extra slider rows stack above the control row, plus one button row in mask mode (`Copy mask from…`, `Clear mask`). Closed by default; remembered per tool. It is the only thing that takes the stack past two rows. |
| Other rows | Where a control is not a slider: AI prompt field (48dp, one line), Ask checklist, Curves graph. Same floating style. |
| Mask button | Right slot of Color, Detail, Liquify and AI Erase / Fill. 48dp. No mask: half-circle mask icon. With a mask: tiny mask thumbnail + step count on a `primaryContainer` fill. Tap: the bar switches to mask mode for this layer. |
| Apply (✓) | Right slot, 48dp, Pollen check. Creates or updates the layer and returns to the tool list. |
| Run button | AI only, in place of ✓. Compact split button, 44dp high, petal fill. Main part: verb over a small second line that says where or what it costs ("Generate" / "2 credits"). Caret part (32dp) opens a menu above the bar: the providers that support this mode as a single-choice list (icon + name + cost, check mark on the current one), a divider, `AI settings…`. The choice is remembered per mode. Disabled while a needed mask is empty. |
| AI mode menu | Opens above the bar from the mode chip: Erase object, Generative fill, Upscale, Replace background, Ask, each with an icon and a check mark on the current one. The last mode used is remembered. |
| Bar, mask mode | Left: mask icon (back to the tool). Centre: `Add` / `Remove` toggle (one chip that flips; it sets the sign of every new step) and the step-type chips `± Brush`, `± Subject`, `± Background`, `± Gradient`, `± Color`, `± Luminance`, all always present. Right: Invert (toggle) · ✓ (back to the tool). `Brush` is selected by default: painting works at once. |
| Steps row | A chip row above the settings row, shown as soon as the mask has a step: `+ Gradient`, `− Subject`, `+ Brush`, sign as a + or − icon, in evaluation order. Selected: 2dp Pollen border; its setting shows in the row below and, for a gradient, its handles show on the photo. Hidden: dashed border + strike-through. Tap: select and open the step popover. Long-press 400 ms then drag: reorder. Scrolls sideways when the steps do not fit. |
| Step popover | Small bubble above the stack, tail pointing at the chip: `Make −` / `Make +` · `Hide` / `Show` · `Delete`. Tap outside closes it. |
| Gradient handles | Linear: two parallel lines and a dashed centre line; full beyond one line, fading to nothing at the other. Centre handle moves it; either outer handle sets width and angle. Radial: two circles; full inside the inner one, fading to the outer. Handles: 12dp dots, 48dp touch, white with black outline. A handle wins over painting when a touch lands on it. |
| Color step row | 28dp sample swatches · `+` (arms the eyedropper for one more tap) · Range slider · `More` (Smoothness). While the eyedropper is armed a hint reads "Tap the photo to pick a color" and one tap samples instead of painting. Distance is measured in a perceptual color space (OKLab) in the app. |
| Luminance strip | In place of the slider: a black-to-white strip, 12dp tall. Two ring handles above it bound the fully selected range; two smaller handles below it set where the fade ends; a thin bar under the strip previews the band. A preset button at the end shows Shadows / Midtones / Highlights (or the range when custom) and cycles on tap. Each handle has a 48dp-wide touch area; the half touched (above or below the strip) decides which pair moves. |
| Mask overlay | `#FF3B30` at 45% + lighter diagonal hatch + black-and-white dashed edge at the 50% line. Tint + edge in mask mode and in AI Erase / Fill; edge only in tool mode, so color is judged correctly. Never uses the accent color. |
| Brush cursor | Ring at brush size, white with black hairlines; rests where the last touch was. Size 4–200dp measured on screen (zoom in for finer work). One stroke = one undo step. |
| Loupe | 104dp circle, 2× zoom, shows photo + mask + brush ring. Only while a finger is down painting a mask. Top-left; drops to just above the floating rows on the leading side when the finger is within 150dp of the top-left corner. |
| Layer rail | The only layers UI. Column on the trailing edge, 8dp below the top bar, ending 8dp above the floating chrome. 36dp thumbnails on a 48dp pitch (48dp touch), topmost layer first, `Photo` last. No backing panel; each thumbnail is opaque `surfaceContainer` with a 1dp outline. Thumbnail = type icon; half-filled dot badge when the layer has a mask; strike-through when hidden. **Active:** 2dp Pollen border + 3dp side bar. **Pending** (tool open, not applied): 2dp dashed Pollen border, directly above the active layer. **Scrolling:** when the stack is taller than the space, the list scrolls; a 36×20dp arrow pill appears at the top and/or bottom edge only when there is more in that direction, and a tap scrolls by one thumbnail. **Gestures:** tap = open the bubble; vertical drag = scroll; long-press 400 ms then drag = reorder (the thumbnail lifts: 1.15× scale, shadow, haptic tick; the list auto-scrolls near an arrow edge; `Photo` stays last); **swipe left or right = hide / show the layer**: the thumbnail follows the finger by up to 10dp with resistance, the layer toggles once the drag passes 24dp (struck-through and dimmed at once, haptic tick), and the thumbnail snaps back on release. The toggle is one undo step. A toast "Color layer hidden · Undo" shows for 3 s the first three times only. Header pill (layers icon + count) collapses the rail to a 20dp tab. Fades to 25% while a finger is on the photo. |
| Layer bubble | Popover beside the tapped thumbnail, 236dp wide, tail pointing at it. Opaque `surfaceContainerHigh`, 1dp outline, 12dp radius. Rows: name + visibility toggle · Opacity slider · Blend menu (Normal, Multiply, Screen, Overlay, Soft light) · `Edit` (opens the owning tool with this layer's values) / `Edit mask` (opens the owning tool already in mask mode) / `Delete`. Tap outside closes it and does nothing else. Not offered for `Photo` or a pending layer. |
| Crop overlay | Rectangle with a 1.5dp white border; L-shaped corner handles and short edge handles, 4dp thick, 48dp touch. Outside the rectangle is dimmed 65% black. Rule-of-thirds lines while a handle or the rectangle is dragged; a 12×12 fine grid while the Straighten slider is held. Perspective `Free`: four 12dp corner dots (48dp touch) joined by a white outline. Checkerboard (two grays, 8dp squares) wherever the frame has no image and `Fill edges` is off. |
| Navigator | Bottom-leading, 8dp above the floating chrome. Whole image in a box up to 66×88dp, opaque, 1dp outline; the visible part as a white rectangle with black hairlines. Shown whenever the image is larger than the canvas, hidden at Fit. Drag or tap inside it to move the view. |
| Result bar | Replaces the bar after a generative action: retry · report flag · "Result n of m / AI generated" · `Discard` · `Keep`. |
| Credits | Cost is on the run button and in its menu. The balance is in the consent sheet ("2 credits. You have 12.") and in Settings › Bloom Cloud. No balance indicator in the editor. At 0 credits the run button reads `Get credits`. |
| Job pill | Floats above the chrome, 12dp side inset. Title (`Erasing…`), detail (`On device` / `Bloom Cloud · about 10 s` / `Downloading model 12 of 38 MB`), 4dp progress bar, `Cancel` always present. `liveRegion = polite`. Under 300 ms: nothing. Over 2 s: the pill. |
| Report sheet | Reasons (radio): Offensive, Sexual, Violent, Other + optional note. Sends prompt and provider; the image only if the user ticks `Include the image`. |
| Sheets | M3 `ModalBottomSheet`, 28dp top corners, drag handle, scrim. Used for Export, Consent and Report only. |
| Errors | Snackbar: cause + action (`Retry`, `Settings`). Example: "Can't reach your IOPaint server. Retry / Settings". |

### Layer model

| Rule | Detail |
|---|---|
| One edit, one layer | Color, Blur, Sharpen, Liquify, Retouch (Erase), Fill and Background are separate layer types. Upscale is not a layer: it resamples the whole document. Crop (all five modes) is one document-level transform, stored as parameters and re-editable; it never appears in the rail. |
| Top-down | A layer changes the result of everything below it. Sharpen above Blur sharpens the blurred result. |
| Insert position | A new layer goes directly above the active layer. |
| Mask | Each layer owns one mask: a step list (§3) plus Invert. It is made in that layer's tool, never carried over from another tool. `Copy mask from…` is the only way to reuse one. For Retouch and Fill layers the mask is the area that was erased or generated. |
| Reopening | `Edit` reopens the tool with the layer's values, `Edit mask` opens it in mask mode; ✓ updates the same layer. |

### Position rules

| Piece | Where | Yields to / avoids |
|---|---|---|
| Floating bar | Bottom, 8dp margins + safe area | – |
| Rows above the bar | Stacked above the bar, 6dp apart: the control row sits directly on the bar, an optional chip row above it, the AI prompt above that | At most two rows plus the prompt, unless `More` is open. The photo can be panned clear of them. |
| Layer rail | Trailing edge, from 8dp below the top bar to 8dp above the stack | Scrolls instead of running under the stack. Fades to 25% during a canvas gesture. Collapses to a tab. |
| Layer bubble | Left of the rail, tail on the thumbnail | Kept between the top of the canvas and the top of the stack. Closes when the rail scrolls. |
| Navigator | Leading edge, 8dp above the stack (above the job pill when one shows) | Hidden while a layer bubble, step popover or menu is open, and during a brush stroke. |
| Loupe | Top-leading, or leading just above the stack | Only during a stroke, when the navigator is hidden. |
| Menus and small bubbles (mode menu, provider menu, copy menu, step popover, discard prompt) | Open upward, 8dp above the stack. Mode menu and discard prompt at the leading side, provider and copy menus at the trailing side, step popover over its chip | One at a time; opening one closes the others and the layer bubble. A tap outside closes it and does nothing else. |
| Hint pill, job pill | Centred, above the stack | Hint shows only while the mask is empty; it hides during a stroke and while a job runs. |

Leading side: navigator and loupe. Trailing side: rail and its bubble. Bottom: bar, rows, menus. All mirror in RTL.

### Free vs cloud rules

| Capability | Default | If nothing is set up |
|---|---|---|
| Subject / Background masks, Erase, Upscale 2×/4×, all manual tools | On device | n/a |
| Replace background with blur, a color or an image | On device | n/a |
| Generative fill, background from a prompt, high-quality upscale | Last provider picked in that mode's menu | The run button reads `Set up…` and opens the menu: Bloom Cloud, own key, own server. No lock icon. |
| Ask (natural-language) | User's OpenAI-compatible endpoint or Bloom Cloud | Same as above. |

Offline: cloud entries in the menu show `Offline` and are disabled; on-device entries are unaffected.

## 6. Design tokens

### Color

| Role | Dark | Light | Contrast (text on surface) |
|---|---|---|---|
| canvas (behind photo) | `#0E0E0E` | `#DADADA` | – |
| surface / background (top bar, floating bar and rows) | `#171717` | `#FAFAFA` | – |
| surfaceContainer (sheets, pills, rail thumbnails) | `#1F1F1F` | `#F0F0F0` | – |
| surfaceContainerHigh (selected chip, bubbles, menus) | `#292929` | `#E6E6E6` | – |
| onSurface | `#EDEDED` | `#1A1A1A` | 15.3 / 16.7 |
| onSurfaceVariant | `#A8A8A8` | `#5C5C5C` | 7.5 / 6.4 |
| outline (control and floating-chrome borders, track) | `#757575` | `#757575` | 3.9 / 4.4 (≥ 3 for UI parts) |
| outlineVariant (dividers) | `#333333` | `#D0D0D0` | – |
| primary "Pollen" | `#FFC857` | `#7A5200` | 11.7 / 6.6 |
| onPrimary | `#2A1D00` | `#FFFFFF` | 10.7 / 6.9 |
| primaryContainer | `#4A3600` | `#FFDEA0` | – |
| onPrimaryContainer | `#FFE2A6` | `#261900` | 9.2 / 13.3 |
| tertiary (cloud icon only) | `#9ECBFF` | `#1F5F99` | 10.6 / 6.4 |
| error | `#FFB4AB` | `#BA1A1A` | 10.6 / 6.2 |
| scrim | `#000000` 55% | `#000000` 40% | – |
| maskOverlay | `#FF3B30` 45% | same | – |

```kotlin
val BloomDark = darkColorScheme(
    primary = Color(0xFFFFC857), onPrimary = Color(0xFF2A1D00),
    primaryContainer = Color(0xFF4A3600), onPrimaryContainer = Color(0xFFFFE2A6),
    secondary = Color(0xFFA8A8A8), onSecondary = Color(0xFF171717),
    secondaryContainer = Color(0xFF292929), onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFF9ECBFF), onTertiary = Color(0xFF003353),
    background = Color(0xFF171717), onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF171717), onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF292929), onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceContainerLowest = Color(0xFF0E0E0E), surfaceContainerLow = Color(0xFF171717),
    surfaceContainer = Color(0xFF1F1F1F), surfaceContainerHigh = Color(0xFF292929),
    surfaceContainerHighest = Color(0xFF333333),
    outline = Color(0xFF757575), outlineVariant = Color(0xFF333333),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    scrim = Color(0xFF000000),
    surfaceTint = Color.Transparent, // no accent tint on elevated surfaces
)

val BloomLight = lightColorScheme(
    primary = Color(0xFF7A5200), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDEA0), onPrimaryContainer = Color(0xFF261900),
    secondary = Color(0xFF5C5C5C), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E6E6), onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF1F5F99), onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFAFAFA), onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE6E6E6), onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF0F0F0), surfaceContainerHigh = Color(0xFFE6E6E6),
    surfaceContainerHighest = Color(0xFFDADADA),
    outline = Color(0xFF757575), outlineVariant = Color(0xFFD0D0D0),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    scrim = Color(0xFF000000),
    surfaceTint = Color.Transparent,
)

val CanvasDark = Color(0xFF0E0E0E)
val CanvasLight = Color(0xFFDADADA)
val MaskOverlay = Color(0x73FF3B30) // 45%
```

No dynamic color (Material You): wallpaper-derived hues would tint the chrome.

### Typography

System font (`FontFamily.Default`), no bundled font. Numeric values use tabular figures: `fontFeatureSettings = "tnum"`.

| Style | Size / line | Weight | Used for |
|---|---|---|---|
| headlineSmall | 28 / 36sp | Medium | Home wordmark |
| titleLarge | 22 / 28sp | Medium | Sheet titles |
| titleMedium | 16 / 24sp | Regular | Prompt text |
| bodyMedium | 14 / 20sp | Regular | Body, consent text, menu items |
| labelLarge | 14 / 20sp | Medium | Buttons, slider value, bubble title, run-button verb |
| labelMedium | 12 / 16sp | Medium | Chips, slider label, info rows |
| labelSmall | 11 / 16sp | Medium | Tool labels, bubble actions, rail count; run-button second line at 10sp |

```kotlin
val BloomType = Typography(
    headlineSmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)
```

### Spacing, size, shape

| Token | Value |
|---|---|
| Spacing scale | 4, 6, 8, 12, 16, 20, 24, 32dp |
| Floating chrome | 8dp from screen edges (+ safe area), 6dp between rows |
| Touch target | min 48×48dp |
| Icon | 24dp (14–20dp inside chips, rail, bubbles, menus) |
| Top bar / floating bar | 56dp docked / 60dp |
| Rows above the bar | 48dp each (Curves graph 170dp), 16dp radius, at most 2 plus the AI prompt (`More` excepted) |
| Bar slots | left 54dp, Mask and ✓ 48dp each, run button 44dp high |
| Chips in the bar | 40dp high, 10dp radius, 6dp gap |
| Layer rail | 36dp thumbnail, 48dp pitch, 36×20dp scroll arrows, 20dp collapsed tab |
| Layer bubble / small bubbles / menus | 236dp / 216dp / min 244dp wide, 48dp rows |
| Navigator / loupe | 66×88dp max / 104dp |

```kotlin
val BloomShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),  // slider track, navigator, luminance strip
    small = RoundedCornerShape(8.dp),       // thumbnails, swatches
    medium = RoundedCornerShape(12.dp),     // buttons, fields, bubbles, menus (chips in the bar: 10.dp)
    large = RoundedCornerShape(20.dp),      // floating bar (floating rows: 16.dp)
    extraLarge = RoundedCornerShape(28.dp), // sheet top corners
)
// Signature shape: primary CTA, Export, run button, and the tool marker in the bar's left slot
val Petal = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
val PetalSmall = RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomEnd = 14.dp, bottomStart = 4.dp)
```

The run button is one petal cut in two with a 2dp gap: the main part keeps the two leading corners, the caret part the two trailing ones.

### Motion

| Token | Value | Used for |
|---|---|---|
| `Fast` | 100 ms, `LinearOutSlowInEasing` | Press, toggle, bubble and menu open/close, thumbnail and chip lift |
| `Standard` | 200 ms, `FastOutSlowInEasing` | Bar state swap, rows appearing above the bar, `More` expand, rail fade, rail arrow scroll |
| `SheetIn` / `SheetOut` | 300 ms / 200 ms | Bottom sheets (M3 default) |
| `LongPress` | 400 ms | Start of reorder in the rail and among step chips |
| `Compare` | 0 ms swap | A fade would hide differences |

Only `animate*AsState`, `AnimatedVisibility` and `AnimatedContent`. No Lottie, no shimmer, no blur effects. Honor the system "remove animations" setting. Double-tap zoom snaps without animation in v1.

## 7. Identity

| Item | Direction |
|---|---|
| Name | Keep **Bloom**. Store listing: "Bloom Photo Editor" (the bare word is crowded). |
| Idea | Layers that open into a flower: four petals, each one a layer. |
| Accent | **Pollen** `#FFC857`. Warm yellow sits far from the red mask overlay and from the blue, green and magenta accents common in this category. Used at small sizes only. |
| Signature shape | The petal: a rounded rectangle with two opposite tight corners. Appears on primary buttons, the tool marker and the logo. Everything else uses plain rounded rectangles. |
| Logo / app icon | Four petal shapes rotated around a centre. Three in gray at 90 / 60 / 35% opacity (a layer stack), one in Pollen. Flat, no gradients. Adaptive icon: `#171717` background, mark in the 66dp safe zone, plus a monochrome layer for themed icons. |
| UI icons | Material Symbols Rounded, weight 400, 24dp, outlined. Copy the ~40 needed as vector drawables; do not add `material-icons-extended`. |
| Voice | Short verbs: "Open photo", "Erase", "Keep". Say where things run: "on device", "Send to Bloom Cloud?". No exclamation marks, no "magic". |

## 8. Accessibility checklist

- Every icon-only button has a `contentDescription`: left slot "Color. Back to tools" / "Color mask. Back to Color"; Mask button "Mask: none. Add" or "Mask: 2 steps. Edit"; Compare "Compare. Hold to see the original" (a toggle under TalkBack).
- All targets ≥ 48dp, including bar chips (40dp visual, 60dp touch), rail thumbnails (36dp), rail arrows (20dp tall visual), the collapsed rail tab (20dp), gradient and luminance handles (12–16dp visual), the run-button caret (32dp visual).
- Text contrast ≥ 4.5:1, control and floating-chrome borders ≥ 3:1 (values in the color table).
- State is never color-only: selected parameter = fill + underline + value; changed = dot; chosen choice chip = fill + `selected` semantics; step sign = + or − icon; selected step = thicker border; hidden step = dashed + strike-through; hidden layer = strike-through; cloud = icon + text; mask = tint + hatch + dashed edge; active rail layer = border + side bar; pending layer = dashed border; current mode or provider = check mark.
- The chip row is a horizontally scrolling list; TalkBack moves through chips in order and scrolls them into view.
- Slider rows announce label, value and unit, and step by a11y actions. Each luminance handle is a slider ("Shadows fade end, 40%").
- Canvas gestures have non-gesture paths through custom accessibility actions: `Zoom in`, `Zoom out`, `Fit`, `Pan left / right / up / down`. The navigator is decorative (`clearAndSetSemantics {}`).
- Rail thumbnails read "Color layer, has mask" / "Blur layer, not applied yet", with actions `Layer settings`, `Hide layer` / `Show layer`, `Move up`, `Move down`. The swipe has no other accessible form; the bubble's eye button and that action are the accessible path.
- Crop: each rectangle edge is adjustable by actions (`Move edge in / out`); aspect chips, Rotate buttons and all sliders work without gestures. Perspective `Free` corners have no non-pointer equivalent; Vertical, Horizontal and `Auto` cover it. Rail arrows are buttons ("Scroll layers down").
- Step chips read "Step 2 of 3: subtract Subject", with actions `Move earlier`, `Move later`, `Switch to add / subtract`, `Hide`, `Delete`.
- Brush painting, gradient handles and the eyedropper have no non-pointer equivalent; Subject, Background, Luminance presets, Invert, Clear and Copy mask build a mask without them.
- Font scale up to 200%: chips grow and the row scrolls further; the slider label may truncate, the value never does.

## 9. Open points for the owner

1. Liquify's five brushes and Crop's five modes do not all fit in the bar at 390dp (about 215–260dp of centre width); the last one or two scroll into view.
2. `Add` / `Remove` is one chip that flips, not a two-part segment, to save width. Every type chip shows the current sign.
3. Reset on a control row resets only what that row edits (one parameter, or the open Crop mode). There is no "reset everything" in a tool; leaving with the left slot and `Discard` does that.
4. In AI the run button replaces ✓, so running is applying. Erase commits each run to the Retouch layer; undo reverts it.
5. Compare uses a split-view icon and Mask a half-circle icon, so the two never look alike.
6. Tap-an-object masking is still out (removed in v3). It would fit as one more step type.
7. Invert applies after all steps, so painting with `Add` while Invert is on removes from what you see.
8. Consent sheet copy ("Deleted after the result is returned") must match the real Bloom Cloud and fal.ai retention terms before release.
9. Recent projects on Home assumes projects are saved on device. Not in M0.
10. Mockup only: the consent sheet appears for Generative fill only; `Copy mask from…` copies a canned one-step mask; Color and Luminance steps read the stand-in photo (not a composite) and color distance is plain RGB; Feather has no effect; Liquify shows only the ring; Erase and fill results are canned; Curves is static. Crop transforms are live but are dropped when you leave the tool (✓ included); both `Auto` chips set a canned value; with `Fill edges` on, Perspective `Free` handles do not track the enlarged image; the swipe toggle is not wired to Undo.

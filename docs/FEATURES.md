# Bloom feature inventory

Every user-visible feature and behaviour in the app today, as the checklist for automated tests.
Paths are under `app/src/main/java/dev/bloom/editor/` unless they start with `app/` or `cpp/` (`app/src/main/cpp/`).
Line numbers are as of commit `df876e7`.

Columns:
- **Test today**: `unit: Class.test` (JVM, `app/src/test`), `UI: Class.test` (Compose UI test on a device, `app/src/androidTest`), `GPU: test` (`app/src/androidTest/.../PipelineTest.kt`, on a device), or `none`. GPU tests marked *(model)* skip unless the model file was pushed to the device.
- **Testable how**: `unit` (JVM), `GPU` (instrumented, offscreen GL), `UI` (Compose UI test, instrumented), `manual`.
- **BROKEN (user report)**: reported by the user; see the notes in that row.

Spec references are to `design/UI.md` (v3.3) and `docs/PLAN.md`. Where the code differs from the spec, the row says `Spec:`; the full list is in [Differences from the spec](#differences-from-the-spec).

## 1. App start, first run, empty state

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Launcher icon: adaptive (navy gradient + sparkles background, flower foreground, white monochrome for themed icons), PNGs rasterized from design/icon_foreground.svg and design/icon.svg; Play store icon app/src/main/ic_launcher-playstore.png | app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml, AndroidManifest.xml | none | manual |
| Edge-to-edge window with dark transparent system bars | MainActivity.kt:15 | none | manual |
| One PhotoView created per activity, follows the lifecycle (pause/resume GL) | MainActivity.kt:16-17, PhotoView.kt:311-312 | none | manual |
| Crash reports (ACRA, both flavors): after a crash a dialog asks "Share the crash report?"; unanswered, it asks again on the next launch (reports under a minute old wait). Share opens the system share sheet with the report text; Don't share deletes it. No network sender, no server. Report holds only stack trace, app version name/code, Android version, brand, model, available/total memory, crash date | BloomApp.kt, app/src/main/resources/META-INF/services/org.acra.sender.ReportSenderFactory | none | manual (`am crash`) |
| First run (no photo): empty state shows "Bloom", tagline "Edit in layers. Everything runs on your phone." | ui/EditorScreen.kt:682-686 | UI: ProjectsTest.firstRunHomeHasTheTaglineAndOpenButtonsAndNoEditing | UI |
| Empty state is opaque, so a closed photo never shows through | ui/EditorScreen.kt:552-554 | UI: BackTest.backGoesFromMaskModeToTheToolToToolsToHome (Home shows after close; opacity itself manual) | UI |
| "Open photo" petal button opens the system photo picker, images only | ui/EditorScreen.kt:687-693, 497 | UI: ProjectsTest.openPhotoAsksThePickerForImagesAndOpensACopyAsANewProject | UI |
| "Open file (RAW: CR3, NEF, ARW, DNG…)" opens the document picker with `image/*` and `application/octet-stream` (CR3 shows as octet-stream) | ui/EditorScreen.kt:694-698, 439, 554 | UI: ProjectsTest.openFileAsksForImagesAndRawsAndCancelDoesNothing | UI |
| With no photo: Back, Undo, Redo, Compare and Export are disabled; layer rail, navigator and tool bar hidden | ui/EditorScreen.kt:638-665, 552-555 | UI: ProjectsTest.firstRunHomeHasTheTaglineAndOpenButtonsAndNoEditing | UI |
| Home has a Settings icon (top right, opens Settings, §31). Spec: privacy line; not built (the recent-projects grid is built, see §30) | ui/EditorScreen.kt:731 | UI: AiSettingsTest.addAProviderTestItSaveItAndPickIt | UI |

## 2. Open and load

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Opening decodes on IO thread, then replaces the photo and drops all edits, AI masks, depth and history | ui/EditorScreen.kt:430-436, 274-284 | UI: ProjectsTest.openPhotoAsksThePickerForImagesAndOpensACopyAsANewProject, UndoTest.openingAnotherPhotoClearsHistory | UI |
| Open failure shows snackbar "Can't open image: <reason>" and keeps the current photo | ui/EditorScreen.kt:434 | UI: ProjectsTest.aFileThatIsNoImageShowsWhyAndLeavesNoProject | UI |
| Cancelling the picker (null uri) does nothing | ui/EditorScreen.kt:431 | UI: ProjectsTest.openFileAsksForImagesAndRawsAndCancelDoesNothing | UI |
| Preview downsampled by power of two so the long side is ≤ 2048 | ImageIo.kt:29, 33-37, 59 | unit: MathTest.sampleSizeKeepsLongSideUnderMax | unit |
| Preview is made upright from EXIF rotation and flip | ImageIo.kt:64-69 | none | GPU (instrumented decode) |
| Wide-gamut photos (P3 etc.) are converted to sRGB on decode | ImageIo.kt:40, 60 | none | GPU (instrumented decode) |
| Undecodable file: "unsupported format" error | ImageIo.kt:62-63 | none | unit (Robolectric) / manual |
| Sources deeper than 8 bits stay RGBA_F16; their levels survive the pipeline | ImageIo.kt:39, gl/Pipeline.kt:42, 151-180 | GPU: sixteenBitSourceKeepsLevelsBetweenEightBitSteps | GPU |
| RAW detection: any MIME type not in the plain list (JPEG, PNG, WebP, HEIC/HEIF, AVIF, GIF, BMP) is tried as raw, if the native lib loaded | Raw.kt:14-16 | none | unit |
| RAW preview: LibRaw half-size decode, scaled to ≤ 2048 | ImageIo.kt:51-55, Raw.kt:27-28 | none | manual (needs raw file) / GPU |
| RAW decode: upright, camera white balance, linear F16, brightest 1% above white | Raw.kt:22-28, cpp/raw.cpp | none | GPU (with a small DNG asset) |
| RAW opens with Lightroom "Medium Contrast" tone curve | Raw.kt:19-20 | none | unit (curve LUT) |
| RAW highlights above white come back when Exposure lowers (also through a Curves layer) | gl/Pipeline.kt:952 | GPU: rawHighlightsAboveWhiteComeBackWithExposure | GPU |
| RAW that LibRaw can't read falls back to BitmapFactory | ImageIo.kt:51 | none | manual |
| F16 upload works before API 28 (raw half floats); API 26-27 F16 decodes treated as linear | gl/Pipeline.kt:41, 160 | none | GPU (API 26 device) |
| Ultra HDR: gain map read on API 34+, kept through decode and rotation | ImageIo.kt:43-47 | GPU: androidKeepsTheGainMapThroughJpegAndRotation | GPU |
| Ultra HDR photo opens looking like the gallery (no edits = SDR look) | gl/Pipeline.kt:47-49, 813-830 | GPU: ultraHdrOpensLikeTheGalleryPhoto | GPU |
| Ultra HDR: darkening edits reveal the hidden highlight range | gl/Pipeline.kt:235-249, 821-830 | GPU: ultraHdrBringsBackHighlightsWhenDarkened | GPU |
| Single-channel gain maps uploaded as RGBA (driver stall workaround) | gl/Pipeline.kt:189 | none | GPU |
| GL context loss: bitmap, subjects and depth re-uploaded on surface re-create | PhotoView.kt:325, 339-368 | none | manual |

## 3. Canvas: pan, zoom, touches

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Photo fits the view preserving aspect (letterbox on the right axis) | PhotoView.kt:24-28 | unit: MathTest.fitScaleLetterboxesCorrectAxis | unit |
| Pinch zoom 1× to 20×, pinch focus point stays fixed | PhotoView.kt:252-266 | none | manual / UI (injected multi-touch) |
| One-finger pan when no tool takes touches | PhotoView.kt:269-275 | none | UI |
| Two-finger pan always (also inside brush tools) | PhotoView.kt:270 | none | manual |
| Double-tap resets to Fit (only when no tool takes touches). Spec: Fit ↔ 200% at the tapped point | PhotoView.kt:277-281 | none | UI |
| Pan limits: photo can't leave the view; bottom limit is the top of the floating bar | PhotoView.kt:224-235 | none | unit (extract math) / UI |
| At Fit, a photo taller than the clear area rests at the top | PhotoView.kt:52, 232 | none | UI |
| Bottom inset follows the bar only (rows above it never move the photo) | ui/EditorScreen.kt:579-581, PhotoView.kt:197-202 | none | UI |
| Crop tool adds 32dp margin around the photo at Fit (clear of the back gesture) | ui/EditorScreen.kt:409, PhotoView.kt:88, 220 | none | UI |
| Zoom resets when the shown crop changes shape; same shape keeps zoom | PhotoView.kt:117-132 | none | UI |
| Tool touches arrive in upright photo 0..1 through the crop matrix (brush lands under the finger on a cropped/rotated photo) | PhotoView.kt:178, 285-305 | none | unit (matrix) / UI |
| A second finger cancels the current tool stroke (ToolTouch.Cancel) and turns the gesture into pan/zoom | PhotoView.kt:302-303 | none | UI |
| Touch routing: Crop > Liquify > mask mode / AI Erase > Lens/Zoom focus > none (pan) | ui/EditorScreen.kt:411-428 | UI: CanvasTest (crop, Liquify, gradient, lens tests), UndoTest.undoInsideAToolStepsBackOneBrushStrokeAtATime | UI |
| Overlays (handles, focus mark, crop) redraw on every zoom/pan | ui/EditorScreen.kt:332-335, PhotoView.kt:237 | none | UI |
| Navigator: shown only when zoomed past the view; hidden at Fit | PhotoView.kt:238-244, ui/EditorScreen.kt:568-576 | none | UI |
| Navigator: tap or drag inside moves the view there (clamped 0..1) | ui/LayerRail.kt:389-403, PhotoView.kt:205-210 | none | UI |
| Navigator: 66dp wide, height by aspect capped at 120dp (Spec: 88dp); white rect with black outline for the visible part | ui/LayerRail.kt:390-411 | none | UI (screenshot) |
| Navigator shows the cropped photo (or whole frame in Crop) via CPU-sampled thumbnail | ui/CropBar.kt:215-232, ui/EditorScreen.kt:569-571 | none | unit (Robolectric) |
| Navigator sits bottom-left, 8dp above the floating chrome | ui/EditorScreen.kt:574 | none | UI |

## 4. Top bar

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Back ("Close photo") closes the photo; asks first if there are edits (see §24) | ui/EditorScreen.kt:641, 510-513 | UI: BackTest.closingAPhotoWithEditsAsksKeepDiscardOrCancel | UI |
| Undo button, enabled only with a photo, no tool open, and history to undo | ui/EditorScreen.kt:642 | UI: UndoTest.applyingAToolIsOneUndoStep, undoInsideAToolStepsBackOneBrushStrokeAtATime | UI |
| Redo button, enabled only with a photo, no tool open, and history to redo | ui/EditorScreen.kt:643 | UI: UndoTest.applyingAToolIsOneUndoStep, undoInsideAToolStepsBackOneBrushStrokeAtATime | UI |
| Undo/Redo step the open tool's own gestures while any tool or Crop is open (`idle` = no tool); a fresh tool starts with nothing to undo | ui/EditorScreen.kt:639 | UI: UndoTest.undoInsideAToolStepsBackOneBrushStrokeAtATime | UI |
| Compare: hold shows the original (no layers), release restores; works inside every tool | ui/EditorScreen.kt:644-650, 173 | UI: CanvasTest.holdingCompareShowsTheOriginalWithTheCropStill | UI |
| Compare drops layers only; the crop still applies | ui/EditorScreen.kt:172-173, 340-342 | UI: CanvasTest.holdingCompareShowsTheOriginalWithTheCropStill | UI |
| Compare disabled (outline tint, no gesture) without a photo | ui/EditorScreen.kt:646-650 | UI: CanvasTest.compareDoesNothingWithoutAPhoto | UI |
| Spec: Compare shows an "Original" tag and is a toggle under TalkBack; not built | design/UI.md:216 | none | n/a |
| Export button: Pollen petal when enabled; disabled while a tool is open or exporting | ui/EditorScreen.kt:654-665 | UI: ExportMenuTest.exportIsOffWhileAToolIsOpen, ProjectsTest.firstRunHomeHasTheTaglineAndOpenButtonsAndNoEditing | UI |
| Export progress: ring + "NN%" in the Export button while exporting | ui/EditorScreen.kt:660-663 | none | UI |
| Export menu: "Size: 100%" cycles 100 → 50 → 25 → 100 in place, menu stays open | ui/EditorScreen.kt:666-668 | UI: ExportMenuTest.sizeCyclesInPlaceAndTheMenuStaysOpen | UI |
| Export size choice is kept for the session (not persisted) | ui/EditorScreen.kt:475 | UI: ExportMenuTest.aFormatAsksWhereToSaveWithATimestampedName | UI |
| Export menu: one entry per format: JPEG, WebP, HEIC, AVIF, "16-bit PNG (larger, for further editing)"; each opens Save-as with `bloom_<millis>.<ext>` and its MIME type | ui/EditorScreen.kt:876-891, 620, 672 | UI: ExportMenuTest.theMenuListsTheFormatsThisPhoneCanWrite, aFormatAsksWhereToSaveWithATimestampedName | UI |
| HEIC shown only on API 29+ with a HEVC/HEIC encoder; AVIF only on API 34+ with an AV1 encoder | ImageIo.kt:36-47 | GPU: heicExport, avifExport (skip without encoder), UI: ExportMenuTest.theMenuListsTheFormatsThisPhoneCanWrite | GPU |

## 5. Undo / redo (history)

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Immutable document snapshots; undo/redo round-trips 50 random edits | model/Document.kt:280-295 | unit: HistoryTest.fiftyEditsUndoAndRedoRoundTrip | unit |
| History capped at 100 steps | model/Document.kt:293, 288 | unit: HistoryTest.pushClearsRedoAndCapsHistory | unit |
| A new edit clears redo | model/Document.kt:288 | unit: HistoryTest.pushClearsRedoAndCapsHistory | unit |
| Pushing an identical document is a no-op (no empty undo step) | model/Document.kt:288 | unit: HistoryTest.pushingTheSameDocumentAddsNoStep | unit |
| Applying a tool (✓) is one undo step | ui/EditorScreen.kt:243-248 | UI: UndoTest.applyingAToolIsOneUndoStep | UI |
| Applying Crop is one undo step | ui/EditorScreen.kt:256-260 | UI: UndoTest.applyingCropIsOneUndoStep | UI |
| Hide/show, opacity release, blend change, delete, reorder, deband toggle, rename, group/ungroup, group hide/opacity/collapse/move: one undo step each (hide-all is not undoable) | ui/EditorScreen.kt:500, 608-615, 564, 598 | UI: LayerRailTest.swipingAThumbnailHidesTheLayerAsOneStepAndTheSnackbarUndoesIt, LayerBubbleTest (opacity, blend, delete, rename, group, move tests), LayerRailTest.swipingThePillHidesAllLayersAsAViewSetting (hide-all not undoable) | UI |
| Opacity drag previews live and commits one step on release | ui/EditorScreen.kt:603-611, ui/LayerRail.kt:342 | UI: LayerBubbleTest.opacityDragPreviewsLiveAndCommitsOnRelease | UI |
| Undo of brush strokes / Liquify strokes inside an open tool: one gesture = one step (fixed 2026-11-23, `EditorState.steps`) | ui/EditorScreen.kt, model/Document.kt (History<T>) | unit: HistoryTest.toolStepsOneGestureEach, UI: UndoTest.undoInsideAToolStepsBackOneBrushStrokeAtATime, sliderDragInsideAToolIsOneStep | UI |
| Opening a new photo clears history | ui/EditorScreen.kt:281 | UI: UndoTest.openingAnotherPhotoClearsHistory | UI |

## 6. Layer rail

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Rail on the right edge, top layer first, Photo last | ui/LayerRail.kt:110, 124-133, ui/EditorScreen.kt:556-567 | UI: LayerRailTest.railListsTheTopLayerFirstAndPhotoLast | UI |
| Header pill: layers icon + layer count | ui/LayerRail.kt:111-122 | UI: LayerRailTest.railListsTheTopLayerFirstAndPhotoLast | UI |
| Tap the header collapses the rail to a 20dp tab; tap the tab ("Show layers") expands it | ui/LayerRail.kt:101-109, 112 | UI: LayerRailTest.tappingThePillCollapsesTheRailAndTheTabExpandsIt | UI |
| Collapsed state is not persisted (resets per composition) | ui/LayerRail.kt:96 | none | UI |
| Thumbnail = layer kind icon (Color, Blur, Sharpen, Liquify, Retouch) | ui/LayerRail.kt:223, 275-281 | UI: LayerRailTest.thumbnailsReadNameKindGroupMaskAndHidden (kind by description) | UI |
| Half-filled dot badge when the layer has an active mask | ui/LayerRail.kt:242-247 | UI: LayerRailTest.thumbnailsReadNameKindGroupMaskAndHidden (description only; drawing manual) | UI (screenshot) |
| Hidden layer: dimmed to 45% + strike-through | ui/LayerRail.kt:236-241 | UI: LayerRailTest.swipingAThumbnailHidesTheLayerAsOneStepAndTheSnackbarUndoesIt (description only; drawing manual) | UI (screenshot) |
| Active layer (bubble open or being edited): 2dp Pollen border + 3dp side bar | ui/LayerRail.kt:235, 256, ui/EditorScreen.kt:560 | none | UI |
| Pending (new, unapplied) layer: dashed Pollen thumbnail at the top, "New X layer, not applied yet". Spec: directly above the active layer | ui/LayerRail.kt:125, 249-255, ui/EditorScreen.kt:529 | UI: LayerRailTest.aDraftLayerShowsAsPendingAtTheTop | UI |
| Pending thumbnail and Photo can't be swiped or reordered | ui/LayerRail.kt:125-133 | none | UI |
| Tap a thumbnail opens its bubble; tap the same one again closes it | ui/LayerRail.kt:193, ui/EditorScreen.kt:562 | UI: LayerRailTest.tappingAThumbnailTogglesItsBubble | UI |
| Tap Photo opens the Photo bubble (toggle) | ui/LayerRail.kt:127-130, ui/EditorScreen.kt:565 | UI: LayerRailTest.tappingThePhotoTogglesThePhotoBubble | UI |
| Gesture decided by first touch-slop: sideways = swipe, vertical = scroll, none before long-press timeout = hold | ui/LayerRail.kt:176-190 | UI: LayerRailTest (tap, swipe and hold-drag tests) | UI |
| Vertical drag on a thumbnail scrolls the rail | ui/LayerRail.kt:194, 124 | none | UI |
| Swipe left/right: thumbnail follows with resistance (0.3×, max 10dp), toggles hide/show once past 24dp, snaps back on release | ui/LayerRail.kt:195-206 | UI: LayerRailTest.swipingAThumbnailHidesTheLayerAsOneStepAndTheSnackbarUndoesIt (toggle; follow animation manual) | UI |
| Swipe toggle is one undo step | ui/EditorScreen.kt:499-500 | UI: LayerRailTest.swipingAThumbnailHidesTheLayerAsOneStepAndTheSnackbarUndoesIt | UI |
| Snackbar "<Kind> layer hidden/shown · Undo" after a hide/show, first 3 times per session only | ui/EditorScreen.kt:499-507 | UI: LayerRailTest.swipingAThumbnailHidesTheLayerAsOneStepAndTheSnackbarUndoesIt (first 3 per session: none) | UI |
| Snackbar Undo restores only that layer's visibility (later edits kept) | ui/EditorScreen.kt:503-504 | UI: LayerRailTest.swipingAThumbnailHidesTheLayerAsOneStepAndTheSnackbarUndoesIt | UI |
| Hold-and-drag reorder: thumbnail (layer or group header) lifts (1.15×, shadow); on drop, one Move up/down step per row dragged, all one undo step | ui/LayerRail.kt:213-290, ui/EditorScreen.kt:792 | unit: LayersTest.stepSwapsWithTheNeighbourAndStopsAtTheEnds, UI: LayerRailTest.holdAndDragMovesALayerUpOneRowAsOneStep | unit + UI |
| Document.move: into another group's run joins it, away from its own group leaves it | model/Document.kt:333 | unit: HistoryTest.moveKeepsAllLayers, LayersTest.moveIntoAGroupJoinsItAndOutOfItLeaves | unit |
| Swipe the layers pill left/right: hides/shows all layers at once, latched until swiped back. View state (not saved, not undoable, reset on open); pill turns Pollen with an eye-off icon, layer rows dim to 45%. A draft being edited still shows. Export ignores it. Tap still collapses the rail | ui/LayerRail.kt:138-166, ui/EditorScreen.kt:146, 199-209 | unit: LayersTest.hidingAllLayersIsAViewSettingThatKeepsTheDraft, UI: LayerRailTest.swipingThePillHidesAllLayersAsAViewSetting | unit + UI |
| Groups on the rail: a header row (folder icon) above its members; members have a line down the left; a collapsed group shows only its header; members of a hidden group are struck through | ui/LayerRail.kt:169-198, 300 | UI: LayerRailTest.thumbnailsReadNameKindGroupMaskAndHidden, aCollapsedGroupShowsOnlyItsHeader | UI |
| Group header: tap = group bubble, swipe = hide/show the group (one undo step), hold-drag = move the group as a block | ui/LayerRail.kt:174-181, ui/EditorScreen.kt:787-795 | unit: LayersTest.stepGroupMovesItsMembersAsABlock, UI: LayerRailTest.groupHeaderSwipesHideHoldDragsMoveAndTapsOpenTheBubble | unit + UI |
| Spec: reorder haptic tick, auto-scroll near the edge; not built | design/UI.md:240 | none | n/a |
| Scroll arrows: up/down pills appear only when there is more that way; tap scrolls one row | ui/LayerRail.kt:135-140 | UI: LayerRailTest.scrollArrowsShowOnlyWhenThereIsMoreThatWay | UI |
| A11y: thumbnail reads "<Name>, <Kind> layer in <Group>, has mask, hidden"; header reads "<Name>, group of n layers, collapsed, hidden"; custom action Hide/Show layer or group; pill has Hide/Show all layers. Spec also: Layer settings, Move up, Move down (as actions) | ui/LayerRail.kt:158-171 | UI: LayerRailTest.thumbnailsReadNameKindGroupMaskAndHidden, theHideActionTogglesALayerForScreenReaders, swipingThePillHidesAllLayersAsAViewSetting | UI (semantics) |
| Rail bottom padding follows the chrome height | ui/EditorScreen.kt:566 | none | UI |
| Spec: rail fades to 25% while a finger is on the photo; not built | design/UI.md:240 | none | n/a |

## 7. Layer bubble

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Bubble opens left of the rail, tail at the thumbnail, vertically at its centre (never above the canvas top) | ui/EditorScreen.kt:593-594, ui/LayerRail.kt:287-294 | none | UI |
| Tap outside closes the bubble and does nothing else | ui/EditorScreen.kt:591-592 | UI: LayerBubbleTest.tapOutsideClosesTheBubbleAndDoesNothingElse, touchesOnTheBubblesOwnBackgroundKeepItOpen | UI |
| Bubble closes if its layer disappears (e.g. undo) | ui/EditorScreen.kt:589-590 | UI: LayerBubbleTest.theBubbleClosesWhenItsLayerGoes | UI |
| Title = layer name, or its kind when unnamed; eye button hides/shows (same as swipe, with snackbar) | ui/LayerRail.kt:333-338, ui/EditorScreen.kt:606 | UI: LayerBubbleTest.renameTrimsAndEmptyFallsBackToTheKind, eyeButtonHidesTheLayerWithASnackbar | UI |
| Opacity slider 0..100%, integer steps, value label | ui/LayerRail.kt:339-346 | UI: LayerBubbleTest.opacitySliderSetsOpacityAsOneStep | UI |
| Opacity drag previews live, one undo step on release | ui/EditorScreen.kt:603-611 | UI: LayerBubbleTest.opacityDragPreviewsLiveAndCommitsOnRelease | UI |
| Opacity math: 50% is halfway | gl/Pipeline.kt:270, 964 | GPU: halfOpacityIsHalfway | GPU |
| Blend menu: Normal, Multiply, Screen, Overlay, Soft light; check on the current one | ui/LayerRail.kt:347-361, model/Document.kt:10 | UI: LayerBubbleTest.blendMenuListsTheModesAndSetsOne | UI |
| Blend modes match their formulas | gl/Pipeline.kt:907-913, 958 | GPU: blendModesMatchTheirFormulas | GPU |
| Edit: opens the layer's tool with its values (✓ updates the same layer) | ui/LayerRail.kt:363, ui/EditorScreen.kt:612, 181-190 | UI: LayerBubbleTest.editOpensTheLayersToolAndApplyReplacesIt | UI |
| Edit mask: opens the tool already in mask mode | ui/LayerRail.kt:364, ui/EditorScreen.kt:613 | UI: LayerBubbleTest.editMaskOpensTheToolInMaskMode | UI |
| Delete: removes the layer (one undo step) and closes the bubble | ui/LayerRail.kt:365, ui/EditorScreen.kt:615 | UI: LayerBubbleTest.deleteRemovesTheLayerAsOneStepAndClosesTheBubble | UI |
| Rename: tap the title to edit, IME Done commits (trimmed; empty = kind label); one undo step; saved as `Layer.name` | ui/LayerRail.kt:390-425, ui/EditorScreen.kt:757 | unit: LayersTest.renameIsOneUndoStep, ProjectTest.documentSurvivesJson, UI: LayerBubbleTest.renameTrimsAndEmptyFallsBackToTheKind | unit + UI |
| Move up / Move down: one step, disabled when there is none. At its group's edge a layer leaves the group; next to a group it joins it (skips a collapsed group whole); else swaps. Bubble follows the thumbnail | model/Document.kt:347, ui/LayerRail.kt:477, ui/EditorScreen.kt:765-767, 786 | unit: LayersTest.stepSwapsWithTheNeighbourAndStopsAtTheEnds, stepLeavesAGroupAtItsEdgeAndJoinsOneNextToIt, stepSkipsACollapsedGroup, UI: LayerBubbleTest.moveButtonsStepTheLayerAndDisableAtTheEnds | unit + UI |
| Group below: groups the layer with the one below (new group, or joins the one either is in); disabled at the bottom or when both are grouped. A grouped layer shows Ungroup instead | model/Document.kt:377, ui/LayerRail.kt:479-480 | unit: LayersTest.groupWithBelowMakesAGroupThenJoinsIt, UI: LayerBubbleTest.groupBelowMakesAGroupAndUngroupUndoesIt, groupBelowIsDisabledForTheBottomLayer | unit + UI |
| Group members stay contiguous through move, step, group, ungroup and delete; empty groups are dropped | model/Document.kt:312-391 | unit: LayersTest.randomOperationsKeepGroupsContiguous, deletingInAGroupKeepsItUntilItsLastMemberGoes, ungroupKeepsTheLayersInPlace | unit |
| No Duplicate action | ui/LayerRail.kt:470-481 | none | n/a |
| System back closes an open bubble first | ui/EditorScreen.kt:519 | UI: LayerBubbleTest.backClosesTheBubbleFirst | UI |

## 7b. Group bubble

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Title = group name or "Group", tap to rename; collapse/expand chevron; eye hides/shows the group; each one undo step, all saved | ui/LayerRail.kt:487-512, ui/EditorScreen.kt:729-745 | unit: ProjectTest.documentSurvivesJson, UI: LayerBubbleTest.groupBubbleRenamesCollapsesHidesFadesMovesAndUngroups | unit + UI |
| Opacity slider previews live, one undo step on release | ui/EditorScreen.kt:733-737, 199-209 | unit: LayersTest.hidingAllLayersIsAViewSettingThatKeepsTheDraft (preview), UI: LayerBubbleTest.groupBubbleRenamesCollapsesHidesFadesMovesAndUngroups | unit + UI |
| Rendering: a hidden group hides its members, group opacity multiplies each member's (not the group's composite); preview and export | model/Document.kt:323, ui/EditorScreen.kt:207, 604 | unit: LayersTest.renderedAppliesGroupVisibilityAndOpacity | unit |
| Move up / Move down move the group's run past the next layer or group; Ungroup keeps the layers in place and closes the bubble | model/Document.kt:362, 389 | unit: LayersTest.stepGroupMovesItsMembersAsABlock, ungroupKeepsTheLayersInPlace, UI: LayerBubbleTest.groupBubbleRenamesCollapsesHidesFadesMovesAndUngroups | unit + UI |
| Group and layer ids share one id space (new ids start past both after opening a project) | ui/EditorScreen.kt:211, 346 | unit: LayersTest.hidingAllLayersIsAViewSettingThatKeepsTheDraft | unit |

## 8. Photo bubble (Smooth banding)

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Photo bubble: "Smooth banding" switch, on by default, one undo step per toggle | ui/LayerRail.kt:297-316, model/Document.kt:269, ui/EditorScreen.kt:598 | UI: LayerBubbleTest.photoBubbleTogglesSmoothBandingAsOneStep | UI |
| Switch disabled and off with "Not needed: this photo has more than 8 bits" for F16 previews | ui/LayerRail.kt:302-313, ui/EditorScreen.kt:597 | UI: LayerBubbleTest.photoBubbleSwitchIsOffAndDisabledForDeepPhotos | UI |
| Deband smooths 8-bit steps under a hard push | gl/Pipeline.kt:537-563, 768-812 | GPU: debandSmoothsEightBitStepsUnderAHardPush | GPU |
| Deband keeps edges and texture | gl/Pipeline.kt:768-812 | GPU: debandKeepsEdgesAndTexture | GPU |
| Deband noise hashes the full-image pixel position, so export tiles agree | gl/Pipeline.kt:770 | GPU: paddedTileMatchesFullRenderWithFeather (deband = true) | GPU |
| Deband setting reaches preview and export | ui/EditorScreen.kt:336, 482 | none | UI |

## 9. Tool bar shell (shared by all tools)

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Tool list: Color, Detail, Liquify, Crop, AI as icon-only buttons (names for screen readers). Spec: icon + label | ui/EditorScreen.kt:729-735, 795-801 | UI: ColorToolTest.theToolListHasEveryTool, AccessibilityTest.everyControlHasAName | UI |
| Opening a tool starts a new draft layer (Color/Blur/Liquify/Retouch); param resets to Exposure, channel to RGB, mask mode off, Brush tool, no step selected | ui/EditorScreen.kt:181-190 | unit: EditorStateTest.openingAToolStartsAFreshDraft | UI / unit (EditorState) |
| Opening a tool closes the layer bubble | ui/EditorScreen.kt:186, 253 | unit: EditorStateTest.openingAToolStartsAFreshDraft, UI: LayerBubbleTest.editOpensTheLayersToolAndApplyReplacesIt | UI |
| Left slot: tool icon on a petal; tap = back to tools (asks "Discard changes?" if changed) | ui/EditorScreen.kt:805-814, 514 | UI: BackTest.leavingAChangedToolAsksAndKeepEditingStays, LayerRailTest.aDraftLayerShowsAsPendingAtTheTop | UI |
| ✓ Apply: new layer added on top; edited layer replaced in place; desc "Apply as X layer" / "Apply changes" | ui/MaskBar.kt:112-115, ui/EditorScreen.kt:243-248 | UI: UndoTest.applyingAToolIsOneUndoStep, LayerBubbleTest.editOpensTheLayersToolAndApplyReplacesIt, unit: EditorStateTest.newLayersGoOnTopAndEditedOnesStayInPlace | UI |
| ✓ on a new layer with nothing changed adds no layer | ui/EditorScreen.kt:245-246, model/Document.kt:257-263 | unit: EditorStateTest.applyingAnUnchangedNewLayerAddsNothing | unit (Layer.edited) |
| `dirty`: crop differs, or edited layer differs, or new layer is edited | ui/EditorScreen.kt:240-241 | unit: EditorStateTest.dirtyMeansTheCropOrTheEditedLayerOrANewEditedLayerChanged | unit (EditorState) |
| Mask button: "Mask: none. Add" / "Mask: N steps. Edit", count badge on a filled chip once there are steps. Spec: mask thumbnail | ui/MaskBar.kt:96-108 | UI: MaskBarTest.typeButtonsAddAStepWithTheSignSelectItAndSwitchTool, ToolBarsTest.aiBarErasesOnlyOnceThereIsAMask | UI |
| Bottom bar is fixed 60dp; settings box floats above it | ui/EditorScreen.kt:704-745 | none | UI |
| Param chips: icon only; selected = fill + Pollen underline; dot when value changed | ui/EditorScreen.kt:909-923 | UI: ColorToolTest.theBarListsEveryParameterWithCurvesBeforeTemp (selected state; drawing manual) | UI |
| Live preview of the draft layer while editing (draft replaces the edited layer in place) | ui/EditorScreen.kt:172-178, 343 | none | UI |
| Layers above an edited layer still render over the draft | ui/EditorScreen.kt:176 | none | GPU / UI |

## 10. Color tool

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Parameters: Exposure, Contrast, Highlights, Shadows, Temp, Tint, Saturation, Vibrance; Curves chip sits before Temp | ui/EditorScreen.kt:769-779, model/Document.kt:7 | UI: ColorToolTest.theBarListsEveryParameterWithCurvesBeforeTemp | UI |
| Selected parameter's row: name, slider −100..100, value, Reset (to 0) | ui/EditorScreen.kt:750-765 | UI: ColorToolTest.otherParametersSnapToWholeNumbersWithATrueMinus | UI |
| Slider snaps to integers (non-Exposure) | ui/EditorScreen.kt:928 | UI: ColorToolTest.otherParametersSnapToWholeNumbersWithATrueMinus | unit (extract snap) |
| Exposure shown in stops (±3.0 EV), snaps to 0.1 stop | ui/EditorScreen.kt:925-938 | UI: ColorToolTest.exposureReadsInStopsAndSnapsToATenth | unit |
| Value text: "+N", "−N" (true minus sign), "0" | ui/EditorScreen.kt:930-947 | UI: ColorToolTest.exposureReadsInStopsAndSnapsToATenth, otherParametersSnapToWholeNumbersWithATrueMinus | unit |
| Chip a11y: "Exposure +1.0 stops", "Curves, edited" | ui/EditorScreen.kt:774-775 | UI: ColorToolTest.exposureReadsInStopsAndSnapsToATenth, tapAddsAPointOnTheCurveAndDragOffTheTopRemovesIt | UI (semantics) |
| Bar scrolls sideways with edge fade and ‹ › | ui/EditorScreen.kt:771, ui/Theme.kt:62-82 | none | UI |
| No layers = identity; zero layer = identity | gl/Pipeline.kt:225-234 | GPU: noLayersIsIdentity, zeroLayerIsIdentity | GPU |
| Exposure +1 stop doubles linear light | gl/Pipeline.kt:941 | GPU: exposurePlusOneStopDoublesLinearLight | GPU |
| Contrast around 0.5 | gl/Pipeline.kt:947 | none | GPU |
| Highlights / Shadows lift by luminance band | gl/Pipeline.kt:945 | none | GPU |
| Temp / Tint in linear light | gl/Pipeline.kt:942 | none | GPU |
| Saturation and Vibrance (vibrance favours low-saturation pixels) | gl/Pipeline.kt:950 | none | GPU |
| Spec: slider double-tap resets, a11y ±1 step actions; not built | design/UI.md:223 | none | n/a |

## 11. Curves

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Curves chip replaces the slider row with channel chips (RGB, R, G, B), Reset and a 120dp graph | ui/CurvesGraph.kt:40-58 | UI: ColorToolTest.curvesChipSwapsTheSliderForTheGraph | UI |
| Channel chip says ", edited" when that channel isn't the line | ui/CurvesGraph.kt:47 | UI: ColorToolTest.resetAndEditedMarksAreForTheSelectedChannelOnly | UI |
| Reset resets only the selected channel | ui/CurvesGraph.kt:50-52 | UI: ColorToolTest.resetAndEditedMarksAreForTheSelectedChannelOnly | UI |
| Tap on empty curve adds a point on the curve; drag moves it | ui/CurvesGraph.kt:84-92 | UI: ColorToolTest.tapAddsAPointOnTheCurveAndDragOffTheTopRemovesIt | UI |
| Grabs the nearest point within 24dp | ui/CurvesGraph.kt:78, 85-86 | none | UI |
| No point added past 12 points, at the ends, or within 0.01 of a neighbour | ui/CurvesGraph.kt:89, model/Document.kt:162 | none | UI |
| Points stay ordered: x clamped between neighbours ± 0.01; y clamped 0..1 | ui/CurvesGraph.kt:102-105 | none | UI |
| Drag a middle point off the top/bottom (past 24dp) removes it; end points can't be removed; never below 2 points | ui/CurvesGraph.kt:93-100 | UI: ColorToolTest.tapAddsAPointOnTheCurveAndDragOffTheTopRemovesIt | UI |
| A11y: graph reads points; actions Raise/Lower point by 5% | ui/CurvesGraph.kt:67-76 | UI: ColorToolTest.curvesChipSwapsTheSliderForTheGraph, resetAndEditedMarksAreForTheSelectedChannelOnly | UI (semantics) |
| Monotone cubic passes through its points | model/Document.kt:170-208 | unit: CurvesTest.passesThroughItsPoints | unit |
| No overshoot between points | model/Document.kt:186-208 | unit: CurvesTest.risingPointsGiveARisingCurveWithoutOvershoot | unit |
| Default curve changes nothing / not active | model/Document.kt:138, 148-158 | unit: CurvesTest.defaultCurveChangesNothing | unit |
| Channel curve applies after RGB | model/Document.kt:148-158 | unit: CurvesTest.channelCurveAppliesAfterRgb | unit |
| GPU curves match the reference table within one level (half-float table) | gl/Pipeline.kt:107, 952-954 | GPU: curvesMatchTheReferenceTableWithinOneLevel | GPU |
| Values above 1.0 ride over the table so later layers can pull them back | gl/Pipeline.kt:952 | GPU: rawHighlightsAboveWhiteComeBackWithExposure | GPU |

## 12. Mask mode: bar and steps row

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Mask button (or Edit mask) enters mask mode; left slot "X mask. Back to the tool" and ✓ "Done with mask" return to the tool, keeping the mask | ui/MaskBar.kt:113, 122, 138 | UI: MaskBarTest.maskModeHasTheStepTypesInvertAndDone, theLeftSlotGoesBackToTheTool, LayerBubbleTest.editMaskOpensTheToolInMaskMode | UI |
| Bar type buttons: Brush, Gradient, Color, Luminance, Depth, Subject, Background; each shows the current sign badge | ui/MaskBar.kt:123-134 | UI: MaskBarTest.maskModeHasTheStepTypesInvertAndDone | UI |
| Brush button only selects the brush tool (painting makes the step) | ui/MaskBar.kt:125 | UI: MaskBarTest.typeButtonsAddAStepWithTheSignSelectItAndSwitchTool | UI |
| Every other type button adds a new step with the current sign, selects it, switches tool | ui/MaskBar.kt:126-133, ui/EditorScreen.kt:223-229 | UI: MaskBarTest.typeButtonsAddAStepWithTheSignSelectItAndSwitchTool, unit: EditorStateTest.addingAStepSelectsItAndSwitchesToItsTool | UI / unit |
| Luminance adds Shadows preset; Depth adds Near preset | ui/MaskBar.kt:129-130 | UI: MaskBarTest.typeButtonsAddAStepWithTheSignSelectItAndSwitchTool | UI |
| Luminance vs Depth (and Subject vs Background) highlight only the matching selected step | ui/MaskBar.kt:128-133 | UI: MaskBarTest.typeButtonsAddAStepWithTheSignSelectItAndSwitchTool | UI |
| Invert toggle (Pollen when on, `selected` semantics) | ui/MaskBar.kt:135-137 | UI: MaskBarTest.maskModeHasTheStepTypesInvertAndDone | UI |
| Invert flips the mask | gl/Pipeline.kt:964 | GPU: removeStepCutsOutAndInvertFlips | GPU |
| Sign toggle (+/− round button at the start of the settings row) sets the sign of the next step. Spec: in the bar | ui/MaskBar.kt:327-335, 183 | UI: MaskBarTest.typeButtonsAddAStepWithTheSignSelectItAndSwitchTool | UI |
| Steps row appears once the mask has a step; chips in evaluation order with type icon + sign badge | ui/MaskBar.kt:146-158, 409-443 | UI: MaskBarTest.aSelectedStepFlipsHidesAndDeletes | UI |
| Tap a step chip selects it (2dp Pollen border) and switches to its tool; tap again deselects | ui/EditorScreen.kt:207-212 | UI: MaskBarTest.tappingAStepChipSelectsItAndAgainDeselects, unit: EditorStateTest.tappingAStepSelectsItAndTappingAgainDeselects | unit (EditorState) / UI |
| Selected step: flip sign (+/−) button | ui/MaskBar.kt:161-163 | UI: MaskBarTest.aSelectedStepFlipsHidesAndDeletes | UI |
| Selected step: hide/show (eye) button; hidden chips are dimmed + struck through. Spec: dashed border | ui/MaskBar.kt:164-166, 439-441 | UI: MaskBarTest.aSelectedStepFlipsHidesAndDeletes | UI |
| Selected step: delete (trash); clears the selection | ui/MaskBar.kt:167, ui/EditorScreen.kt:201-205 | UI: MaskBarTest.aSelectedStepFlipsHidesAndDeletes | UI |
| Step chip long-press then drag sideways reorders (54dp pitch); the moved step stays selected | ui/MaskBar.kt:149-156, 419-425 | UI: MaskBarTest.stepChipsReorderByActionAndByHoldAndDrag | UI |
| Step chip a11y: "Add brush, hidden"; actions Move left / Move right | ui/MaskBar.kt:429-436 | UI: MaskBarTest.aSelectedStepFlipsHidesAndDeletes, stepChipsReorderByActionAndByHoldAndDrag | UI (semantics) |
| Mask.move keeps every step | model/Document.kt:125 | unit: MaskTest.moveKeepsEveryStep | unit |
| Step sign/visibility copy helper | model/Document.kt:105-111 | unit: MaskTest.maskIsActiveOnlyWithAVisibleStep | unit |
| Mask is active only with a visible step; inverted empty mask is inactive | model/Document.kt:116 | unit: MaskTest.maskIsActiveOnlyWithAVisibleStep | unit |
| Mask without visible steps covers the whole layer (even inverted) | gl/Pipeline.kt:258 | GPU: maskWithoutVisibleStepsCoversTheWholeLayer | GPU |
| Add = max, Remove = × (1 − step) | gl/Pipeline.kt:623-667 | GPU: removeStepCutsOutAndInvertFlips | GPU |
| Steps row scrolls sideways with edge fade | ui/MaskBar.kt:147 | none | UI |
| Spec: step popover bubble; built as inline buttons in the steps row instead | design/UI.md:233 | none | n/a |

## 13. Mask settings box (More, copy, clear, feather)

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| One box: main row + More (caret rotates; "Less") that stacks extra rows above | ui/MaskBar.kt:348-360 | UI: MaskBarTest.moreHasCopyFromAnotherLayerClearAndFeather | UI |
| More (mask) open/closed is remembered across sessions | ui/EditorScreen.kt:315 | UI: SettingsTest.savedToolSettingsLoadOnStart | UI |
| More: "Copy mask from…" lists other layers with steps as "<Kind> layer N"; disabled when none | ui/MaskBar.kt:225-241 | UI: MaskBarTest.moreHasCopyFromAnotherLayerClearAndFeather, copyFromIsDisabledWithoutAnotherMaskedLayer | UI |
| Copy mask replaces this layer's steps and invert, clears the selection | ui/MaskBar.kt:236-237 | UI: MaskBarTest.moreHasCopyFromAnotherLayerClearAndFeather | UI |
| More: "Clear mask" removes all steps and Invert; disabled when already empty | ui/MaskBar.kt:242-244 | UI: MaskBarTest.moreHasCopyFromAnotherLayerClearAndFeather | UI |
| More: Feather slider (0..100) for the selected step | ui/MaskBar.kt:181 | UI: MaskBarTest.moreHasCopyFromAnotherLayerClearAndFeather | UI |
| Feather softens an edge evenly (1 = 3% of image height) | gl/Pipeline.kt:673-687 | GPU: featherSoftensAnEdgeEvenly | GPU |
| Feathered mask: export tile with overlap matches the full render | ImageIo.kt:107, 113 | GPU: paddedTileMatchesFullRenderWithFeather | GPU |
| maxFeather and readsBelow count visible steps only | model/Document.kt:119-122 | unit: MaskTest.readsBelowAndMaxFeatherCountVisibleStepsOnly | unit |
| Slider rows snap to integers and align under the main row | ui/MaskBar.kt:338-345 | none | UI |

## 14. Mask step: Brush

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| One finger paints with the current sign | ui/MaskBar.kt:514, 555-563 | UI: UndoTest.undoInsideAToolStepsBackOneBrushStrokeAtATime | UI |
| Consecutive strokes with the same sign merge into the last Brush step (if visible); otherwise a new step | ui/MaskBar.kt:557-560 | UI: UndoTest.undoInsideAToolStepsBackOneBrushStrokeAtATime | unit (MaskTouch with fake view) |
| Stroke dabs a quarter radius apart; short moves carry over | model/Document.kt:16-40 | unit: MaskTest.strokeDabsAreAQuarterRadiusApart | unit |
| Brush radius in screen dp → fraction of image height (zoom in for finer strokes) | ui/MaskBar.kt:556 | none | unit |
| Size slider 4..150 (Spec: 4–200), integer | ui/MaskBar.kt:185-189 | UI: MaskBarTest.brushRowsHaveSizeSoftnessAndFlow | UI |
| More: Softness 0..100, Flow 1..100 | ui/MaskBar.kt:374-377 | UI: MaskBarTest.brushRowsHaveSizeSoftnessAndFlow | UI |
| Flow builds up where dabs overlap | gl/Pipeline.kt:688-716 | none | GPU |
| Hard dab is full inside and none outside | gl/Pipeline.kt:688-716 | GPU: hardDabIsFullInsideAndNoneOutside | GPU |
| Second finger or system cancel undoes the stroke in progress (restores the mask from touch-down) | ui/MaskBar.kt:531, PhotoView.kt:303 | none | unit / UI |
| Brush mask lines up in rotated (EXIF) export tiles | gl/Pipeline.kt:72-74 | GPU: rotatedExportTilesMatchTheUprightPreview | GPU |

## 15. Mask step: Gradient

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Gradient button adds a linear gradient (full at top, fading past the middle) with the current sign | ui/EditorScreen.kt:238 | UI: MaskBarTest.typeButtonsAddAStepWithTheSignSelectItAndSwitchTool | UI |
| Linear / Radial chips for the selected gradient; hint "Tap the gradient icon to add one" when none selected | ui/MaskBar.kt:190-197 | UI: MaskBarTest.tappingAStepChipSelectsItAndAgainDeselects | UI |
| Handles drawn on the photo: filled Pollen = full end, ring = fade end; radial shows edge and half-radius circles | ui/MaskBar.kt:454-477 | none | UI (screenshot) |
| Handles hidden while the gradient step is hidden | ui/EditorScreen.kt:544 | none | UI |
| Drag near a handle (32dp) moves it | ui/MaskBar.kt:576-585, 525 | none | UI |
| Touch away from both handles starts a new gradient line at the finger | ui/MaskBar.kt:582-584 | UI: CanvasTest.touchingAwayFromTheHandlesDrawsANewGradientLine | UI |
| Touch with Gradient tool and no gradient selected adds one | ui/MaskBar.kt:577 | none | UI |
| Linear gradient follows smoothstep | gl/Pipeline.kt:967-1000 | GPU: linearGradientFollowsSmoothstep | GPU |
| Radial gradient (round in image space) | gl/Pipeline.kt:970 | GPU: rotatedExportTilesMatchTheUprightPreview (partial: radial step present) | GPU |

## 16. Mask step: Color range (eyedropper)

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Color button adds an empty Color step; hint "Tap the photo to pick a color" | ui/MaskBar.kt:127, 258-260 | UI: MaskBarTest.colorRangeHintsThenATapOnThePhotoPicksAColor | UI |
| Tap the photo samples the color of the layers below (3×3 average) into the selected Color step (adds one if none selected) | ui/MaskBar.kt:516, 538-553, gl/Pipeline.kt:488 | GPU: eyedropperReadsTheLayersBelow, UI: MaskBarTest.colorRangeHintsThenATapOnThePhotoPicksAColor | GPU + UI |
| Dragging fine-tunes the last sample | ui/MaskBar.kt:526 | none | UI |
| One readback at a time while dragging | ui/MaskBar.kt:539 | none | unit |
| "+" arms the next tap to add a sample (up to 4); hidden at 4 | ui/MaskBar.kt:271-275, model/Document.kt:72 | UI: MaskBarTest.colorRangeHintsThenATapOnThePhotoPicksAColor | UI |
| Without "+", a tap replaces all samples | ui/MaskBar.kt:545-549 | none | unit |
| Swatches; tapping one removes it (only when there is more than one) | ui/MaskBar.kt:263-270 | UI: MaskBarTest.colorRangeHintsThenATapOnThePhotoPicksAColor | UI |
| Range slider (0..100) in the main row; More: Smooth (Spec: Smoothness) | ui/MaskBar.kt:276, 176-178 | UI: MaskBarTest.colorRangeHintsThenATapOnThePhotoPicksAColor | UI |
| Color step selects the sampled hue only | gl/Pipeline.kt:1038 | GPU: colorStepSelectsTheSampledHueOnly | GPU |
| Loupe shows while picking | ui/EditorScreen.kt:398 | none | manual |

## 17. Mask step: Luminance and Depth range

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Luminance presets Shadows / Midtones / Highlights; Depth presets Near / Middle / Far | model/Document.kt:85-90, ui/MaskBar.kt:248-249 | unit: EditorStateTest.luminanceAndDepthPresetsCoverTheirBands | unit |
| Preset button cycles presets; shows "Custom" after a handle drag | ui/MaskBar.kt:205-210 | UI: MaskBarTest.luminancePresetCyclesAndTheStripReadsItsRange | UI |
| 4-handle strip: nearest handle moves; stacked handles pick by drag direction; handles stay ordered | ui/MaskBar.kt:281-324 | UI: MaskBarTest.luminancePresetCyclesAndTheStripReadsItsRange (order kept) | UI |
| Strip a11y: "Luminance range a to d percent" / "Depth range, far to near" | ui/MaskBar.kt:286 | UI: MaskBarTest.luminancePresetCyclesAndTheStripReadsItsRange | UI (semantics) |
| Hint when no step selected; photo touches do nothing in this tool | ui/MaskBar.kt:202, 518 | none | UI |
| Luminance step selects only its range | gl/Pipeline.kt:1001-1012 | GPU: luminanceStepSelectsOnlyItsRange | GPU |
| Luminance reads the layers below (follows earlier exposure) | ui/EditorScreen.kt:232-235, gl/Pipeline.kt:598-600 | GPU: luminanceFollowsEarlierLayers | GPU |
| Depth range needs the depth map: status row "Measuring depth…" / download % / "Couldn't…. Tap to retry" | ui/MaskBar.kt:201-203, ui/EditorScreen.kt:349-366 | none | UI (fake ModelStore) |
| Depth range selects near/far using the depth map, edges snapped to the photo | gl/Pipeline.kt:968-969, 1015-1030 | GPU: depthRangeStepSelectsTheNearHalf | GPU |

## 18. Mask step: Subject, Background, pins

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Subject / Background buttons add a step; mask found once per photo (and per pin set), cached | ui/MaskBar.kt:132-133, ui/EditorScreen.kt:367-390 | UI: MaskBarTest.typeButtonsAddAStepWithTheSignSelectItAndSwitchTool | UI |
| Status: "Finding the subject…" (no pins) / "Selecting…" (pins) / "Downloading AI model… N%" / "Couldn't find the subject: …. Tap to retry" | ui/EditorScreen.kt:373, 376, 387, ui/MaskBar.kt:214-218 | UI: MaskBarTest.subjectStatusAndHintShowInTheMainRow | UI (fake ModelStore) |
| Hint: "Tap what to select. Remove mode: tap what to leave out" / "Tap what to leave out" | ui/MaskBar.kt:217 | UI: MaskBarTest.subjectStatusAndHintShowInTheMainRow | UI |
| Tap on the photo with a Subject step selected adds a pin with the current sign (include / exclude) | ui/MaskBar.kt:566-574 | none | UI |
| Tap within 24dp of a pin removes it | ui/MaskBar.kt:570-571 | none | UI |
| Tap with no Subject step selected does nothing | ui/MaskBar.kt:567 | none | unit |
| Pins drawn: filled Pollen = include, ring = exclude | ui/MaskBar.kt:480-493 | none | UI (screenshot) |
| No pins: IS-Net automatic subject | Segment.kt:23-27, cpp/segment.cpp:100-127 | GPU (model): subjectMaskFindsTheObject | GPU |
| Pins: MobileSAM; encoder once per photo, one decode per include pin, masks merged (max) | Segment.kt:32-54, cpp/segment.cpp:139-202 | GPU (model): aPinSelectsOnlyThatObject | GPU |
| Only exclude pins: empty mask | Segment.kt:47 | none | GPU |
| Subject and Background steps follow the subject mask (Background = everything else) | gl/Pipeline.kt:968-969 | GPU: subjectStepsFollowTheSubjectMask | GPU |
| Joint bilateral upsampling snaps the low-res mask to photo edges | gl/Pipeline.kt:1015-1030 | none | GPU |
| AI job for a photo that was replaced mid-run is dropped | ui/EditorScreen.kt:385 | none | UI |
| Several missing pin sets: stops at the first failure | ui/EditorScreen.kt:388 | none | unit (extract) |
| Spec diff: mask computed from the preview photo, not the composite below | docs/PLAN.md:56 | none | n/a |

## 19. Mask overlay, loupe, brush ring

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Red tint (#FF3B30, ~45%) + diagonal hatch + black/white dashed 50% edge, in mask mode and AI Erase only. Spec: edge only in tool mode (changed post-freeze: nothing) | ui/EditorScreen.kt:412, PhotoView.kt:471-479 | none | GPU (screenshot) |
| Overlay respects Invert, hidden layers and inactive masks | PhotoView.kt:385, 398 | none | GPU |
| Loupe while a finger paints or picks a color (mask brush, AI Erase brush, eyedropper) | ui/EditorScreen.kt:398, PhotoView.kt:289-292 | none | manual |
| Loupe: 104dp circle top-left; moves top-right when the finger is within 150dp of that corner. Spec: drops to leading side above the rows | PhotoView.kt:413-418 | none | manual |
| Loupe zoom 2×, less for big brushes so the ring fits (never below 1×); white rim, black outline | PhotoView.kt:421-427, 462 | none | manual |
| Brush ring rests where the finger last lifted; hidden while drawing | PhotoView.kt:67-73, 294-295, 410 | none | manual |
| Brush ring shown for mask brush, AI Erase and Liquify only | ui/EditorScreen.kt:399-403 | none | UI |
| Ring: softness 0 = one solid ring at the edge; soft = dashed outer + solid inner at (1 − softness) | PhotoView.kt:480-495 | none | GPU (screenshot) |
| Size/Softness slider shows the ring mid-photo; fades 700 ms after the slider stops | ui/LiquifyBar.kt:72-89, ui/EditorScreen.kt:154, 407, 550 | none | UI |
| Mid-photo ring: softness ≤ 2% draws one solid ring | ui/LiquifyBar.kt:80 | none | UI (screenshot) |

## 20. Detail tool (Blur, Sharpen)

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Detail opens a new Blur layer; bar chips Blur / Sharpen | ui/EditorScreen.kt:731, 783-792 | UI: ToolBarsTest.detailOpensABlurLayerAndSwitchesToSharpenWithFreshSettings | UI |
| Switching Blur ↔ Sharpen only for a new layer, and resets its settings; an applied layer keeps its kind | ui/EditorScreen.kt:786-788 | UI: ToolBarsTest.detailOpensABlurLayerAndSwitchesToSharpenWithFreshSettings, anAppliedBlurLayerKeepsItsKind | UI |
| Main row: "<Type> / Blur" or "Sharpen / Amount" slider 0..100, integer | ui/EditorScreen.kt:861-867, 872-880 | UI: ToolBarsTest.detailOpensABlurLayerAndSwitchesToSharpenWithFreshSettings | UI |
| More (Blur): Type row Gaussian / Lens / Motion / Zoom | ui/EditorScreen.kt:855-858 | UI: ToolBarsTest.blurMoreHasTheTypeRowAndEachTypesSliders | UI |
| More (Lens): Range 0..100 (default 10), Highlights 0..100 (default 30) | ui/EditorScreen.kt:834-838, model/Document.kt:225-228 | UI: ToolBarsTest.blurMoreHasTheTypeRowAndEachTypesSliders | UI |
| More (Motion): Angle 0..180 | ui/EditorScreen.kt:832 | UI: ToolBarsTest.blurMoreHasTheTypeRowAndEachTypesSliders | UI |
| More (Sharpen): Radius 0..100 (default 30), Clarity −100..100 | ui/EditorScreen.kt:820-824 | UI: ToolBarsTest.sharpenMoreHasRadiusAndClarity | UI |
| More (Detail) remembered across sessions | ui/EditorScreen.kt:317 | UI: SettingsTest.savedToolSettingsLoadOnStart | UI |
| Blur/Sharpen with Amount 0 (and Clarity 0) is "not edited": ✓ adds nothing | model/Document.kt:259-260 | unit: EditorStateTest.blurAndSharpenWithNothingToDoAreNotEdits | unit |
| Gaussian blur follows the bell curve (sigma 2% of height at 100) | gl/Pipeline.kt:428-452 | GPU: gaussianBlurFollowsTheBellCurve | GPU |
| Masked blur doesn't smear the subject into the background (mask-weighted) | gl/Pipeline.kt:1073-1075 | GPU: maskedBlurDoesntSmearTheSubjectIntoTheBackground | GPU |
| Lens blur: highlight becomes a disc (bokeh) | gl/Pipeline.kt:398-427 | GPU: lensBlurTurnsAHighlightIntoADisc | GPU |
| Lens blur keeps the focused depth sharp | gl/Pipeline.kt:398-427 | GPU: lensBlurKeepsTheFocusedNearHalfSharp | GPU |
| Lens / Zoom: focus point mark on the photo; tap or drag moves it (clamped 0..1) | ui/EditorScreen.kt:391-392, 421-425, 884-894 | UI: ToolBarsTest.blurMoreHasTheTypeRowAndEachTypesSliders, CanvasTest.aTapMovesTheLensFocus | UI |
| Focus mark hidden in mask mode | ui/EditorScreen.kt:528 | none | UI |
| Lens without a depth map yet: status row replaces the slider (measuring / download % / retry) | ui/EditorScreen.kt:862-863 | none | UI (fake ModelStore) |
| Depth map measured once per photo, only when a Lens blur or depth step needs it | ui/EditorScreen.kt:158-162, 348-366, Segment.kt:57-64 | none | UI |
| Motion blur streaks along its angle; Zoom blur keeps its centre | gl/Pipeline.kt:375-397 | GPU: motionBlurStreaksAlongItsAngleAndZoomKeepsItsCentre | GPU |
| Sharpen raises edge contrast, leaves flat areas | gl/Pipeline.kt:935 | GPU: sharpenRaisesEdgeContrastAndLeavesFlatAreas | GPU |
| Clarity (midtone local contrast) | gl/Pipeline.kt:935 | none | GPU |
| Blurred export tiles match the full render (Gaussian, Lens, Motion, Zoom) | gl/Pipeline.kt:1217, ImageIo.kt:110-115 | GPU: blurredExportTileMatchesTheFullRender | GPU |

## 21. Liquify

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Bar: Push, Bloat, Pinch, Twirl, Restore icon chips | ui/LiquifyBar.kt:119-125 | UI: ToolBarsTest.liquifyHasFiveBrushesSizeAndMore | UI |
| Main row: "<Brush> / Size" slider 10..150 dp | ui/LiquifyBar.kt:113 | UI: ToolBarsTest.liquifyHasFiveBrushesSizeAndMore | UI |
| More: Strength 5..100, Softness 0..100 | ui/LiquifyBar.kt:103-104 | UI: ToolBarsTest.liquifyHasFiveBrushesSizeAndMore | UI |
| More (Twirl only): Direction Clockwise / Counterclockwise | ui/LiquifyBar.kt:106-110 | UI: ToolBarsTest.liquifyHasFiveBrushesSizeAndMore | UI |
| Each touch is one stroke with the current settings | ui/LiquifyBar.kt:49-57 | UI: CanvasTest.eachLiquifyTouchIsOneStroke | unit (LiquifyTouch with fake view) |
| Dabs a sixth of the radius apart | model/Liquify.kt:22-23 | unit: LiquifyTest.growingAStrokeMatchesReplayingIt (indirect) | unit |
| Hold to apply: Bloat, Pinch, Twirl, Restore keep adding a dab every 33 ms while the finger rests; Push doesn't | ui/LiquifyBar.kt:35-44, 51, 68 | none | unit (Robolectric looper) |
| Hold stops on Up/Cancel and if the tool changes mid-hold | ui/LiquifyBar.kt:38, 63 | none | unit |
| Empty field changes nothing | model/Liquify.kt:31-35 | unit: LiquifyTest.emptyFieldChangesNothing | unit |
| Push drags pixels along the stroke; nothing outside the brush | model/Liquify.kt:126 | unit: LiquifyTest.pushDragsPixelsAlongTheStroke | unit |
| Bloat reads toward the centre, Pinch away | model/Liquify.kt:127-128 | unit: LiquifyTest.bloatReadsTowardTheCentreAndPinchAway | unit |
| Twirl keeps distance from centre; clockwise by default, reverse = counterclockwise | model/Liquify.kt:129-136 | unit: LiquifyTest.twirlKeepsDistanceFromTheCentre | unit |
| Restore at full strength returns exactly to zero | model/Liquify.kt:119-121, 149 | unit: LiquifyTest.restoreAtFullStrengthReturnsExactlyToZero | unit |
| Growing stroke continues incrementally; undo to fewer strokes replays from zero | model/Liquify.kt:44-65 | unit: LiquifyTest.growingAStrokeMatchesReplayingIt | unit |
| Softness falloff (0 = full to the edge) | model/Liquify.kt:110-117 | none | unit |
| Zero offsets / full restore keep exact pixels on GPU | gl/Pipeline.kt:923 | GPU: liquifyWithoutOffsetsAndAfterFullRestoreKeepsExactPixels | GPU |
| GPU reads where the grid points (bilinear) | gl/Pipeline.kt:332-357 | GPU: liquifyReadsWhereTheGridPoints | GPU |
| Liquified export tile matches the full render (overlap = largest offset) | ImageIo.kt:112, model/Liquify.kt:69 | GPU: liquifiedExportTileMatchesTheFullRender | GPU |
| Liquify layer is maskable | ui/LiquifyBar.kt:124 | none | GPU |
| Liquify tool, Strength, Softness, Size, Direction, More remembered across sessions | ui/EditorScreen.kt:309-322 | UI: SettingsTest.savedToolSettingsLoadOnStart, changesAreSavedAsTheyHappen | UI |

## 22. Crop

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Crop opens on the current document crop, ratio unlocked; whole frame shown with the crop drawn over it | ui/EditorScreen.kt:250-254, 340-342 | UI: ToolBarsTest.cropBarHasPresetsTurnFlipResetAndApply | UI |
| Crop is a document setting after all layers; rail doesn't change; ✓ is one undo step | model/Crop.kt:7-12, ui/EditorScreen.kt:256-260 | UI: UndoTest.applyingCropIsOneUndoStep | UI |
| Overlay: dim outside (55%; Spec 65%), rule-of-thirds always (Spec: while dragging), white border, L corners (Spec: also edge handles) | ui/CropBar.kt:142-165 | none | UI (screenshot) |
| Drag a corner (28dp hit) resizes | ui/CropBar.kt:80-83, 111-114 | none | UI |
| Drag an edge resizes, only when no ratio is locked | ui/CropBar.kt:84-90 | none | UI |
| Drag inside moves the crop | ui/CropBar.kt:91, 109 | UI: CanvasTest.dragInsideACropMovesIt | UI |
| Touch outside the crop does nothing (one finger doesn't pan in Crop) | ui/CropBar.kt:91, 98 | none | UI |
| Minimum crop 5% of the photo height | ui/CropBar.kt:102-103, 137 | none | unit (CropTouch with fake view) |
| Locked ratio holds while resizing a corner, anchored at the opposite corner | ui/CropBar.kt:117-125 | none | unit |
| Crop never leaves the photo; a drag past an edge slides along it | ui/CropBar.kt:126-129, model/Crop.kt:102-108 | unit: CropTest.moveTowardStopsAtThePhotoEdge | unit |
| Presets: Free, Original, 1:1, 4:5, 3:2, 16:9 | ui/CropBar.kt:38, 178-198 | UI: ToolBarsTest.cropBarHasPresetsTurnFlipResetAndApply | UI |
| Picking a preset sets the largest crop at that ratio, nearest the centre | ui/CropBar.kt:195, model/Crop.kt:120-143 | unit: CropTest.maximizedFillsThePhotoAtTheRatio | unit |
| Keystoned photo: largest crop uses the wide end | model/Crop.kt:120-143 | unit: CropTest.maximizedUsesTheWideEndOfAKeystone | unit |
| A new preset follows the crop's current orientation (Original: the photo's) | ui/CropBar.kt:188-193 | UI: ToolBarsTest.aPresetTakesTheCropsOrientationAndTappingItAgainTurnsIt | UI |
| Tap the selected preset again turns it (portrait ↔ landscape); label flips ("5:4") | ui/CropBar.kt:180-189 | UI: ToolBarsTest.aPresetTakesTheCropsOrientationAndTappingItAgainTurnsIt | UI |
| Free unlocks the ratio without changing the crop | ui/CropBar.kt:184 | UI: ToolBarsTest.aPresetTakesTheCropsOrientationAndTappingItAgainTurnsIt | UI |
| More ("Rotate and perspective"): Rotate ±45° in 0.1° steps, shown "N.N°" | ui/CropBar.kt:45-62 | UI: ToolBarsTest.cropMoreHasRotateInTenthsAndKeystones | UI |
| More: Vertical and Horizontal keystone −100..100 | ui/CropBar.kt:47-48 | UI: ToolBarsTest.cropMoreHasRotateInTenthsAndKeystones | UI |
| Straighten/keystone reset the crop to the largest that fits at the locked or current ratio | ui/CropBar.kt:56-57 | unit: CropTest.straightenedCropFitsInsideThePhoto (fit), UI: ToolBarsTest.cropMoreHasRotateInTenthsAndKeystones | unit + UI |
| Crop More remembered across sessions | ui/EditorScreen.kt:318 | UI: SettingsTest.savedToolSettingsLoadOnStart | UI |
| Bar: Turn 90° clockwise (crop turns with it; locked ratio inverts) | ui/CropBar.kt:207, model/Crop.kt:111 | unit: CropTest.quarterTurnIsClockwise, CropTest.turningKeepsTheSameCropOfThePhoto, UI: ToolBarsTest.cropBarHasPresetsTurnFlipResetAndApply | unit |
| Bar: Flip horizontally (as seen, crop mirrored) | ui/CropBar.kt:208, model/Crop.kt:114 | unit: CropTest.flipMirrorsAndTwiceIsIdentity, UI: ToolBarsTest.cropBarHasPresetsTurnFlipResetAndApply | unit |
| Four turns / two flips = identity | model/Crop.kt:111-114 | unit: CropTest.quarterTurnIsClockwise, CropTest.flipMirrorsAndTwiceIsIdentity | unit |
| Bar: Reset (to no crop, unlocked), disabled when already identity | ui/CropBar.kt:210 | UI: ToolBarsTest.cropBarHasPresetsTurnFlipResetAndApply | UI |
| Bar: ✓ Apply crop | ui/CropBar.kt:211 | UI: UndoTest.applyingCropIsOneUndoStep | UI |
| Frame is the box around the warped photo; all of it reachable | model/Crop.kt:19-29 | unit: CropTest.frameShowsTheWholeWarpedPhoto | unit |
| Identity crop maps output onto the photo; crop rect picks that part | model/Crop.kt:32-42 | unit: CropTest.identityMapsOutputOntoThePhoto, CropTest.cropRectPicksThatPartOfThePhoto | unit |
| Layers, masks, pins, focus keep uncropped coordinates (re-crop never loses edits) | ui/EditorScreen.kt:341, PhotoView.kt:178-184 | none | UI |
| Crop export is that region pixel for pixel | ImageIo.kt:156-176 | GPU: cropExportIsThatRegionPixelForPixel | GPU |
| Quarter-turn export is the rotated photo; four turns are the original | ImageIo.kt:124 | GPU: quarterTurnExportIsTheRotatedPhotoAndFourTurnsAreTheOriginal | GPU |
| Straightened export matches bilinear sampling across tiles | gl/Pipeline.kt:857-859 | GPU: straightenedExportMatchesBilinearSamplingAcrossTiles | GPU |
| Spec: Crop modes (Straighten/Rotate/Perspective/Scale) with Auto, Flip V, Scale + Fill edges, size info row, fine grid, Free perspective corners; not built | design/UI.md:73-89 | none | n/a |

## 23. AI eraser

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| AI opens a new Retouch draft; one finger paints the layer's mask directly with red tint and loupe | ui/EditorScreen.kt:734, 394-398, 412, 418 | UI: ToolBarsTest.aiBarErasesOnlyOnceThereIsAMask | UI |
| Main row "Erase / Size" (4..150, shared with mask brush size); More: Softness, Flow (shared with the mask brush) | ui/EraseBar.kt:18-24 | UI: ToolBarsTest.aiBarErasesOnlyOnceThereIsAMask | UI |
| Bar: back, AI settings (gear), Mask button, Erase button (no ✓: running is applying) | ui/EraseBar.kt:31-44 | UI: ToolBarsTest.aiBarErasesOnlyOnceThereIsAMask, AiSettingsTest.theAiBarOpensAiSettings | UI |
| Erase disabled until the mask is active; spinner while running | ui/EraseBar.kt:32-36 | UI: ToolBarsTest.aiBarErasesOnlyOnceThereIsAMask | UI |
| Status "Downloading AI model… N%" then "Erasing…" in the main row (on the phone; remote runs: see §31) | ai/OnDevice.kt, ui/EraseBar.kt:20-26 | UI: ToolBarsTest.aiBarErasesOnlyOnceThereIsAMask ("Erasing…" only) | UI (fake ModelStore) |
| Hole = rendered mask ≥ 50%; widened by max(3, h/300) px | ui/EditorScreen.kt:616-634, Segment.kt:44-58 (EraseArea.of) | none | unit (Eraser) |
| Mask bounds; context square 2× the hole's long side, min 512, kept on the photo | model/Eraser.kt:6-35 | unit: EraserTest.boundsOfTheMarkedPixels, EraserTest.contextIsCentredSquareAtLeastMinAndStaysOnThePhoto | unit |
| Erode grows the hole | model/Eraser.kt:38-53 | unit: EraserTest.erodeGrowsTheHole | unit |
| LaMa at 512×512 on the crop (EraseArea); only hole pixels come from the model (or a remote provider, scaled to the crop), the rest stays the photo | Segment.kt:14-58 (EraseArea.paste), 118-130, cpp/segment.cpp:239-280 | GPU (model): eraserContinuesStripesAcrossTheHole | GPU |
| LaMa weights freed after each run | cpp/segment.cpp:274 | none | manual (memory) |
| Result: new Retouch layer at the bottom of the stack (every other layer still applies); editing an existing Retouch layer replaces it | ui/EditorScreen.kt:589-614 | UI: AiSettingsTest.theFirstSendAsksAndSendOnlyWhenAgreed (remote, new layer) | UI |
| Result dropped if the draft changed while erasing | ui/EditorScreen.kt:606 | none | UI |
| Errors: "Couldn't erase: paint over what to remove first" / "nothing to erase" / "the model couldn't run on this phone" / download errors / the provider's message as snackbar | ui/EditorScreen.kt:600-603, 625-630, 1251 | UI: AiSettingsTest.aProviderErrorIsShown (provider message) | UI |
| Retouch shows its patch only inside its rect and mask | gl/Pipeline.kt:930 | GPU: retouchShowsItsPatchOnlyInsideItsRectAndMask | GPU |
| Retouched export tile matches the full render | ImageIo.kt:153-185 | GPU: retouchedExportTileMatchesTheFullRender | GPU |
| Spec: AI mode menu (Fill, Upscale, Replace background, Ask), run button with provider caret; not built (M8 part 2). Cancel for remote jobs is built (§31) | design/UI.md:59-71, 246 | none | n/a |

## 24. AI models (download, verify, run)

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Models listed in assets/models.json (file, url, sha256, size): subject, sam_encoder, sam_decoder, depth, eraser | ModelStore.kt:21, app/src/main/assets/models.json | unit: ModelStoreTest.everyListedModelHasAFileUrlHashAndSize | unit (parse json) |
| Stored in external files dir `models/` (fallback filesDir); `adb push` works | ModelStore.kt:22 | none | manual |
| Existing file used only if its SHA-256 matches; verified once per process | ModelStore.kt:26-27 | unit: ModelStoreTest.aFileAlreadyThereIsUsedOnlyIfItsHashMatches, partsAreJoinedInOrderAndCheckedOnce | unit (Robolectric, temp dir) |
| Download to `.part`, SHA-256 while writing; mismatch deletes it: "Downloaded model is damaged, try again" | ModelStore.kt:28-32 | unit: ModelStoreTest.aDamagedDownloadIsDeletedAndReported | unit (local HTTP server) |
| Multipart URLs (space-separated) joined in order (LaMa: 2 parts) | ModelStore.kt:29, 44 | unit: ModelStoreTest.partsAreJoinedInOrderAndCheckedOnce, everyListedModelHasAFileUrlHashAndSize | unit (local HTTP server) |
| Non-200: "Model download failed (HTTP n)"; 15 s connect / 30 s read timeouts | ModelStore.kt:46-49 | unit: ModelStoreTest.anHttpErrorIsReportedWithItsCode (timeouts: none) | unit |
| Rename failure: "Can't save the model" | ModelStore.kt:33 | none | unit |
| Download progress 0..1 shown as "Downloading AI model… N%" | ModelStore.kt:57, ui/EditorScreen.kt:355, 376, 451 | UI: MaskBarTest.subjectStatusAndHintShowInTheMainRow (text only) | UI |
| One download at a time (synchronized) | ModelStore.kt:25 | none | unit |
| Retry: tapping a "Couldn't…" status reruns the failed depth and subject jobs | ui/MaskBar.kt:364-370, ui/EditorScreen.kt:137, 349, 368 | UI: MaskBarTest.subjectStatusAndHintShowInTheMainRow (subject; depth: none) | UI |
| GPU backend (AUTO) on phones, CPU on the emulator; one shared runtime and GPU program cache file | Segment.kt:20, 114, cpp/segment.cpp:52-91 | none | manual |
| Native lib missing: model calls return null ("couldn't run on this phone") | Segment.kt:17, 24, ui/EditorScreen.kt:357, 381 | none | unit |
| Depth input: long side ~518, multiples of 14 | Segment.kt:57-64 | none | unit (extract size math) |
| Low memory: on trim level RUNNING_CRITICAL or above (incl. UI_HIDDEN, BACKGROUND) every loaded model, the SAM embedding and the MNN runtime are freed on a background thread (log "trim memory N: AI models freed"); each reloads on next use. Subject and depth masks are kept (~1 MB each; dropping them would blank the preview until recomputed) | BloomApp.kt (onTrimMemory), Segment.kt (release), cpp/segment.cpp (release0) | none | manual (`am send-trim-memory`) |
| Out of memory in a model run: "Couldn't … : not enough memory. Close other apps and try again" instead of a crash or a byte count | ui/EditorScreen.kt (reason) | none | UI |
| Spec: Settings › On-device models (size, delete); not built | design/UI.md:47 | none | n/a |

## 25. Export

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Export uses the document at the moment of export (layers, crop, deband, size, subjects, depth) | ui/EditorScreen.kt:476-494 | UI: ExportMenuTest.exportWritesTheFileAndSaysSaved (partial: writes the file) | UI |
| Result snackbar "Saved" / "Export failed: …" | ui/EditorScreen.kt:492 | UI: ExportMenuTest.exportWritesTheFileAndSaysSaved ("Saved" only) | UI |
| Out of memory: "Not enough memory to export. Pick a smaller Size in the Export menu" | ui/EditorScreen.kt (export) | none | UI |
| Progress 0..1 rises monotonically and ends at 1 | ImageIo.kt:87-89, 176, 184, 198 | GPU: pngExportKeepsLevelsBetweenEightBitSteps (asserts progress) | GPU |
| Full resolution, 1024 px tiles, rendered on an offscreen GL context | ImageIo.kt:30, 135-185, gl/Offscreen.kt | GPU: paddedTileMatchesFullRenderWithFeather | GPU |
| No visible layers and no crop/scale: tiles copied without the GPU | ImageIo.kt:135, 179 | none | GPU |
| Tiles overlap by the largest reach (feather, deband, subject edge, blur, liquify); blur pads align to 64 px | ImageIo.kt:106-115 | GPU: paddedTileMatchesFullRenderWithFeather, blurredExportTileMatchesTheFullRender | GPU |
| JPEG: quality 95, sensor orientation kept with EXIF orientation tag | ImageIo.kt:194, 131-132 | GPU: jpegExportKeepsSensorOrientationAndExifButNotPixelLayoutTags | GPU |
| WebP: lossy, quality 95, drawn upright, EXIF copied; fails clearly past 16383 px a side | ImageIo.kt:222-231 | GPU: webpExport | GPU |
| HEIC / AVIF: quality 95 via androidx.heifwriter (bitmap input), drawn upright, no EXIF. Long side capped by GL_MAX_TEXTURE_SIZE | ImageIo.kt:216-221 | GPU: heicExport, avifExport | GPU |
| 16-bit PNG keeps levels between 8-bit steps | ImageIo.kt:133, gl/Pipeline.kt:461-486 | GPU: pngExportKeepsLevelsBetweenEightBitSteps | GPU |
| Everything but JPEG (and anything cropped or scaled) drawn upright; orientation tag reset to normal | ImageIo.kt:125-130, 237 | GPU: pngExportIsUpright | GPU |
| Masks line up in rotated (EXIF) tiles | ImageIo.kt:95-102 | GPU: rotatedExportTilesMatchTheUprightPreview | GPU |
| Size 50% / 25%: output averages source pixels (n×n box) | ImageIo.kt:150, gl/Pipeline.kt:857-859 | GPU: halfSizeExportAveragesPixels | GPU |
| EXIF copied from the source, minus pixel-layout and thumbnail tags | ImageIo.kt:204-239 | GPU: jpegExportKeepsSensorOrientationAndExifButNotPixelLayoutTags, webpExport | GPU (instrumented, file I/O) |
| Source without readable EXIF (CR3): export still succeeds without tags | ImageIo.kt:230 | none | GPU |
| RAW export: full decode (35% of the progress bar), always upright | ImageIo.kt:84-89, 196 | none | manual |
| Ultra HDR: export uses the whole photo's gain map at preview size, so it matches the preview | ImageIo.kt:140-148 | none | GPU |
| Temp file in cache dir deleted after copy | ImageIo.kt:192-201 | none | GPU |
| Output dithered to 8-bit; dithering keeps fractional levels on average | gl/Pipeline.kt:1235 | GPU: ditherKeepsFractionalLevelsOnAverage | GPU |
| Half-float truncation probe: passes add half a step on GPUs that truncate | gl/Pipeline.kt:115-117, 520-534 | none | GPU |
| Spec: export sheet (quality, remove location, size estimate, Share); not built | design/UI.md:43 | none | n/a |

## 26. System back and discard dialogs

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Back is handled only while a photo is open (otherwise the app exits) | ui/EditorScreen.kt:516 | UI: BackTest.backIsHandledOnlyWhileAPhotoIsOpen | UI (Espresso pressBack) |
| Back order: dialog open → close it; bubble open → close it; mask mode → back to the tool; tool/Crop open → leave tool; else → close photo | ui/EditorScreen.kt:516-524 | UI: BackTest.backGoesFromMaskModeToTheToolToToolsToHome, leavingAChangedToolAsksAndKeepEditingStays, LayerBubbleTest.backClosesTheBubbleFirst | UI |
| Leaving a dirty tool (left slot or back) asks "Discard changes?" (Discard / Keep editing) | ui/EditorScreen.kt:514, 624-632 | UI: BackTest.leavingAChangedToolAsksAndKeepEditingStays, leavingAChangedCropAsks | UI |
| Leaving an unchanged tool closes it without asking | ui/EditorScreen.kt:514 | UI: BackTest.backGoesFromMaskModeToTheToolToToolsToHome | UI |
| Close photo after this visit changed the document asks "Keep this project? Discard deletes it." (a reopened project: "Keep your changes? Discard takes the project back…") with Keep project / Cancel / Discard; a dirty tool first asks "Discard changes?" | ui/EditorScreen.kt:510-513 | UI: BackTest.closingAPhotoWithEditsAsksKeepDiscardOrCancel, closingWithAChangedToolAsksToDiscardItFirst, anOpenedProjectAsksAboutItsChanges | UI |
| Close photo with nothing to lose closes at once, back to the empty state | ui/EditorScreen.kt:511 | UI: BackTest.backGoesFromMaskModeToTheToolToToolsToHome | UI |
| Dialog dismiss (outside tap / back) keeps editing | ui/EditorScreen.kt:626, 518 | UI: BackTest.leavingAChangedToolAsksAndKeepEditingStays | UI |
| Spec: "Keep project / Discard / Cancel" and a small discard bubble; built as an AlertDialog | design/UI.md:40, 219 | none | n/a |

## 27. Settings persistence

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| SharedPreferences "tools" saves brush size, softness, flow, Liquify size/strength/softness, Twirl direction, More states (mask, Liquify, Detail, Crop), Liquify tool | ui/EditorScreen.kt:302-330 | UI: SettingsTest.savedToolSettingsLoadOnStart | UI (relaunch activity) |
| Saved on every change (snapshotFlow) | ui/EditorScreen.kt:323-329 | UI: SettingsTest.changesAreSavedAsTheyHappen | UI |
| Unknown saved Liquify tool name ignored | ui/EditorScreen.kt:322 | UI: SettingsTest.anUnknownSavedLiquifyBrushIsIgnored | unit |
| Not persisted: export size, rail collapsed, mask sign, current Color parameter, edits (no projects until M7) | ui/EditorScreen.kt:475, ui/LayerRail.kt:96, ui/EditorScreen.kt:125 | none | n/a |

## 28. Theme, scroll indicators, accessibility basics

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Dark theme only, tokens from the spec (Canvas, Surface, Pollen…) | ui/Theme.kt:27-55 | none | UI (screenshot) |
| Scrolling rows fade the edge with more content and draw ‹ › there | ui/Theme.kt:57-82 | none | UI (screenshot) |
| Stroke icons on a 24 grid, no icon library | ui/Theme.kt:85-152 | none | n/a |
| Every icon button has a contentDescription (tool names, "X. Back to tools", mask button, apply) | ui/EditorScreen.kt:798, 807; ui/MaskBar.kt:102, 114 | UI: AccessibilityTest.everyControlHasAName | UI (semantics) |
| Changed state isn't color-only: dots on changed params, sign badges, strike-through for hidden | ui/EditorScreen.kt:920-921, ui/MaskBar.kt:440-441, ui/LayerRail.kt:239 | none | UI |
| Touch targets at least 48 dp: param chips, mask type and text chips, color sample swatches (24 dp drawn), rail items (rail 48 dp wide), collapsed rail tab; exception: the rail's scroll arrows (48×32, overlaying the list; screen readers scroll the list instead) | ui/EditorScreen.kt (ParamChip), ui/MaskBar.kt, ui/LayerRail.kt | none | UI (uiautomator dump) |
| Sliders read their name and value ("Exposure", "+1.0 EV"; "Opacity", "80%") | ui/LayerRail.kt (sliderLabel) | UI: ColorToolTest.exposureReadsInStopsAndSnapsToATenth, ToolBarsTest (slider state checks) | UI (semantics) |
| The bubble's tap-outside layer is hidden from screen readers (Back closes the bubble) | ui/EditorScreen.kt (bubble) | UI: AccessibilityTest.everyControlHasAName (no unnamed control in the bubble sweeps) | UI |
| Font scale up to 2.0: slider labels, value texts, tool rows, bubble actions and the Open photo button grow instead of clipping; font scale changes keep the open photo (no activity restart) | ui/*.kt (widthIn/heightIn), AndroidManifest.xml (configChanges) | none | manual (font_scale 1.3, 2.0) |
| Spec: light theme, Appearance settings, tabular figures, motion (200 ms cross-fades), canvas a11y actions (zoom/pan), RTL mirroring; not built | design/UI.md:48, 356, 411-421, 443 | none | n/a |

## 29. Rendering pipeline core

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| One GPU pass per visible layer, bottom to top, ping-pong RGBA16F (RGBA8 fallback) | gl/Pipeline.kt:111, 256-310 | none | GPU |
| Hidden or 0-opacity layers are skipped | gl/Pipeline.kt:227 | GPU: hiddenLayerIsSkipped | GPU |
| Render cached until the visible layer list changes | gl/Pipeline.kt:228 | none | GPU |
| Masks cached; masks that read below recompute when layers below change | gl/Pipeline.kt:598-618 | GPU: luminanceFollowsEarlierLayers | GPU |
| Deleted layers free their mask, warp and patch textures | gl/Pipeline.kt:229-231 | none | GPU |
| Result mipmapped for the minified preview | gl/Pipeline.kt:251 | none | GPU |
| Mask geometry in upright coordinates, so preview and rotated tiles agree | gl/Pipeline.kt:72-74, 718-724 | GPU: rotatedExportTilesMatchTheUprightPreview | GPU |
| Eyedropper sample is 3×3 averaged sRGB of the layers below | gl/Pipeline.kt:487-497 | GPU: eyedropperReadsTheLayersBelow | GPU |
| Mask readback for AI Erase (preview size bytes) | gl/Pipeline.kt:322-330, PhotoView.kt:161-166 | none | GPU |

## 30. Projects and Home

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| Home lists projects with edits as a 3-column grid of thumbnails, most recently saved first; with none it shows the tagline | ui/EditorScreen.kt (EmptyState), Project.kt (list) | UI: ProjectsTest.homeListsSavedProjectsAndATapOpensOne, firstRunHomeHasTheTaglineAndOpenButtonsAndNoEditing | UI |
| Tap a project opens it with its document (not an undo step) | ui/EditorScreen.kt (openProject) | UI: ProjectsTest.homeListsSavedProjectsAndATapOpensOne | UI |
| Long-press asks "Delete <name>? Its edits can't be brought back. The photo in your gallery stays." Delete / Cancel | ui/EditorScreen.kt (deleting) | UI: ProjectsTest.longPressAsksBeforeDeletingAProject | UI |
| Opening a photo copies it into a new project folder (original.<ext>); a file that fails to open leaves no project | Project.kt (create), ui/EditorScreen.kt (open) | UI: ProjectsTest.openPhotoAsksThePickerForImagesAndOpensACopyAsANewProject, aFileThatIsNoImageShowsWhyAndLeavesNoProject | UI |
| Autosave: the committed document 2 s after it last changed, and when the app leaves the screen | ui/EditorScreen.kt (autosave) | UI: ProjectsTest.editsAutosaveAfterTwoSeconds (on leaving the screen: none) | UI |
| Close: Keep project saves (a project left with no edits is removed, without asking); Discard deletes a new project, or takes a reopened one back to how it was opened; Cancel stays | ui/EditorScreen.kt (closeProject, keepAsk) | UI: ProjectsTest.keepSavesANewProjectToHomeAndDiscardOfAReopenedOneRestoresIt, discardDeletesANewProject, BackTest.closingAPhotoWithEditsAsksKeepDiscardOrCancel | UI |
| A photo closed without edits leaves no project | ui/EditorScreen.kt (closeProject) | UI: ProjectsTest.aPhotoClosedWithoutEditsLeavesNoProject | UI |
| Saved project reopens with its document, Retouch patch pixels (lossless) and a thumbnail of the crop; an unused patch file is removed | Project.kt (save, open) | GPU: ProjectSaveTest.savedProjectReopensWithPatchPixelsAndThumbnail | GPU |
| doc.json round trip | Project.kt | unit: ProjectTest.documentSurvivesJson | unit |

## 31. AI providers, Settings, consent (M8 part 1)

Remote adapters are tested only against fake servers (no real IOPaint, ComfyUI, fal or LLM server was available).

| Feature / behaviour | Where (file:line) | Test today | Testable how |
|---|---|---|---|
| `AiProvider` interface: `models(cap)`, `test()`, `run(cap, model, request, status)`; cancel = coroutine cancel | ai/AiProvider.kt | (through the adapters below) | unit / UI |
| On-device provider "Phone": SEGMENT (IS-Net + MobileSAM, picked by pins), ERASE (LaMa); subject masks and AI Erase go through the chosen provider (default Phone, no behaviour change); depth still calls Segment directly | ai/OnDevice.kt, ui/EditorScreen.kt:418, 465-467, 589-614 | UI: MaskBarTest.subjectStatusAndHintShowInTheMainRow, ToolBarsTest.aiBarErasesOnlyOnceThereIsAMask; GPU (model): eraserContinuesStripesAcrossTheHole | UI / GPU |
| Config: providers (id, type, name, URL, models found, ComfyUI workflows), choice per capability, consented providers, Hide cloud features; JSON in prefs "ai"; a removed provider takes its choices and consent with it; a choice no longer offered falls back to the first option (Phone first) | ai/Providers.kt:15-76 | unit: AiTest.choiceDefaultsToThePhoneAndFollowsTheUsersPick, hidingCloudFeaturesLeavesOnlyThePhone, consentIsPerProviderAndGoesWithIt, configSurvivesJsonWithoutKeys | unit |
| Keys: AES-GCM with a key in the Android Keystore, ciphertext in prefs "ai_keys" (never in the config, a project or logs); Replace / Forget only, never shown again | ai/Providers.kt:85-123 | UI: AiProvidersTest.keysAreEncryptedAtRestAndComeBack, AiSettingsTest.addAProviderTestItSaveItAndPickIt | UI (instrumented) |
| HTTP: HttpURLConnection, 15 s connect / per-call read timeout, 64 MB reply cap, no redirects (they could carry a key elsewhere), http(s) only, one retry on a network error (not on 4xx, not for fal's paid submit), cancel closes the connection; errors carry the server's message ("HTTP 400: …", FastAPI detail, OpenAI error.message), printable, ≤ 200 chars; "Uploading N %" for bodies over 256 KB | ai/Http.kt | unit: AiTest.httpReadsRepliesAndSendsBodies, httpErrorsCarryTheServersMessageAndAreNotRetried, httpRetriesANetworkErrorOnceUnlessTold, httpTimesOutRefusesHugeRepliesAndRedirects, cancellingClosesTheConnectionAtOnce, serverErrorMessagesAreShortAndReadable | unit |
| Result images refused over 64 MP or when not an image | ai/Http.kt (decodeImage) | none | UI |
| IOPaint: Test = GET /api/v1/server-config (inpaint models → Erase, need_prompt models → Fill, RealESRGAN plugin → Upscale); run switches the server's model (POST /api/v1/model) if needed, then POST /api/v1/inpaint (crop + white-on-black mask, base64) or /api/v1/run_plugin_gen_image; status counts seconds (no progress API) | ai/IoPaint.kt | unit: AiTest.ioPaintModelsFromServerConfig, ioPaintInpaintRequest; UI: AiProvidersTest.ioPaintSwitchesModelAndErases, ioPaintTestListsModelsAndErrorsShowTheServersMessage, cancellingClosesTheConnectionQuickly | unit / UI (fake server) |
| ComfyUI: user's API-format workflows per capability, nodes found by title bloom_image / bloom_mask / bloom_prompt / bloom_output; checked when loaded; Test = GET /system_stats; run uploads crop and mask (/upload/image, multipart), queues /prompt, polls /history and /queue ("Queued, N ahead", "Running, N s"), downloads /view; Cancel deletes the job from the queue and interrupts it. Samples: docs/comfyui/inpaint.json (Erase / Fill, SD 1.5 inpainting), upscale.json (RealESRGAN x4 → 2×) | ai/ComfyUi.kt, docs/comfyui/ | unit: AiTest.comfyUiSampleWorkflowsAreValidAndGetFilledIn, comfyUiHistoryAndQueue; UI: AiProvidersTest.comfyUiUploadsQueuesReportsProgressAndDownloads, comfyUiCancelTakesTheJobOffTheServer | unit / UI (fake server) |
| fal (own key): model table (Erase: fal-ai/object-removal/mask, fal-ai/bria/eraser; Fill: fal-ai/flux-pro/v1/fill, fal-ai/flux-lora/inpainting; Upscale: fal-ai/esrgan, fal-ai/clarity-upscaler); Test = GET api.fal.ai/v1/models/pricing (free, needs a valid key); queue submit with data-URI image and mask, poll status_url ("Queued, N ahead", "Running on fal, N s"), fetch response_url; status/result/cancel URLs must be on queue.fal.run; result image downloaded without the key, https or data URI only; Cancel = PUT cancel_url | ai/Fal.kt | unit: AiTest.falReplies; UI: AiProvidersTest.falQueuesPollsAndFetchesTheResult, falErrorsAndCancel | unit / UI (fake server) |
| OpenAI-compatible: Test = GET /v1/models (URL with or without /v1) → Ask models; running Ask is part 2 | ai/OpenAiCompatible.kt | unit: AiTest.openAiModels; UI: AiProvidersTest.openAiCompatibleListsModels | unit / UI |
| Settings screen (from Home's gear and the AI bar's gear; Back closes): AI providers (per capability a radio list of provider · model; provider rows with Replace/Add key, Forget key, Delete; + Add provider form: type, name, address, key, ComfyUI workflow files; http warning; Test connection lists what it found and Save is enabled only after it passed for exactly what is entered), On-device models (name, size or "Not downloaded", delete file), Privacy (Reset consent choices, Hide cloud features), About (version, licenses of models and libraries). Appearance left out (dark only) | ui/SettingsScreen.kt | UI: AiSettingsTest.addAProviderTestItSaveItAndPickIt, theAiBarOpensAiSettings | UI |
| Consent sheet before the first erase on a provider off the phone: what is sent (crop size and its mask), where (host; "fal.ai"), not stored by Bloom; Send / Not now / Use a different provider (lists the other options; picking one sets the Erase choice) / Don't ask again for <provider> | ui/SettingsScreen.kt (ConsentSheet), ui/EditorScreen.kt:616-634, 863-883 | UI: AiSettingsTest.theFirstSendAsksAndSendOnlyWhenAgreed | UI |
| Remote erase: the tool stays locked, the status row shows the provider's live status with Cancel; Cancel stops the job and keeps the tool and mask | ui/EraseBar.kt:20-26, ui/EditorScreen.kt:589-614 | UI: AiSettingsTest.aRemoteEraseShowsItsStatusAndCancels | UI |
| Cleartext http allowed (users' own servers on a home network) | AndroidManifest.xml | UI: AiSettingsTest (fake server on http://127.0.0.1) | UI |

## Differences from the spec

`design/UI.md` is the frozen v3.3 proposal; `docs/PLAN.md` § "Design changes after the v3.3 freeze" lists the accepted changes (icon-only bars, sign toggle in the settings row, one box + More, overlay only in mask mode, Exposure in stops). Differences not covered by that list:

| Area | Spec | Code |
|---|---|---|
| Undo inside a tool | One stroke = one undo step (brush, Liquify) | Done: one gesture = one step |
| Rail reorder | Long-press 400 ms then drag, haptic tick, auto-scroll near arrows | Fixed: hold-drag works, haptic tick; no auto-scroll yet |
| Rail | Fades to 25% during canvas gestures; actions Move up/down/Layer settings; insert new layer above the active one; pending shown above the active layer | No fade; Move up/down are bubble buttons, not a11y actions; new layers always on top (Retouch at the bottom); pending always at the top |
| Rail swipe | Haptic tick; toast 3 s | No haptic; Snackbar Short, first 3 per session |
| Layer bubble | Closes when the rail scrolls; navigator hidden while open | The tap-outside layer covers the rail, so the rail can't scroll or be dragged while a bubble is open |
| Compare | "Original" tag; TalkBack toggle | Press-and-hold only, no tag |
| Double-tap | Fit ↔ 200% at the tapped point; zoom to 800% | Double-tap resets to Fit; zoom to 2000% |
| Navigator | ≤ 66×88dp, decorative for a11y | ≤ 66×120dp, no a11y handling |
| Loupe | Drops to the leading side above the rows near the corner | Moves to the top-right |
| Brush size | 4–200dp | 4–150dp (Liquify 10–150) |
| Mask button | Mask thumbnail + count | Mask icon + count |
| Steps | Popover with Make ±/Hide/Delete; hidden = dashed border; a11y "Step 2 of 3" with 5 actions | Inline buttons in the steps row; hidden = dim + strike; a11y Move left/right only |
| Gradient | Linear: two parallel lines + centre handle; handles set width and angle | One line, two end handles |
| Luminance strip | Upper/lower handle pairs chosen by touch half | Nearest handle, ties by drag direction |
| New step types | Six types | Seven: Depth range and Subject pins added (post-freeze, PLAN.md M3/M4) |
| Detail | Gaussian/Lens toggle; Sharpen chips Amount/Radius/Clarity | One box: Amount main, More holds Type (Gaussian/Lens/Motion/Zoom) and the rest |
| Liquify | Size/Strength chips + More (Softness) | Size main, More: Strength, Softness, Direction |
| Crop | Five modes, Auto, Flip V, Scale/Fill edges, size info, thirds only while dragging, edge handles, 65% dim; Crop before the layer stack (§2 rules) | Presets + More sliders, Turn/Flip H/Reset in the bar; thirds always; L corners only; 55% dim; crop applies after all layers (PLAN.md M4.5) |
| AI | Mode menu, run button with provider caret, job pill with Cancel | Erase only; plain Erase icon; gear to AI settings; status text in the settings row, Cancel only for remote jobs |
| Export | Bottom sheet with quality, remove location, estimate, Share | Dropdown: Size cycle, JPEG (q95), 16-bit PNG |
| Discard | Bubble "Discard changes?"; Back with work: Keep project / Discard / Cancel | AlertDialog Discard / Keep editing; "Close photo? Your edits will be lost." |
| Home, Settings, Light theme, motion, providers, consent, credits | Specified | Settings (AI providers, on-device models, privacy, about), providers and consent built (§31); Appearance / Light theme, motion, credits not built |

## Not covered by any test

Built rows whose "Test today" is `none`, per area (spec-only rows that are not built are counted apart):

| Area | Built rows | Not covered | Spec-only rows (not built, n/a) |
|---|---|---|---|
| 1. App start, first run, empty state | 10 | 4 | 0 |
| 2. Open and load | 20 | 11 | 0 |
| 3. Canvas: pan, zoom, touches | 19 | 17 | 0 |
| 4. Top bar | 13 | 1 | 1 |
| 5. Undo / redo (history) | 10 | 0 | 0 |
| 6. Layer rail | 26 | 5 | 2 |
| 7. Layer bubble | 17 | 1 | 1 |
| 7b. Group bubble | 5 | 0 | 0 |
| 8. Photo bubble (Smooth banding) | 6 | 1 | 0 |
| 9. Tool bar shell (shared by all tools) | 12 | 3 | 0 |
| 10. Color tool | 13 | 5 | 1 |
| 11. Curves | 15 | 3 | 0 |
| 12. Mask mode: bar and steps row | 22 | 1 | 1 |
| 13. Mask settings box (More, copy, clear, feather) | 10 | 1 | 0 |
| 14. Mask step: Brush | 10 | 3 | 0 |
| 15. Mask step: Gradient | 9 | 4 | 0 |
| 16. Mask step: Color range (eyedropper) | 10 | 4 | 0 |
| 17. Mask step: Luminance and Depth range | 9 | 2 | 0 |
| 18. Mask step: Subject, Background, pins | 14 | 8 | 1 |
| 19. Mask overlay, loupe, brush ring | 10 | 10 | 0 |
| 20. Detail tool (Blur, Sharpen) | 21 | 4 | 0 |
| 21. Liquify | 20 | 4 | 0 |
| 22. Crop | 31 | 7 | 1 |
| 23. AI eraser | 14 | 3 | 2 |
| 24. AI models (download, verify, run) | 15 | 8 | 1 |
| 25. Export | 21 | 7 | 1 |
| 26. System back and discard dialogs | 7 | 0 | 1 |
| 27. Settings persistence | 3 | 0 | 1 |
| 28. Theme, scroll indicators, accessibility basics | 8 | 5 | 2 |
| 29. Rendering pipeline core | 9 | 5 | 0 |
| 30. Projects and Home | 9 | 0 | 0 |
| 31. AI providers, Settings, consent | 15 | 1 | 0 |
| **Total** | **433** | **128** | **16** |

Remaining gaps: canvas gestures (pinch, pan limits, double-tap, navigator), drawing (overlay, loupe, rings, badges: screenshot or manual), AI model runs and their status rows (need a fake ModelStore or pushed models), RAW decode, and GPU colour maths for Contrast, Highlights/Shadows, Temp/Tint and Saturation/Vibrance. Compose UI tests (app/src/androidTest, `showEditor` in Editor.kt) cover the rail, bubbles, tool bars, mask bar, dialogs, back handling, projects, export menu and settings; unit tests cover the model maths, EditorState and ModelStore; PipelineTest covers the GPU pipeline and export. Three GPU tests skip without pushed models (aPinSelectsOnlyThatObject, subjectMaskFindsTheObject, eraserContinuesStripesAcrossTheHole).

package io.github.genneth.haines

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **haines — the owner's phone, and the only device these apps are for.**
 *
 * This file is authored in `~/almon/tools/haines-previews.kt` and copied verbatim
 * into each consumer's `screenshotTest` source set. It is **byte-identical in every
 * repo**, which is what lets `~/almon/conformance/` pin the copies against the
 * source; the source in turn is pinned against `conformance/intent.py` →
 * `PHONE_GEOMETRY` / `PHONE_DENSITY`, so the numbers below cannot drift from the
 * phone as measured. Do not edit a copy: edit the almon source and redeploy, the
 * same rule the toolchain versions follow.
 *
 * **Why a copy and not a link.** almon owns which numbers are right; the repo
 * *states* them, so a clone builds the same off molly. A symlink into `~/almon`
 * would make an admin repo a build input for three signed, released apps.
 *
 * **Why a `spec:` string and not a device definition.** Android does have a device
 * catalogue — `devices.xml`, `id:`/`name:` references, `spec:parent=` inheritance —
 * and Studio and `avdmanager` read a user-defined one at `~/.android/devices.xml`.
 * The screenshot renderer does not: its standalone path builds only a
 * `DeviceResourceTable` from the catalogue baked into the jar, and an unresolvable
 * `device =` **falls back to the default device silently**, which is the 420dpi
 * default that made every golden in this house 0.95x the phone until 2026-08-14.
 * See `~/almon/base.md` → Corrections.
 *
 * **State what the device reports; never derive one number from another.** That is
 * the rule, and it is why the geometry below is in **dp**: 413x947 and 814x898 are
 * the phone's own `am get-config` output, not arithmetic. The previous hand-written
 * figures were derived from the pixel counts and both were wrong by 1dp in opposite
 * directions — folded read 948 (2616 x 160 / 442 = 946.97, rounded up) and unfolded
 * read 813 (813.75, rounded down). Do not "improve" these by recomputing them from
 * pixels; re-read them from the phone.
 *
 * **Why dp and not pixels, given the renderer takes either.** Because the renderer
 * cannot render at 442 and the error has to land somewhere. Measured 2026-08-14 by
 * rendering 1000dp at a range of densities: the requested dpi is **snapped to the
 * nearest of a fixed ladder** — 400/410/420 all render at 420, 430/440/442/450/455
 * all render at **440**, 460/470/480 all render at 480. So 442 becomes 2.75 px/dp,
 * 0.45% off the phone (which is a tenfold improvement on the 420dpi default this
 * house rendered at until today, at 4.5% off, and is the best available).
 *
 * Given that, a `px` spec would buy an exactly-phone-sized bitmap at the cost of
 * putting the error into the **dp**: 1140px / 2.75 = 414.5dp folded and 2248px / 2.75
 * = 817.5dp unfolded, i.e. up to 3.5dp of width the phone does not have. dp is what
 * every layout decision keys on — where text wraps, whether a header row fits, which
 * size class fires — so the error belongs in the raster instead, where it is 0.35% of
 * scale and invisible. Frames therefore come out 1136x2604 and 2239x2470 rather than
 * 1140x2616 and 2248x2480, and the content inside is laid out at exactly the dp the
 * phone gives it. `dpi=442` is still stated rather than 440: it is what the phone
 * reports, the snapping is the tool's business, and if the tool ever gains 442 the
 * frames improve for free.
 *
 * Measured from the phone over adb on 2026-08-14 with `am get-config`, `wm size`,
 * `wm density` and `dumpsys display`, in both postures:
 *
 * ```
 * folded     1140x2616 px   520 physical / 442 override   sw413dp-w413dp-h947dp   normal-long
 * unfolded   2248x2480 px   520 physical / 442 override   sw814dp-w814dp-h898dp   large-notlong
 * ```
 *
 * `mAppBounds` is the full panel in both postures, so an app window is the whole
 * display and the system-bar insets are drawn inside it rather than subtracted from
 * it. `font_scale` is 1.0 and `fontWeightAdjustment` 0, which is what a preview
 * assumes by default, so nothing needs saying about type scale here.
 *
 * **`cutout=` is deliberately absent, and that took two measurements to get right.**
 * The first said the key was inert: a preview reports `top=0 bottom=0` for
 * `safeDrawing`, `statusBars`, `navigationBars` and `displayCutout` alike, at crop
 * size and at full device size. That much is true and still is. But an A/B on the key
 * itself — the same screen rendered with `cutout=punch_hole`, `cutout=none` and no
 * keys — showed the screen's content starting at **y=136px** with the cutout and
 * **y=0** without it. The key applies a hard **49.5dp** offset to the content that
 * never appears in any `WindowInsets`, so an app cannot read it, respond to it or
 * consume it; it just pushes everything down. And 49.5dp is the renderer's own idea
 * of a punch hole, not haines' 38.7dp.
 *
 * So it was removed: it produced the exact blank band this profile exists to avoid,
 * at the wrong size, in a form nothing can react to. `navigation=` was measured too
 * and is genuinely inert — dropped as well, because a decorative key that reads as
 * coverage is what caused this in the first place.
 *
 * The phone's cutout is 107px (38.7dp) on both panels: a centre punch-hole folded, a
 * corner one at top **right** unfolded (the inner panel is mounted
 * `installOrientation ROTATION_270`) — exactly where an app bar's action icons sit.
 * **No frame here can catch a collision with it.** That is a device observation.
 *
 * **The frames are the app's content area, not the panel — and that is the point.**
 * Because every inset resolves to 0, a screen that consumes them is drawn with
 * nothing reserved. Reserving the space by hand was tried and rejected: a blank band
 * at the top of a frame shows nothing an agent can judge, reads as a layout bug, and
 * cannot show the one thing that would justify it — an app bar's background bleeding
 * under the status bar — because with zero insets the bar draws flush anyway. A
 * panel-height frame is not a frame with a status bar in it; it is a frame with 55dp
 * of height the app does not have.
 *
 * So the heights below are the window minus the bars, and **everything in a frame is
 * app**. Nothing has to be imagined and "does this fit above the fold?" is answered
 * by the picture instead of by subtraction:
 *
 * ```
 * folded     947 - 38.7 - 16.3  ->  413 x 892 dp
 * unfolded   898 - 38.7 - 16.3  ->  814 x 843 dp
 * ```
 *
 * The width is untouched because there are no side insets in portrait: both bars
 * report `left=0 right=0`, and the cutout contributes nothing.
 *
 * This is also why **no app needs a preview-only seam**. A screen calling
 * `windowInsetsPadding(safeDrawing)` or `safeDrawingPadding()` pads by zero and fills
 * this frame exactly, which is correct by construction. Do not add an insets
 * parameter to a production API to serve previews; shamoji had one and it was
 * removed. [HAINES_STATUS_BAR] and [HAINES_NAVIGATION_BAR] exist to *derive the
 * heights above*, not to be passed into a composable.
 *
 * What this gives up is nothing a frame could show today: a collision with the cutout
 * and the bar's bleed under the status bar stay device observations, which is what
 * they already were.
 *
 * **What is not in a frame and cannot be.** Rounded corners of 77px inner / 102px
 * cover (27.9dp / 36.9dp), so corner-adjacent content is clipped on glass and square
 * here. 120Hz, HDR to 2200 nits and a wide colour gamut, against sRGB rendering.
 * Those stay device observations, the same category as gestures and IME panning — a
 * golden that cannot show a thing must not be cited as covering it.
 *
 * **One 442 for two different panels.** The inner display is really 422.96 x 434.43
 * dpi and the cover display 445.48 x 455.11 — non-square pixels, ~5% apart — yet
 * both declare base density 520 and both take the single 442 override. 442 is the
 * OS's own rounding across two displays, not a physical constant.
 *
 * ## Using this
 *
 * A full-screen preview states no dimensions at all:
 *
 * ```kotlin
 * @PreviewTest
 * @HainesFolded
 * @Composable
 * fun RiverPreview() { … }
 * ```
 *
 * A deliberate crop — a popup at the width the field actually gives it, or a tall
 * frame that fits content a screen would scroll — keeps its own `widthDp`/`heightDp`
 * and **still inherits 442** by naming the spec, because `device` and
 * `widthDp`/`heightDp` are orthogonal rather than competing: width and height become
 * the root view's layout size, while the spec configures the device and therefore the
 * density. Say in a comment what the crop is for, since it is no longer the phone:
 *
 * ```kotlin
 * @PreviewTest
 * @Preview(name = "haines folded", device = HAINES_FOLDED, widthDp = 381, heightDp = 260)
 * ```
 *
 * `@PreviewTest` cannot be folded in here: its `allowedTargets` is `FUNCTION`, so it
 * stays at the call site. It carries no dimensions, so nothing is duplicated by it.
 */
const val HAINES_FOLDED = "spec:width=413dp,height=892dp,dpi=442"

/** haines unfolded — see [HAINES_FOLDED] for why this is stated in dp. */
const val HAINES_UNFOLDED = "spec:width=814dp,height=843dp,dpi=442"

/**
 * The system-bar insets haines actually reports, from `dumpsys window`. **Measured
 * in both postures on 2026-08-14 and identical in each** — folded `statusBars
 * [0,0][1140,107]` / `navigationBars [0,2571][1140,2616]`, unfolded
 * `[0,0][2248,107]` / `[0,2435][2248,2480]` — so one pair of constants serves the
 * whole device. That was the expected answer and was still worth reading off the
 * phone; "obviously the same" is the reasoning that produced 948 and 813.
 *
 * They exist because **a preview renders with no insets at all** — every one of
 * `safeDrawing`, `statusBars`, `navigationBars` and `displayCutout` reports zero, so
 * a screen that consumes insets is drawn in a golden with nothing reserved unless it
 * is handed a value. A guessed value is no better: shamoji carried 32dp/24dp by
 * hand, under-reserving the status bar by 7dp and over-reserving the gesture bar by
 * 8dp.
 *
 * Kept in pixels because that is how the phone reports them. Use [HAINES_STATUS_BAR]
 * / [HAINES_NAVIGATION_BAR] rather than writing a dp figure down anywhere: the
 * conversion below is evaluated, so unlike a transcribed number it cannot drift.
 */
const val HAINES_STATUS_BAR_PX = 107

/** See [HAINES_STATUS_BAR_PX]. The gesture bar, not a button bar. */
const val HAINES_NAVIGATION_BAR_PX = 45

/** haines' override density, for converting the two constants above. */
const val HAINES_DENSITY_DPI = 442

/** [HAINES_STATUS_BAR_PX] in dp — 38.7dp. Hand a preview this, never a guess. */
val HAINES_STATUS_BAR: Dp = (HAINES_STATUS_BAR_PX * 160f / HAINES_DENSITY_DPI).dp

/** [HAINES_NAVIGATION_BAR_PX] in dp — 16.3dp. */
val HAINES_NAVIGATION_BAR: Dp = (HAINES_NAVIGATION_BAR_PX * 160f / HAINES_DENSITY_DPI).dp

/** haines, folded. A full-screen frame; states no dimensions of its own. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Preview(name = "haines folded", device = HAINES_FOLDED, showBackground = true)
annotation class HainesFolded

/** haines, unfolded. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Preview(name = "haines unfolded", device = HAINES_UNFOLDED, showBackground = true)
annotation class HainesUnfolded

/** haines, folded, dark. The phone lives in dark mode; `am get-config` reports `night`. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Preview(
    name = "haines folded dark",
    device = HAINES_FOLDED,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
annotation class HainesFoldedDark

/** haines, unfolded, dark. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Preview(
    name = "haines unfolded dark",
    device = HAINES_UNFOLDED,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
annotation class HainesUnfoldedDark

/**
 * Both postures from one composable, which is the common case: the fold is the
 * layout question this house actually has, and a screen that only ever proved one
 * of them has proved half a phone.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@HainesFolded
@HainesUnfolded
annotation class HainesBothPostures

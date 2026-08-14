package io.github.genneth.haines

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

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
 * **`cutout=` and `navigation=` are set here and do nothing. Measured, not assumed.**
 * A preview rendering `WindowInsets.safeDrawing`, `statusBars`, `navigationBars` and
 * `displayCutout` reports **top=0 bottom=0 for every one of them**, with this spec.
 * The keys are accepted by the grammar and reach the configuration, but no inset is
 * synthesised and nothing is painted — a cutout is a hole in a panel, not pixels.
 * They are kept because they cost nothing, state what the device is, and would start
 * mattering for free if the renderer ever grew them. **Do not read them as coverage.**
 * The phone's cutout is 107px (38.7dp) tall on both panels: a centre punch-hole
 * folded, and unfolded a corner one at top **right**, since the inner panel is
 * mounted `installOrientation ROTATION_270` — exactly where an app bar's action
 * icons sit. No frame here will ever catch a collision with it.
 *
 * **Zero insets is the trap that follows.** Because every inset resolves to 0, a
 * screen that consumes them is drawn in previews with nothing reserved: hartley's
 * `HartleyInsets.bar` and pupil's `safeDrawingPadding()` both collapse to nothing,
 * and their goldens sit content flush against an edge the phone gives 38.7dp of
 * status bar. The only way a frame can be honest about this is shamoji's shape —
 * take the insets as a parameter and hand the preview a real value — using
 * [HAINES_STATUS_BAR_PX] and [HAINES_NAVIGATION_BAR_PX] rather than a guess.
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
const val HAINES_FOLDED = "spec:width=413dp,height=947dp,dpi=442,cutout=punch_hole,navigation=gesture"

/** haines unfolded — see [HAINES_FOLDED] for why this is stated in dp. */
const val HAINES_UNFOLDED = "spec:width=814dp,height=898dp,dpi=442,cutout=corner,navigation=gesture"

/**
 * The system-bar insets haines actually reports, in pixels at 442dpi — 38.7dp top,
 * 16.3dp bottom. A preview renders with **no** insets, so a screen that consumes
 * them has to be handed a value, and a guessed one is a UI validated against
 * nothing: shamoji carried 32dp/24dp by hand, over-reserving the gesture bar by 8dp
 * and under-reserving the status bar by 7dp.
 *
 * Kept in pixels because that is how the phone reports them; convert with
 * [HAINES_DENSITY_DPI] at the point of use rather than writing a dp figure down.
 */
const val HAINES_STATUS_BAR_PX = 107

/** See [HAINES_STATUS_BAR_PX]. The gesture bar, not a button bar. */
const val HAINES_NAVIGATION_BAR_PX = 45

/** haines' override density, for converting the two constants above. */
const val HAINES_DENSITY_DPI = 442

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

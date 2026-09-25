# Native Kotlin Opaline redesign — implementation checkpoint

Source baseline: `880347d`, Geode main. The supplied APK is the older 1.7.0 build;
this branch works against the current 1.8.0 source. This checkpoint is not yet an
Android-device-certified release.

## Authoritative design resources

The source of geometry and appearance is `ui-system/Opaline-3D-Library`, not an
independently generated design. Shipped GLBs and six background PNGs are exact
copies, checked with SHA-256. Library docs, catalogues, modelling source, optical
source and numerical implementations remain in that folder. Older design docs
that describe the WebView adapter are historical; this file describes this branch.

## Native implementation

- `OpalineScene.kt`: measured Compose content frames, clipped native world bounds,
  non-consuming pointer observation, cancellation, lifecycle and window ownership.
- `OpalineTextureView.kt`: Kotlin-owned EGL 3 context on a separate rendering thread,
  Choreographer scheduling, pause/resume and deferred surface destruction.
- `OpalineMesh.kt`: native GLB loading, indexed and unindexed triangles, material
  extensions, morph targets and inherited control motion/pivots.
- `OpalineRenderer.kt`: perspective camera, source mesh buffers, separate receiver
  texture for refraction, stable native text, shared lighting and material families.
- `OpalineMotion.kt`: native port of the library spring, including the original
  4 Hz/.68 contact and 4 Hz/.8 value damping, bounded 1/240 second substeps.
- `opaline-native/shaders/`: GLES 3 shaders. The library's object-space noise/flow
  field is retained. The native surface pass implements GGX/Fresnel lighting,
  thickness/IOR/Beer–Lambert attenuation, backdrop refraction, environment reflection,
  clearcoat, thin-film colour, local touch glow and a bounded contact dye effect.
  These are native GPU shaders, not WebGL or JavaScript.

The embedded WebView, JavaScript runtime and bundled browser dependencies have been
removed from the APK. Original library sources and notices remain in the repository.

## Application surfaces and navigation

Four workspaces occupy the lower crescent dock on phones and suspended rail at
600 dp and above: Listen, Library, Visuals, Studio. Global Search and Settings live
in the workspace bar. Existing independent section stacks, route state, predictive
back dispatch, deep links, permissions, repositories and engine bindings are retained.
Settings continues to have its own saved section state, reached through the header.

Shared components carry the native design into player, library, track and playlist
editors, visual customization, presets, studio, export, onboarding and settings.
Separate Android dialogs and popup menus own their own EGL scene so their coordinates
cannot leak into the underlying page. Text fields retain caret, selection and IME.

| Role | Source geometry / recipe | Native behaviour |
| --- | --- | --- |
| Action | A01 / UI001 | Local contact indentation, recovery, glow, haptic click |
| Transport | A22 / UI003 | Domed play/pause puck with stable native icon |
| Icon / selected lens | A03, N03 | Gel/dew material, bounded tilt and depth lift |
| Seek | B07 / UI019 adaptation | Seven original liquid-rail morph shapes, value spring |
| Parameter slider | B01 / UI019 | Separate cradle and translating/stretching bead |
| Trim range | B04 | Independently moving start/end beads with native range semantics |
| Toggle | B09 / UI008 | Two-stop semantic state; independent travelling thumb |
| Dial | B13 / UI023 | Rotating cap and inherited marker with native range semantics |
| Lists and panels | C02, C03 / UI057, UI043 | Dimensional shells and stable readable fronts |
| Editable field | C04 / UI011 | Quiet shell around real native editable text |
| Media ring | C20 / UI061 | Open 3D frame around native artwork |
| Navigation | D03, D07 / UI034, UI038 adaptation | Original supports, four native mounts; source guide sockets omitted to avoid duplicates |
| Environment | N01, N03, N06 | Original dimensional nature geometry, restrained ambient travel |

Tidal, Opal, Moss, Obsidian, Aurora and Amber can be selected in Settings → Look.
The choice persists with existing GUI preferences. Reduced motion settles shells,
stops the ambient clock and renders changed state; controls remain immediately usable.

## Validation and outstanding work

Completed locally:

- 28 asset provenance, GLB bounds and finite-accessor checks.
- Android debug APK assembled for ARM64 and x86-64; all six Opaline JVM tests pass.
- Formatting checks pass for changed Kotlin files.
- GLSL ES 3 compilation/linking and a Mesa material specimen render with no GL errors.
- Native loader fix for unindexed triangles and inherited dial-marker motion.

Added automated tests cover these loader paths and press/release settling; the
navigation smoke test follows the new settings entry point. Device screenshots,
accessibility traversal and real-device frame timing remain separate gates.
The final local build used `-Pkotlin.incremental=false` after stale incremental
output produced duplicate-declaration errors; the full rebuild passed. A material specimen is not a device screenshot.

This checkpoint does **not** claim all 76 source compositions or all 18 transition
recipes are ported. Local deformation is the authored MotionController behaviour,
not a volumetric XPBD solve. Native PBF fluid, progressive photon-traced caustics,
full participating-volume rendering, film drainage and coupled numerical simulations
remain work to port before claiming the entire library's motion/effect implementation.
Refraction currently samples the environment receiver rather than recursively
refracting other translucent UI objects. These are material limits, not hidden solvers.

The visual quality target is dimensional geometry throughout the app, polished optical
materials, coherent light and depth, responsive deformation and transitions. A passing
build alone does not certify that visual target. No Android emulator/device was available
for the screenshot, touch, accessibility and frame-time gates in this session.

## Checks

```sh
node --test tools/opaline/*.test.mjs
./gradlew :app:testDebugUnitTest --tests 'dev.geode.ui.opaline.*'
./gradlew :app:assembleDebug :app:lintDebug ktlintCheck
./gradlew :app:connectedDebugAndroidTest
```

The user-selected destination is `https://github.com/tessie1993/geode-2`.
The original repository remains the `upstream` source remote.

# Reader and chapter-name regression verification

## Observed protocol

The original failing chapter is https://mangaruu.com/c/zensei-eiyuu-2-kai-me-no-jinsei-ha-asobi-makuro-u-to-omoi-masu-3326-ch-1-id-141124 (investigated 2026-10-30 JST).

- Response HTML contains `__NEXT_DATA__.props.pageProps.data.chapter`; the browser removes that script after hydration.
- `chapter.s_id` and `chapter.manga.s_id` (550083 and 57700) differ from public IDs (141124 and 3326).
- `/config.json` publishes `apiImage`. The manifest URL is `{apiImage}/{manga.s_id}/{chapter.s_id}.json?t={updated_at in epoch milliseconds}`.
- Manifest `d` is Base64 plus repeating XOR, yielding 33 entries. Entry `d` is a Base64/XOR-wrapped AES-CTR value, with a prepended 16-byte IV and chapter UUID key. Paths resolve relative to `chapter._d`.
- Switching the site's ordinary server selector to D in the cloud browser loaded images at 720×1024. D serves extensionless JPEG URLs, without login/cookies. Image requests omit Referer and Origin, matching the site's no-referrer policy. B rejected this environment with a Cloudflare block; no bypass is implemented.

## canva2 and chapter names (2026-10-31 UTC)

The screenshot's `unsupported reader mode 'canva2'` was reproduced using **ないしょの予習**, chapter 59:
https://mangaruu.com/c/naisho-no-yoshuu-3913-ch-59-id-211058

- Opened the actual chapter in the Work cloud browser and selected the site's D server. Raw HTML and the reader JavaScript identify mode `canva2`, chapter `s_id=895177`, manga `s_id=62926`, and a manifest with 28 images, each 1337×1920.
- The reader downloads scrambled images, then invokes https://mangaruu.com/soraraw.wasm `unscramble` on an 8×8 grid. The browser passes `chapter.s_id ?? chapter.id` as its seed: **895177**, not the public chapter ID 211058. The observed chapter has no custom `n`, so the site's default grid is 8.
- Production now marks canva2 page URLs with the site chapter ID in a fragment (not sent to the CDN). An OkHttp interceptor decodes the downloaded bitmap, restores its shuffled/rotated tiles, and returns a lossless PNG. Ordinary `image` responses pass through unchanged.
- The decoder adapts Keiyoushi SoraRaw's Apache-2.0 implementation. Independent comparison with the site's Wasm exposed reversed quarter-turns for square tiles; this port corrects them. The license and attribution are included in APK assets.
- The title page https://mangaruu.com/m/naisho-no-yoshuu-3913 contains 59 numbered chapter links plus duplicated action links labelled `最初から読む` and `最新を読む`. Names now use the parsed chapter number (`1話`, `59話`) before URL deduplication. Decimal numbers are supported; unnumbered special names are retained.

## Reproduction tests

Copy `src/ja/mangaruu` into the pinned Keiyoushi checkout used by `.github/workflows/build.yml`, and load only `ja/mangaruu` in its settings.

```sh
./gradlew :src:ja:mangaruu:spotlessApply
MANGARUU_LIVE_TEST=1 MANGARUU_CAPTURED_METADATA=1 ./gradlew :src:ja:mangaruu:testDebugUnitTest
./gradlew :src:ja:mangaruu:assembleDebug :src:ja:mangaruu:lintRelease
```

The four test classes contain **10 tests**:

- `ReaderTest` (3): the actual manifest/URL decoding path, malformed-manifest rejection, and live downloads/ImageIO decoding of all 33 original images.
- `ChapterLabelTest` (2): the real first/latest links, all 59 deduplicated chapters, decimal URL numbers, and special chapter labels.
- `Canva2Test` (2): all 28 URL fragments use the browser's site ID; decoder pixels match independent site-Wasm output in six square/rectangular/uneven-size cases.
- `Canva2ImageInterceptorTest` (3): Android 35 native graphics through Robolectric tests the actual production HTTP interceptor, Bitmap decode, restoration and lossless PNG encode/decode. Every pixel of **all 28 freshly downloaded real images** must match the independently captured site-Wasm tile mapping. It also checks an uneven synthetic image and ordinary-image passthrough. Comparing permutations of the same Android-decoded pixels avoids platform JPEG-decoder rounding differences.

`test/generate_canva2_reference.cjs` reproduces the independent fixtures by executing the site's Wasm, not the production Kotlin algorithm:

```sh
curl --fail https://mangaruu.com/soraraw.wasm -o soraraw.wasm
node src/ja/mangaruu/test/generate_canva2_reference.cjs soraraw.wasm reference.json
```

`canva2-reference.json` records the Wasm SHA-256, six output hashes, and tile maps validated against every synthetic output pixel. Fixtures include public metadata, encrypted manifests, chapter anchor text and synthetic permutation references; no visitor IPs, cookies or manga image files are committed.

The live tests use curl for HTTP transport to honor the host proxy. `MANGARUU_LIVE_TEST=1` is required; CI checks exact per-class test counts and zero skipped/failed/error tests, preventing `NO-SOURCE` or skipped network checks from passing. Both environment flags are Gradle test inputs.

## Metadata access and scope

Hosted GitHub runners receive HTTP 403 for the original chapter HTML. CI **explicitly uses captured public metadata/manifests, then downloads and decodes all 61 real CDN images live**. This does not validate fresh chapter/API access. For the original chapter, omitting `MANGARUU_CAPTURED_METADATA=1` fetches fresh HTML/config/manifest; that full mode passed in Work before the initial v1.6.2 commit. The canva2 test always uses captured chapter/manifest metadata and live CDN images. Actual canva2 metadata and JavaScript were separately fetched and inspected in Work.

Verified modes are `image` and the observed default-grid `canva2`. Legacy `canva` remains explicitly unsupported. The Android bitmap pipeline is exercised with native Robolectric graphics; **full Mihon UI/device rendering has not been tested**.

## Previous v1.6.2 evidence

GitHub run [40936770009](https://github.com/SPELLMAGIC545/Mangaruu/actions/runs/40936770009) passed on a22b44fbba1af44a2e5719ad5e77101b3f333ef6: 3 tests, zero failures/errors/skips, all 33 plain images decoded, APK assemble and release lint passed (0 errors, 2 manifest warnings). This version explicitly rejected canva2; the current change addresses that reported failure.

## Current v1.6.3 local result (2026-10-31 UTC)

Before committing this update, `spotlessApply`, `testDebugUnitTest`, `assembleDebug` and `lintRelease` completed successfully in one Gradle invocation (6m 33s):

- 10 tests; **0 failures, 0 errors, 0 skipped**.
- All 28 real canva2 images passed Android native Bitmap/interceptor/PNG pixel comparison with the site-Wasm reference (177.1s including native runtime initialization). All 33 plain images downloaded and decoded (197.1s).
- All 59 chapter names/deduplication and decimal/special-name tests passed.
- APK `tachiyomi-ja.mangaruu-v1.6.3.apk`: versionCode 106003, 281633 bytes; SHA-256 `ed49a9ce95469386460091d774bfa09307cdf260d714aab6f3e7305239fddaae` (local debug signature).
- Release lint: **0 errors**, 3 warnings (`AppLinkWarning`, `DataExtractionRules`, `UseTomlInstead`; the last concerns the test-only Robolectric dependency).
- Regenerated the independent reference JSON with the checked-in Node generator and actual site Wasm; byte-for-byte identical to the checked-in fixture. Wasm SHA-256: `21a15e65be2a308dbc695a50505d0c5899515cd0f308c99821d5d235e1666cd6`.

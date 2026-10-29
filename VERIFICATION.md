# Reader regression verification

The failing chapter is https://mangaruu.com/c/zensei-eiyuu-2-kai-me-no-jinsei-ha-asobi-makuro-u-to-omoi-masu-3326-ch-1-id-141124.

## Observed protocol (2026-10-30 JST)

- The response HTML contains `__NEXT_DATA__.props.pageProps.data.chapter`, not the manga page URLs. The browser removes `__NEXT_DATA__` after hydration.
- `chapter.s_id` and `chapter.manga.s_id` (550083 and 57700) are different from the public chapter/manga IDs (141124 and 3326).
- `/config.json` publishes `apiImage`. The reader manifest is `{apiImage}/{manga.s_id}/{chapter.s_id}.json?t={updated_at in epoch milliseconds}`.
- The manifest `d` is Base64 plus repeating XOR; it decodes to 33 entries. Each entry's `d` is a Base64/XOR-wrapped AES-CTR value, with a prepended 16-byte IV and the chapter UUID as the key. It resolves relative to `chapter._d`.
- The D server returns extensionless JPEG URLs. In the cloud browser, switching the site's own server selector to D loaded the first pages at 720 by 1024 pixels. No login or cookie was needed for the direct image request. The B CDN rejected this environment with a Cloudflare block; no bypass is implemented.
- Image requests match the site's `referrerPolicy="no-referrer"` by omitting Referer and Origin.

## Reproduction tests

Copy `src/ja/mangaruu` into the pinned Keiyoushi source checkout used by `.github/workflows/build.yml`, and load only `ja/mangaruu` in its settings.

```sh
./gradlew :src:ja:mangaruu:spotlessApply
MANGARUU_LIVE_TEST=1 ./gradlew :src:ja:mangaruu:testDebugUnitTest
./gradlew :src:ja:mangaruu:assembleDebug :src:ja:mangaruu:lintRelease
```

`ReaderTest` executes the same DTO/decryption/URL construction functions used by `getPageList`. The fixture test checks the two actual first URLs and page order, and rejects malformed manifests. By default, the opt-in live test fetches fresh chapter HTML, configuration and manifest, downloads all 33 URLs without cookies, Referer or Origin, and decodes every response with ImageIO. Hosted CI explicitly sets `MANGARUU_CAPTURED_METADATA=1`: it reads the captured chapter HTML and manifest instead of fetching these metadata endpoints, then still downloads and decodes all 33 real CDN images. The mode is printed in the test report; this CI result does not validate fresh chapter/API access. Both environment flags are Gradle test inputs so cached results cannot cross modes. A successful HTTP status or a nonempty URL list alone does not pass. Curl supplies the test's HTTP transport so the test can use the host's HTTPS proxy.

The checked-in fixtures contain only public protocol fields and the encrypted manifest; no visitor IPs, cookies or manga image files are included.

## Scope

This change handles the reported chapter's `image` mode. `canva` and `canva2` require additional pixel descrambling and explicitly report an unsupported mode. No Mihon device/emulator rendering test is claimed by these JVM/network tests.

## Local verification result

On 2026-10-30 JST, before committing this change:

- `testDebugUnitTest`: 3 tests, 0 failures, 0 errors, 0 skipped. The live test decoded all 33 images at 720 by 1024 pixels (183.9 seconds).
- `assembleDebug`: success; `tachiyomi-ja.mangaruu-v1.6.2.apk`, versionCode 106002.
- `lintRelease`: 0 errors; 2 Android manifest warnings (`AppLinkWarning`, `DataExtractionRules`).
- APK SHA-256: `34000c7241b0a4de49cff08ff2ebee15140eded29490f020766f451f19743ab2`.
- The workflow checks the XML test count and skipped count after Gradle, so a `NO-SOURCE` task cannot be mistaken for a passing test run.

## GitHub-hosted runner correction

Run 40934431747 compiled the tests but failed on the first request for the chapter HTML with HTTP 403, before requesting any image. Two fixture tests passed; the live test failed and no APK artifact was uploaded. This was not an APK compilation failure.

CI now explicitly uses captured public metadata plus **live downloads/decoding of all 33 real CDN images**. It does not silently retry, spoof a browser, bypass the site block, turn a 403 into a passing fresh-metadata test, or skip the image test. The full fresh-metadata mode remains available (and was passed in Work before the initial commit):

```sh
MANGARUU_LIVE_TEST=1 ./gradlew :src:ja:mangaruu:testDebugUnitTest
# Same mode as GitHub CI:
MANGARUU_LIVE_TEST=1 MANGARUU_CAPTURED_METADATA=1 ./gradlew :src:ja:mangaruu:testDebugUnitTest
```

The corrected captured-metadata mode was also verified locally before its follow-up commit: 3 tests, 0 failures/errors/skips; all 33 real images decoded at 720 by 1024; `assembleDebug` and `lintRelease` succeeded. The live-image test took 190.1 seconds.

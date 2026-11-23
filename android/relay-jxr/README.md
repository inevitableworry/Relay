# Relay JPEG XR gallery previews

`com.metallic.chiaki.jb.JxrDecoder.decode(bytes, maxEdge = 2048)` decodes JPEG XR
entirely on Android through `librelay-jxr.so`. Call it on an IO/worker thread.
It returns a premultiplied `ARGB_8888` Bitmap, or null for invalid, oversized,
unsupported, or memory-constrained images. Original gallery downloads stay intact.

## Source and license

The codec is Microsoft's JPEG XR reference implementation, preserved by the
[4creators mirror](https://github.com/4creators/jxrlib) at commit
`1ef17dd8b7b6e958241914c8911d942a43837233`. The vendored C files are unmodified.
`third-party/jxrlib/SOURCE.json` pins the archive URL, archive SHA-256, and every
vendored file hash. `tools/vendor-jxrlib.py` reproduces that subset. The original
BSD-2-Clause license is retained in `third-party/jxrlib/LICENSE` and must accompany
binary distribution in the app's third-party notices.

The Android CMake target is included only under `CHIAKI_ENABLE_ANDROID`. Its
compatibility/allocation header supplies two missing ANSI declarations/includes
required by modern Clang, and routes the vendor's allocations through a bounded
allocator. No network download is performed during an Android build.

## Preview behavior and limits

- Encoded input: at most 32 MiB. Container IFD entries, metadata spans, image spans,
  source dimensions, and decoder-reported dimensions are checked before pixel decode.
- Source image: at most 8192 pixels per edge and 32 megapixels total.
- Output: at most 2048 pixels per edge and 4,194,304 pixels. The native codec decodes
  a power-of-two thumbnail directly; it never allocates a full-resolution pixel
  image first. Requests below a 1/16 native thumbnail are reduced again afterwards.
- Vendor allocation budget: 48 MiB per decode. Allocations are tracked and freed
  on every exit, including failures partway through legacy initialization.
- A bounded read-only stream supplies only validated file bytes and up to 8192
  zero bytes for the codec's fixed-size packet read-ahead. It cannot write files.
- Decode calls are serialized, including their allocation budgets. Bitmap memory
  remains owned by Android after the native call completes.

Gray, RGB/BGR, RGBA/BGRA, premultiplied alpha, 8/16-bit SDR, packed RGB555/565/101010,
and scRGB fixed-point / half-float / float formats are handled. Linear scRGB is
converted to sRGB. Images with luminance above SDR white receive extended Reinhard
luminance tone mapping, preserving relative color while compressing highlights.
This produces an SDR preview, not an HDR export or an ICC-managed rendering.
CMYK, arbitrary multichannel data, and unsupported pixel formats return null.
The Microsoft container implementation supports the little-endian `II BC` header.

The reference encoder writes `AlphaByteCount` as an absolute ending offset (see
`jxrgluelib/JXRGlueJxr.c`, `WriteContainerPost`). The actual PS5 thumbnail uses this
form. The input guard accepts its EOF form as well as a normal bounded byte count.

## Validation performed

On 2026-11-22, the isolated NDK 28.2 / Clang 19 ARM64 build succeeded for both
`librelay-jxr.so` (including JNI) and the standalone native smoke test. The test ran
on the connected Samsung SM-X930 using an actual 476,146-byte PS5 gallery thumbnail
downloaded read-only. No PS5 payload or controller operation is involved in decoding.

Observed result:

```
PASS source=1920x1080 preview=1920x1080 small=120x68 HDR=0
pixelHash=25d007f02e6f1b13; truncated/header bounds rejected
```

The full-color output was visually inspected and the image/trophy overlay render
correctly. The test also rejects a half-truncated capture and an invalid IFD offset.
The real capture validates the BGRA SDR path. Other supported pixel formats are
implemented from the pinned codec format definitions; real HDR PS5 capture testing
has not yet been performed. APK-level Bitmap/JNI integration is validated separately
by the app build and Gallery device test.

`private-fixtures/`, `reference/`, and `build-test/` are ignored. Personal capture
bytes and rendered previews must not be committed or bundled in the APK.

## Isolated native test

Configure this directory with the Android NDK toolchain, `ANDROID_ABI=arm64-v8a`,
`ANDROID_PLATFORM=android-24`, `ANDROID_STL=c++_static`, and
`RELAY_JXR_BUILD_TEST=ON`, using a separate `build-test` directory. The resulting
`relay-jxr-test` accepts `capture.jxr` and an optional private `.ppm` output path.
It can be pushed to `/data/local/tmp`, made executable, and run through ADB without
installing an application or affecting the foreground UI. The regular Gradle build
does not enable or package this test executable.

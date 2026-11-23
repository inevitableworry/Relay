# Relay game-video OCR components

These classes are intentionally scoped to *decoded remote-game SurfaceView pixels* supplied by Relay. They do not include a capture source, MediaProjection, accessibility inspection, or any PS5 control action.

## Existing ML Kit engine adapter

`PortalOcrEngine` abstracts recognition while preserving the source-script identifier and crop-relative line locations. `PortalMlKitOcrEngine.automatic()` creates the five ML Kit script clients already used by Relay, with source-script scoring and cleaned per-line boxes. `PortalMlKitOcrEngine.single(PortalOcrScript.JAPANESE)` selects one language family when desired. One instance can serve successive frames; close it when game translation stops. The game bitmap must remain alive until the returned `Task` completes.

```kotlin
private var ocr: PortalOcrEngine? = PortalMlKitOcrEngine.automatic()

// The caller obtains `sub` using PixelCopy of only the decoded SurfaceView.
ocr?.recognize(sub)?.addOnSuccessListener { frame ->
    // frame.text: cleaned combined source text
    // frame.script: OCR script inferred by readers
    // frame.regions: crop-local text lines with .bounds x/y/width/height
    // For existing text-box code, convert a bounds value to an Android Rect:
    // Rect(b.x, b.y, b.x + b.width, b.y + b.height)
}?.addOnCompleteListener {
    sub.recycle()
    busy = false
}

// When translation stops: ocr?.close(); ocr = null
```

`PortalOcrFrame` contains a neutral type, so adding another engine does not require changing text placement or the translation layer. The adapter does not silently send any pixels/text to a network service.

## Custom video crop and editor

Use the normalized crop only against the decoded game's frame dimensions:

```kotlin
val normalized = PortalVideoCrop.decode(savedCustomCrop)
    ?: PortalVideoCrop.preset(selectedPreset)
val crop = normalized.pixels(sampledWidth, sampledHeight)
val sub = Bitmap.createBitmap(frame, crop.x, crop.y, crop.width, crop.height)
```

`encode()` returns four integer coordinates in units of 1/10000, such as `1000,6000,9000,9500`. Persist this as a regular setting; `decode()` safely returns null for malformed/obsolete values. Converting to bitmap pixels uses floor/ceil and clips to frame boundaries, so crops work across video resolution changes and 16:9/other aspect ratios.

To edit interactively, mount `PortalCropEditorView(activity)` on top of the game while in explicit edit mode. Set `editor.setCrop(normalized)`; give `editor.setVideoViewport(RectF(...))` the *game SurfaceView's displayed rectangle expressed in the editor view's own local coordinates*, including any relative translation between the two views. With both views attached, obtain their screen coordinates using `getLocationOnScreen()` to calculate that rectangle; do not substitute the activity window dimensions. Set `editor.setOnCropChangedListener { newCrop -> save(newCrop.encode()) }`, provide Done/Cancel controls in the containing UI, and remove the editor when done. Touch supports dragging/moving all four corners and drawing a new rectangle; D-pad arrows move by 1% (Shift+arrows by 5%). The editor is purposely touch-consuming while active and should not remain on top of gameplay after confirmation.

Positioned line regions are relative to the cropped bitmap, so add the pixel crop's x/y offset when mapping back into the decoded video and then to the onscreen SurfaceView.

## PaddleOCR feasibility

PlayTranslate's upstream pipeline is **not** a direct Kotlin-only dependency. Its `app/src/main/java/com/playtranslate/ocr/paddle/PaddleOcrBridge.kt` loads an APK-bundled `ocr/paddle_det.mnn` detector plus `rec.mnn` and `keys.txt` from a model pack; the app uses a separate native `:mnn` module and OpenCV. `OcrModelManager` explicitly gates this implementation on a 64-bit process because the native MNN library is arm64-only. Relay currently has ML Kit but no integrated MNN module/models, and its Shield/Google TV flavors also support `armeabi-v7a`. Full Paddle integration therefore requires checking compatible native model/runtime licenses, supplying and verifying model files, packaging only supported ABIs (and exposing ML Kit on 32-bit), and profiling latency and RAM on each device. The API above leaves a ready extension point without falsely declaring Paddle operational.

Source/reference: [PlayTranslate](https://github.com/dominostars/playtranslate), GPL-3.0; particularly `ocr/core/RecognizedTextNormalizer.kt`, `ocr/paddle/PaddleOcrBridge.kt`, and `scripts/ocr-model-conversion/README.md`. OCR text normalization here credits its cursor and decoration rules. Relay source files retain the project's AGPL-3.0-with-OpenSSL SPDX reference.

## Verification

Added local unit tests for normalized crop bounds, serialization, pixel-coordinate roundtrips, text normalization, and script selection. The first attempted `:app:testMobileDebugUnitTest` compile did not reach tests because simultaneously edited, unrelated app files failed Kotlin compilation (`JbStreamActivity.kt`, `StreamActivity.kt`, and `jb/learning/PortalWordLearningSheet.kt`). The lead agent is coordinating the next serialized Gradle build.

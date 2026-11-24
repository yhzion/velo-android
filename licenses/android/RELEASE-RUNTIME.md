# Android release runtime inputs

This inventory comes from a local minified ARM64 release build, not the final production-signed APK. The 107 distinct coordinates below identify resolved AAR/JAR inputs, not a claim that R8 retained every input. BOMs and constraints are not artifacts.

## AndroidX (including the Android variants)

AndroidX code is Apache-2.0; see publisher POMs and the [AndroidX license](androidx-LICENSE.txt). Preserve the component-specific texts under this directory. [Camera Core 1.6.2](https://dl.google.com/dl/android/maven2/androidx/camera/camera-core/1.6.2/camera-core-1.6.2.pom) also embeds libyuv under BSD-3-Clause. Its copyright and terms are retained separately in [libyuv-LICENSE.txt](libyuv-LICENSE.txt) and [libyuv-PATENTS.txt](libyuv-PATENTS.txt) from [the libyuv project](https://chromium.googlesource.com/libyuv/libyuv/).

```text
androidx.activity:activity-compose:1.13.0
androidx.activity:activity-ktx:1.13.0
androidx.activity:activity:1.13.0
androidx.annotation:annotation-experimental:1.4.1
androidx.annotation:annotation-jvm:1.10.0
androidx.appcompat:appcompat-resources:1.1.0
androidx.appcompat:appcompat:1.1.0
androidx.arch.core:core-common:2.2.0
androidx.arch.core:core-runtime:2.2.0
androidx.autofill:autofill:1.0.0
androidx.camera.featurecombinationquery:featurecombinationquery:1.6.2
androidx.camera.viewfinder:viewfinder-core:1.5.1
androidx.camera:camera-camera2-pipe:1.6.2
androidx.camera:camera-camera2:1.6.2
androidx.camera:camera-core:1.6.2
androidx.camera:camera-lifecycle:1.6.2
androidx.camera:camera-video:1.6.2
androidx.camera:camera-view:1.6.2
androidx.collection:collection-jvm:1.6.0
androidx.collection:collection-ktx:1.6.0
androidx.compose.animation:animation-android:1.13.0
androidx.compose.animation:animation-core-android:1.13.0
androidx.compose.foundation:foundation-android:1.13.0
androidx.compose.foundation:foundation-layout-android:1.13.0
androidx.compose.material3:material3-android:1.5.0
androidx.compose.material3:material3-ripple-android:1.5.0
androidx.compose.material:material-icons-core-android:1.7.8
androidx.compose.material:material-icons-extended-android:1.7.8
androidx.compose.material:material-ripple-android:1.13.0
androidx.compose.runtime:runtime-android:1.13.0
androidx.compose.runtime:runtime-annotation-android:1.13.0
androidx.compose.runtime:runtime-retain-android:1.13.0
androidx.compose.runtime:runtime-saveable-android:1.13.0
androidx.compose.ui:ui-android:1.13.0
androidx.compose.ui:ui-geometry-android:1.13.0
androidx.compose.ui:ui-graphics-android:1.13.0
androidx.compose.ui:ui-text-android:1.13.0
androidx.compose.ui:ui-unit-android:1.13.0
androidx.compose.ui:ui-util-android:1.13.0
androidx.concurrent:concurrent-futures-ktx:1.1.0
androidx.concurrent:concurrent-futures:1.1.0
androidx.core:core-backported-fixes:1.0.0
androidx.core:core-ktx:1.19.0
androidx.core:core-viewtree:1.0.0
androidx.core:core:1.19.0
androidx.cursoradapter:cursoradapter:1.0.0
androidx.customview:customview-poolingcontainer:1.0.0
androidx.customview:customview:1.0.0
androidx.drawerlayout:drawerlayout:1.0.0
androidx.emoji2:emoji2:1.7.0
androidx.exifinterface:exifinterface:1.4.2
androidx.fragment:fragment:1.1.0
androidx.graphics:graphics-path:1.1.0
androidx.graphics:graphics-shapes-android:1.0.1
androidx.interpolator:interpolator:1.0.0
androidx.lifecycle:lifecycle-common-java8:2.11.0
androidx.lifecycle:lifecycle-common-jvm:2.11.0
androidx.lifecycle:lifecycle-livedata-core-ktx:2.11.0
androidx.lifecycle:lifecycle-livedata-core:2.11.0
androidx.lifecycle:lifecycle-livedata:2.11.0
androidx.lifecycle:lifecycle-process:2.11.0
androidx.lifecycle:lifecycle-runtime-android:2.11.0
androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0
androidx.lifecycle:lifecycle-runtime-ktx-android:2.11.0
androidx.lifecycle:lifecycle-viewmodel-android:2.11.0
androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0
androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.11.0
androidx.lifecycle:lifecycle-viewmodel:2.11.0
androidx.loader:loader:1.0.0
androidx.media3:media3-common:1.9.0
androidx.media3:media3-container:1.9.0
androidx.media3:media3-muxer:1.9.0
androidx.navigationevent:navigationevent-android:1.1.0
androidx.navigationevent:navigationevent-compose-android:1.1.0
androidx.profileinstaller:profileinstaller:1.4.0
androidx.savedstate:savedstate-android:1.5.0
androidx.savedstate:savedstate-compose-android:1.5.0
androidx.savedstate:savedstate-ktx:1.5.0
androidx.startup:startup-runtime:1.1.1
androidx.tracing:tracing-android:1.3.0
androidx.tracing:tracing-ktx:1.3.0
androidx.vectordrawable:vectordrawable-animated:1.1.0
androidx.vectordrawable:vectordrawable:1.1.0
androidx.versionedparcelable:versionedparcelable:1.1.1
androidx.viewpager:viewpager:1.0.0
androidx.window:window-core-android:1.5.1
androidx.window:window:1.5.1
```

## Checker Framework

MIT terms and attribution in [the artifact license](checker-qual-3.43.0-LICENSE.txt).

```text
org.checkerframework:checker-qual:3.43.0
```

## Empty conflict-resolution artifact

Guava placeholder JAR; no classes.

```text
com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava
```

## Other Apache-2.0 inputs

[Apache-2.0](Apache-2.0.txt) per publisher POMs, parent metadata or upstream licenses. See the bundled Kotlin and Jakarta texts and notices, [Guava 33.3.1](https://github.com/google/guava/blob/v33.3.1/LICENSE), [Dagger 2.59](dagger-2.59-LICENSE.txt), [AtomicFU 0.28.0 copyright](atomicfu-0.28.0-COPYRIGHT.txt), and the [ZXing license](zxing-core-3.5.4-LICENSE.txt) and [upstream notice](zxing-core-3.5.4-NOTICE.txt).

```text
com.google.auto.value:auto-value-annotations:1.6.3
com.google.code.findbugs:jsr305:3.0.2
com.google.dagger:dagger:2.59
com.google.errorprone:error_prone_annotations:2.28.0
com.google.guava:failureaccess:1.0.2
com.google.guava:guava:33.3.1-android
com.google.j2objc:j2objc-annotations:3.0.0
com.google.zxing:core:3.5.4
jakarta.inject:jakarta.inject-api:2.0.1
javax.inject:javax.inject:1
org.jetbrains.kotlin:kotlin-stdlib:2.4.21
org.jetbrains.kotlinx:atomicfu-jvm:0.28.0
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0
org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.13.0
org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.13.0
org.jetbrains:annotations:23.0.0
org.jspecify:jspecify:1.0.0
```

## Packaged native code, models and notices

| Entry under `lib/arm64-v8a/` | Build input | Notice |
| --- | --- | --- |
| `libvelocore.so` | `native/build.zig`: links the pinned Ghostty VT static library and its configured dependencies; Android system libraries are not bundled | [Native section of NOTICE](../../NOTICE), [native notices](../native/) |
| `libtailscale.so` | `native/tailscale/build.sh`: Go 1.27.1, pinned libtailscale source plus Android/SSH glue; `go version -m` reports 28 external linked modules. The directly copied libtailscale source is not a separate entry in Go build info | [Go section of NOTICE](../../NOTICE), [Go notices](../go/) |
| `libandroidx.graphics.path.so` | AndroidX Graphics Path 1.1.0 | Apache-2.0, AndroidX |
| `libimage_processing_util_jni.so`, `libsurface_util_jni.so` | AndroidX Camera Core 1.6.2; image processing links libyuv | Apache-2.0 and libyuv BSD-3-Clause |

`META-INF/NOTICE.md` preserves Jakarta Inject's notice. The APK also retains five AndroidX license resources under `META-INF/androidx/`; the source copies are in `apk-embedded/`. The scanner ships no native QR library or barcode model.

## Repeat for the exact candidate

1. From the same clean checkout and Gradle build as the candidate, run `gradle --offline --no-daemon -I dev/release-runtime.init.gradle :app:printReleaseRuntime`. Compare all `RUNTIME` lines with the coordinate sections above; reconcile additions, removals and version changes with primary POM/license/notice files. Review the release R8 mapping for retained inputs. Do not infer that a dependency is shipped just because its name occurs in a notice file.
2. Run `unzip -Z1 "$apk"` against the production-signed candidate. Compare native, model, and embedded notice entries with their source artifacts; use the Gradle `mergeReleaseNativeLibs` blame report for JNI origins. Run `go version -m` on the extracted ARM64 `libtailscale.so`, match every `dep` version with `licenses/go/`, and check the separate libtailscale source pin. Check `libvelocore.so` against the Zig build's link inputs and pins, not only its dynamic `NEEDED` entries.
3. Run [the signed artifact checks](../../docs/releasing.md#first-public-apk-verify-privately-before-approval), including the notice/font byte comparison. Record the candidate SHA-256 with the runtime listing and build evidence. Do not publish if any unmatched license remains.

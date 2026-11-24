package io.github.yhzion.velo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.yhzion.velo.ui.theme.ButtonKind
import io.github.yhzion.velo.ui.theme.GlassIconButton
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloTextField
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.rounded
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether the pairing screen puts the camera under the form rather than beside it: on a compact
 * window ([isCompact]) that is not wider than tall. A compact window held sideways (the folded
 * phone, about 880 × 344 dp) has room beside the form, and under it the camera would be a strip.
 */
fun pairingStacks(widthDp: Float, heightDp: Float): Boolean = isCompact(widthDp, heightDp) && widthDp < heightDp

/**
 * "Add by QR code": scan the code `rex server tailscale pair` shows, or type the address. [add] saves
 * the remote on the phone and returns the label of the one that already has the URL, else null;
 * once saved, [onDone] leaves the screen.
 */
@Composable
fun PairingScreen(add: (url: String) -> String?, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var address by remember { mutableStateOf(RemoteAddressInput()) }
    var taken by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scanning = it; cameraDenied = !it }
    fun submit(accepted: String?) {
        if (accepted == null) return
        val have = add(accepted)
        if (have != null) taken = have else onDone()
    }

    // Clear of the soft keyboard: the field and Add stay reachable while typing.
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val config = LocalConfiguration.current
    val screen = Modifier.fillMaxSize().background(c.surface).windowInsetsPadding(WindowInsets.screenSafeDrawing).padding(s.rowPadding)
    val form: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.gap)) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Text("Add by QR code", color = c.text, style = VeloTheme.type.screenTitle)
            }
            Spacer(Modifier.height(s.menuPadding))
            Text(
                "On the computer run `rex server tailscale pair`, then scan its QR code or type the remote's address.",
                color = c.secondary,
            )
            Spacer(Modifier.height(s.rowPadding))
            VeloTextField(
                value = address.text,
                onValueChange = { address = RemoteAddressInput(it); taken = null },
                label = "Address",
                placeholder = ADDRESS_PLACEHOLDER,
                error = address.problem ?: taken?.let { "Already added as $it" },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit(address.acceptedUrl) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(s.overlayPadding))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(s.gap), verticalArrangement = Arrangement.spacedBy(s.gap)) {
                VeloButton("Add", { submit(address.acceptedUrl) }, kind = ButtonKind.Primary, enabled = address.acceptedUrl != null)
                VeloButton("Scan QR code", onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        scanning = true
                        cameraDenied = false
                    } else permission.launch(Manifest.permission.CAMERA)
                })
            }
            if (cameraDenied) {
                Spacer(Modifier.height(s.overlayPadding))
                CameraDeniedNotice { cameraDenied = false }
            }
        }
    }
    val camera: @Composable (Modifier) -> Unit = { modifier ->
        if (scanning) {
            Box(modifier.clip(rounded(VeloTheme.radii.card))) {
                QrScanner { payloads ->
                    scanning = false
                    val scanned = address.scan(payloads)
                    address = scanned
                    taken = null
                    submit(scanned.acceptedUrl)
                }
            }
        }
    }
    // A narrow upright window has no room for the camera beside the form: it goes under the form
    // and takes the height the form leaves. Without the camera the form stays in the middle.
    if (pairingStacks(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())) {
        Column(screen, verticalArrangement = Arrangement.spacedBy(s.rowPadding)) {
            form(if (scanning) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().weight(1f))
            camera(Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(screen, horizontalArrangement = Arrangement.spacedBy(s.rowPadding)) {
            form(Modifier.weight(1f).fillMaxHeight())
            camera(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Said when the camera permission was denied: scanning cannot start, and only the system settings can allow it. */
const val CAMERA_DENIED_MESSAGE = "Camera access is off. Allow it in Settings to scan a code."

/**
 * [CAMERA_DENIED_MESSAGE] with a button to the app's page in the system settings, where the
 * permission can be allowed. When the user comes back from there with the camera allowed,
 * [onGranted] runs, so the notice does not claim the camera is off any longer.
 */
@Composable
fun CameraDeniedNotice(onGranted: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val granted by rememberUpdatedState(onGranted)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) granted()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(VeloTheme.sizes.menuPadding)) {
        Text(CAMERA_DENIED_MESSAGE, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = VeloTheme.surface.danger)
        VeloButton("Open Settings", {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        })
    }
}

/** The back camera, reporting the decoded QR payloads from one frame for the form to validate. */
@Composable
internal fun QrScanner(onCodes: (List<String>) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val decoder = remember { PairingQrDecoder() }
    val stopped = remember { AtomicBoolean() }
    val disposed = remember { mutableStateOf(false) }
    val frameLock = remember { Any() }
    val preview = remember { Preview.Builder().build() }
    val analysis = remember {
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    }
    val reportCodes by rememberUpdatedState(onCodes)
    val future = remember(context) { ProcessCameraProvider.getInstance(context) }
    DisposableEffect(Unit) {
        onDispose {
            disposed.value = true
            stopped.set(true)
            // Wait only for the in-flight luminance copy and close, not for ZXing.
            synchronized(frameLock) { }
            analysis.clearAnalyzer()
            if (future.isDone) runCatching { future.get().unbind(preview, analysis) }
            executor.shutdown()
        }
    }
    AndroidView(factory = { ctx ->
        val view = PreviewView(ctx)
        future.addListener({
            if (stopped.get()) return@addListener
            val provider = future.get()
            preview.surfaceProvider = view.surfaceProvider
            analysis.setAnalyzer(executor) { proxy ->
                val source = synchronized(frameLock) {
                    try {
                        if (stopped.get()) return@setAnalyzer
                        val y = proxy.planes.firstOrNull() ?: return@setAnalyzer
                        val crop = proxy.cropRect
                        // In YUV_420_888 the Y plane always has pixel stride 1.
                        decoder.luminanceSource(
                            y.buffer, proxy.width, proxy.height, y.rowStride,
                            crop.left, crop.top, crop.right, crop.bottom, proxy.imageInfo.rotationDegrees,
                        )
                    } finally {
                        proxy.close()
                    }
                } ?: return@setAnalyzer
                val payloads = decoder.decode(source)
                if (payloads.isNotEmpty() && stopped.compareAndSet(false, true)) {
                    ContextCompat.getMainExecutor(ctx).execute {
                        if (!disposed.value) reportCodes(payloads)
                    }
                }
            }
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        view
    }, modifier = Modifier.fillMaxSize())
}

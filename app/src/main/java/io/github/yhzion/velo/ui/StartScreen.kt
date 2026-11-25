package io.github.yhzion.velo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.AppController
import io.github.yhzion.velo.DiscoveredServer
import io.github.yhzion.velo.Tailnet
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.notYetRows
import io.github.yhzion.velo.ui.theme.AttentionBadge
import io.github.yhzion.velo.ui.theme.ButtonKind
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.Capsule
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloSurface
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.SectionHeader
import io.github.yhzion.velo.ui.theme.Status
import io.github.yhzion.velo.ui.theme.StatusMark
import io.github.yhzion.velo.ui.theme.rounded

/** What the start screen offers for the node's state. */
sealed interface StartStep {
    /** The node is off, logged out or waits for its owner: "Log in to Tailscale". [url] is the login page once the node has one. */
    data class LogIn(val url: String?) : StartStep
    /** The node is starting: nothing to tap yet. */
    data object Starting : StartStep
    /** The node cannot run: say why and offer to try again. */
    data class Failed(val message: String) : StartStep
    /** On the tailnet: the Rex servers found on it. */
    data object Found : StartStep
}

fun startStep(state: TailnetState): StartStep = when (state) {
    TailnetState.Off, TailnetState.LoggedOut -> StartStep.LogIn(null)
    is TailnetState.NeedsLogin -> StartStep.LogIn(state.url)
    TailnetState.Starting -> StartStep.Starting
    is TailnetState.Error -> StartStep.Failed(state.message)
    is TailnetState.Running -> StartStep.Found
}

/**
 * A login the user asked for while the node has no login page: [start] restarts the node (a new
 * login page comes only from a new start), and the page opens once the node has one. Once a page
 * is open the screen waits for the browser ([waitingFor]) while the node keeps that very page
 * ([loginWaits]), or until the user cancels.
 */
class TailnetLogin(private val startNode: () -> Unit) {
    var wanted by mutableStateOf(false)
        private set

    /**
     * The login page the browser was given, until the user cancels. It is the page itself, not a flag: a wait is only
     * for that page, so whatever the node did while no screen followed it (stopped, started again with a new page), a
     * page that was never opened shows no wait.
     */
    var waitingFor by mutableStateOf<String?>(null)
        private set

    /** A login page no browser could open: the card shows its address to copy, while the node keeps it. */
    var unopened by mutableStateOf<String?>(null)
        private set

    fun start() {
        wanted = true
        startNode()
    }

    /** The request is over: its login page opened, or the node will show none for it ([tailnetLoginStep]). */
    fun done() {
        wanted = false
    }

    /** Opens the login page [url] in the browser and waits for the node to log in; with no browser, shows the address instead. */
    fun open(uri: UriHandler, url: String) {
        try {
            uri.openUri(url)
            unopened = null
            waitingFor = url
        } catch (_: IllegalArgumentException) {
            unopened = url
            waitingFor = null
        }
    }

    /** The user stops waiting: the card asks to log in again, and the node keeps its login page. */
    fun cancel() {
        waitingFor = null
    }

    /** The node is in [state] now: a page it no longer has is neither waited for nor shown. */
    fun follow(state: TailnetState) {
        if (!loginWaits(waitingFor, state)) waitingFor = null
        if (unopened != loginPageAgain(state)) unopened = null
    }
}

/**
 * Whether the screen waits for the browser with the page [waitingFor] open and the node in [state]: only while the node
 * still asks for that same page. Running moves the card on to the servers found; any other state, or another login page
 * (the node started again), ended the login the page was for.
 */
fun loginWaits(waitingFor: String?, state: TailnetState): Boolean =
    waitingFor != null && state is TailnetState.NeedsLogin && state.url == waitingFor

/** What the login step's card shows ([loginCardView]). */
enum class LoginCardView {
    /** Why to log in, and "Log in to Tailscale". */
    Ask,
    /** The login page is open: "Waiting for the browser…", "Open the login page again" and "Cancel". */
    Waiting,
}

/** The card of the login [step] with the page [waitingFor] open in the browser, or none. */
fun loginCardView(waitingFor: String?, step: StartStep.LogIn): LoginCardView =
    if (waitingFor != null && step.url == waitingFor) LoginCardView.Waiting else LoginCardView.Ask

/** The page "Open the login page again" opens with the node in [state]: its login page, or null to ask the node for a new one. */
fun loginPageAgain(state: TailnetState): String? = (state as? TailnetState.NeedsLogin)?.url

/** What a login the user asked for ([TailnetLogin.wanted]) does at the node's state. */
sealed interface TailnetLoginStep {
    /** The node has its login page: open [url], and the request is over. */
    data class Open(val url: String) : TailnetLoginStep
    /** The node will show no login page for the request: it runs, is off or failed. The request ends unanswered. */
    data object Drop : TailnetLoginStep
    /** Nothing changes: no login is wanted, or the node is still on its way to a login page. */
    data object Keep : TailnetLoginStep
}

/**
 * What the login request does when the node is in [state] and a login is [wanted]. A request
 * that the node will not answer with a login page ends. Otherwise a later login page, after a
 * Log out on the same visit, would open the browser unasked. [TailnetLogin.start] restarts a
 * node that is off, logged out or failed, and that start makes it Starting at once, so a
 * request does not end on the state it was made in.
 */
fun tailnetLoginStep(wanted: Boolean, state: TailnetState): TailnetLoginStep = if (!wanted) TailnetLoginStep.Keep else when (state) {
    is TailnetState.NeedsLogin -> TailnetLoginStep.Open(state.url)
    is TailnetState.Running, TailnetState.Off, is TailnetState.Error -> TailnetLoginStep.Drop
    TailnetState.Starting, TailnetState.LoggedOut -> TailnetLoginStep.Keep
}

/** The app's [TailnetLogin] ([AppController.tailnetLogin]): it opens the login page when the node has one and the user asked for it, and follows the node's wait for it. */
@Composable
fun rememberTailnetLogin(app: AppController): TailnetLogin {
    val login = app.tailnetLogin
    val uri = LocalUriHandler.current
    val state = Tailnet.state
    LaunchedEffect(state, login.wanted) {
        // First the state ends a wait it left, then a page it brings opens and starts a new one.
        login.follow(state)
        when (val step = tailnetLoginStep(login.wanted, state)) {
            is TailnetLoginStep.Open -> {
                login.done()
                login.open(uri, step.url)
            }
            TailnetLoginStep.Drop -> login.done()
            TailnetLoginStep.Keep -> {}
        }
    }
    return login
}

/** What the Found step says while it lists no server, and whether it then offers to search again. */
enum class FoundNote(val text: String?, val offersSearchAgain: Boolean = false) {
    /** Servers are listed: no text. */
    None(null),
    Paused("Paused while Velo is in the background"),
    /** No sweep of this node has finished yet. */
    Looking("Looking for computers that run Rex…"),
    /** A sweep finished and found nothing: say so, so the user can search again instead of waiting. */
    NothingFound("No computer that runs Rex was found on this tailnet.", offersSearchAgain = true),
}

/** Which [FoundNote] the Found step shows for [found] servers, Android's [paused] network and the finished sweeps. */
fun foundNote(found: Int, paused: Boolean, sweepsFinished: Int): FoundNote = when {
    found > 0 -> FoundNote.None
    paused -> FoundNote.Paused
    sweepsFinished == 0 -> FoundNote.Looking
    else -> FoundNote.NothingFound
}

/** Which node is logged in, above the found computers: named only while it runs with a name, else null. */
fun connectedAsLine(state: TailnetState): String? =
    (state as? TailnetState.Running)?.name?.takeIf { it.isNotEmpty() }?.let { "Connected as $it" }

/** The heading of the found computers. */
const val ComputersHeader = "COMPUTERS THAT RUN REX"

/** The count after [ComputersHeader] once something is found, else null: it keeps its place when a narrow card cuts the heading. */
fun computersCount(found: Int): String? = if (found > 0) "· $found" else null

/** What TalkBack reads for a found computer's Connect: the button says only "Connect", so it names its computer. */
fun connectLabel(server: DiscoveredServer): String = "Connect ${server.name}"

/** Where the start screen puts its steps, from the window's size in dp ([startLayout]). */
enum class StartLayout {
    /** A wide and tall window (the unfolded Fold8 held sideways): the steps in a dark band at the start, the card beside it. */
    Band,
    /** A window that is not compact but too narrow or low for the band (the unfolded Fold8 held upright): the steps in a row above the card. */
    TopSteps,
    /** A compact upright window (a bar phone, the folded Fold8): a progress bar and "Step n of 3" above the card. */
    Narrow,
    /** A compact window as wide as tall or wider (a bar phone held sideways): the steps and the links in a column at the start, the card scrolling beside it. */
    Low,
}

/** The least window size, in dp, that holds the step band beside a card. */
const val StartBandMinWidthDp = 840f
const val StartBandMinHeightDp = 600f

/** The start column of [StartLayout.Low]. derived: room for the wordmark, the steps and the links beside a card on a 892 dp wide phone. */
private const val LowColumnWidthDp = 280

/** The least width a card keeps beside the low column: a 48 dp button with its label and the card's padding. derived. */
private const val LowCardMinWidthDp = 320

/** The least window width for [StartLayout.Low]; a narrower sideways window takes [StartLayout.Narrow], whose card scrolls. */
const val StartLowMinWidthDp = (LowColumnWidthDp + LowCardMinWidthDp).toFloat()

/**
 * The start screen's layout for a window [widthDp] × [heightDp]: compact as [isCompact] and upright as [pairingStacks]
 * decide. A compact window too narrow for the low column beside a card takes the narrow layout, held sideways or not.
 */
fun startLayout(widthDp: Float, heightDp: Float): StartLayout = when {
    widthDp >= StartBandMinWidthDp && heightDp >= StartBandMinHeightDp -> StartLayout.Band
    !isCompact(widthDp, heightDp) -> StartLayout.TopSteps
    widthDp < heightDp || widthDp < StartLowMinWidthDp -> StartLayout.Narrow
    else -> StartLayout.Low
}

/** The three steps from a new install to a terminal; the third is the workspace itself, so it is never the current one here. */
val StartSteps = listOf(
    "Log in to Tailscale" to "Once, in the browser",
    "Pick a computer" to "The ones that run Rex",
    "Use its terminals" to "Sessions and tabs as on the computer",
)

/** The step [step] stands at, counted from 1: logging in until the node is on the tailnet, then picking a computer. */
fun startStepNumber(step: StartStep): Int = if (step == StartStep.Found) 2 else 1

/** What the step indicator says to TalkBack: one description, so its labels are not read (or tapped) as controls. */
fun startStepsDescription(current: Int): String =
    "Step $current of ${StartSteps.size}: ${StartSteps[current - 1].first}"

/** The card's widest, in dp: it keeps a line of text readable on the unfolded screen. derived: the mockup approved for #165. */
private const val CardMaxWidthDp = 600

/** What the start card shows and what its buttons do: [StartScreen] reads it from the node, a preview makes it up. */
class StartCardModel(
    val step: StartStep,
    /** The login page open in the browser, or null ([TailnetLogin.waitingFor]). */
    val waitingFor: String?,
    /** A login page no browser could open, shown as an address to copy ([TailnetLogin.unopened]). */
    val unopened: String?,
    /** "Connected as <node>", or null ([connectedAsLine]). */
    val connectedAs: String?,
    /** The computers found on the tailnet that are not saved yet. */
    val found: List<DiscoveredServer>,
    val note: FoundNote,
    /** A search is under way: "Search again" waits for it. */
    val searching: Boolean,
    /** Opens the login page at a url, or asks the node for one (null). */
    val logIn: (String?) -> Unit,
    /** The page "Open the login page again" opens: the node's login page, or null for a new one ([loginPageAgain]). */
    val loginPageAgain: () -> String?,
    val cancelWait: () -> Unit,
    val tryAgain: () -> Unit,
    val searchAgain: () -> Unit,
    val connect: (DiscoveredServer) -> Unit,
    /** Opens Rex's installation guide; false when no browser can, so the card shows its address instead. */
    val getRex: () -> Boolean,
)

/**
 * Shown while no remote is saved. A node that is not logged in gets "Log in to Tailscale" (it
 * starts the node when it is off, then opens the login page); a node on the tailnet lists the
 * Rex servers found there, one tap to save one. Every layout ([startLayout]) keeps "Add by QR code
 * or address" and "Settings" reachable, scrolling to them where the window is too low for all of it.
 */
@Composable
fun StartScreen(app: AppController, onAddByQr: () -> Unit, onSettings: () -> Unit) {
    val state = Tailnet.state
    val step = startStep(state)
    LaunchedEffect(step) {
        if (step == StartStep.Found) Tailnet.sweepSoon()
    }
    // Above the layouts: folding or turning the phone while a login waits for its page, or for the browser, must not drop it.
    val login = rememberTailnetLogin(app)
    val uri = LocalUriHandler.current
    val found = notYetRows(Tailnet.discovered, app.saved)
    val model = StartCardModel(
        step = step, waitingFor = login.waitingFor, unopened = login.unopened, connectedAs = connectedAsLine(state),
        found = found, note = foundNote(found.size, Tailnet.paused, Tailnet.sweepsFinished), searching = Tailnet.searching,
        logIn = { url -> if (url != null) login.open(uri, url) else login.start() },
        loginPageAgain = { loginPageAgain(Tailnet.state) }, cancelWait = login::cancel,
        tryAgain = { app.setTailnet(true) }, searchAgain = { Tailnet.sweepSoon() },
        connect = { app.addRemote(it.name, it.url) },
        getRex = { try { uri.openUri(RexInstallationUrl); true } catch (_: IllegalArgumentException) { false } },
    )
    StartScreenBody(model, onAddByQr, onSettings, settingsBadge = updateDot())
}

/** The start screen for [model], laid out for the window ([startLayout]). */
@Composable
fun StartScreenBody(model: StartCardModel, onAddByQr: () -> Unit, onSettings: () -> Unit, settingsBadge: Boolean = false) {
    val window = LocalConfiguration.current
    val layout = startLayout(window.screenWidthDp.toFloat(), window.screenHeightDp.toFloat())
    val current = startStepNumber(model.step)
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val screen = Modifier.fillMaxSize().background(c.surface).windowInsetsPadding(WindowInsets.screenSafeDrawing)
    val card: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.verticalScroll(rememberScrollState())) { StartCard(model) }
    }
    when (layout) {
        StartLayout.Band -> Row(screen) {
            VeloSurface(VeloTheme.colors.sidebar) {
                Column(Modifier.width(s.sidebar).fillMaxHeight().background(VeloTheme.surface.surface)
                    .verticalScroll(rememberScrollState()).padding(s.rowPadding * 1.6f)) {
                    Wordmark()
                    Spacer(Modifier.height(s.rowPadding))
                    VerticalSteps(current, withHints = true)
                    Spacer(Modifier.height(s.rowPadding))
                    Text(appVersion(), style = VeloTheme.type.caption, color = VeloTheme.surface.secondary)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = s.rowPadding * 3, vertical = s.rowPadding * 2)) {
                card(Modifier.weight(1f).widthIn(max = CardMaxWidthDp.dp))
                Spacer(Modifier.height(s.menuPadding))
                Footer(onAddByQr, onSettings, Modifier.widthIn(max = CardMaxWidthDp.dp), withPrompt = true, settingsBadge)
            }
        }
        StartLayout.TopSteps -> Box(screen, contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = CardMaxWidthDp.dp).fillMaxHeight().padding(horizontal = s.rowPadding * 2, vertical = s.rowPadding * 1.6f)) {
                Wordmark()
                Spacer(Modifier.height(s.rowPadding))
                HorizontalSteps(current)
                Spacer(Modifier.height(s.rowPadding))
                card(Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(s.menuPadding))
                Footer(onAddByQr, onSettings, Modifier.fillMaxWidth(), withPrompt = true, settingsBadge)
            }
        }
        // Everything scrolls together, the links at the bottom of the window while they fit under the card: a very low
        // window (split screen, a large size) then scrolls to them, instead of a fixed footer squeezing the card to
        // nothing, as a wrapped footer of two lines does in a 320 × 220 dp window.
        StartLayout.Narrow -> BoxWithConstraints(screen.padding(horizontal = s.rowPadding, vertical = s.rowPadding)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Velo", color = c.text, style = VeloTheme.type.wordmark)
                    Spacer(Modifier.height(s.rowGap))
                    ProgressSteps(current)
                    Spacer(Modifier.height(s.rowGap))
                    StartCard(model)
                }
                Footer(onAddByQr, onSettings, Modifier.fillMaxWidth().padding(top = s.menuPadding), withPrompt = false, settingsBadge)
            }
        }
        StartLayout.Low -> Row(screen) {
            VeloSurface(VeloTheme.colors.sidebar) {
                Column(Modifier.width(LowColumnWidthDp.dp).fillMaxHeight().background(VeloTheme.surface.surface)
                    .padding(horizontal = s.rowPadding, vertical = s.rowGap)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text("Velo", color = VeloTheme.surface.text, style = VeloTheme.type.screenTitle)
                        Spacer(Modifier.height(s.rowGap))
                        VerticalSteps(current, withHints = false)
                    }
                    StartLink("Add by QR code or address", onAddByQr)
                    StartLink("Settings", onSettings, quiet = true, badge = settingsBadge)
                }
            }
            // The weight takes the rest of the row; the card inside it keeps its widest.
            Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = s.rowPadding * 1.2f, vertical = s.rowGap)) {
                card(Modifier.widthIn(max = CardMaxWidthDp.dp).fillMaxHeight())
            }
        }
    }
}

/** The wordmark and the line under it, on the wide layouts. */
@Composable
private fun Wordmark() {
    Text("Velo", color = VeloTheme.surface.text, style = VeloTheme.type.wordmark)
    Spacer(Modifier.height(VeloTheme.sizes.menuPadding))
    Text("Your computer's terminals, on this phone.", color = VeloTheme.surface.secondary)
}

/** The app's name and version, as the settings' About shows them. */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${context.applicationInfo.loadLabel(context.packageManager)} ${info.versionName}"
    }
}

/** What the node's state asks of the user, on a card ([model]): the same content in every layout. */
@Composable
private fun StartCard(model: StartCardModel) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Column(Modifier.fillMaxWidth().clip(rounded(VeloTheme.radii.card)).background(c.selected)
        .border(s.edge, c.divider, rounded(VeloTheme.radii.card)).padding(s.rowPadding)) {
        when (val step = model.step) {
            is StartStep.LogIn -> LoginCard(step, model)
            StartStep.Starting -> Text("Starting Tailscale…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = c.secondary)
            is StartStep.Failed -> {
                Text("Tailscale: ${step.message}", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = c.danger)
                Spacer(Modifier.height(s.menuPadding))
                VeloButton("Try again", model.tryAgain, kind = ButtonKind.Primary)
            }
            StartStep.Found -> {
                model.connectedAs?.let { ConnectedAs(it) }
                ComputersHeading(model.found.size)
                if (model.note.offersSearchAgain) NothingFoundNote(model.note, model.searching, model.searchAgain, model.getRex)
                else model.note.text?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = c.secondary) }
                FoundRows(model.found, model.connect)
            }
        }
    }
}

/**
 * The login step. Before the login page opens: why to log in, and the button that opens it. While the browser has it
 * ([loginCardView]): that Velo waits, announced without a tap, a way back to the page and a way out of the wait.
 */
@Composable
private fun LoginCard(step: StartStep.LogIn, model: StartCardModel) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    when (loginCardView(model.waitingFor, step)) {
        LoginCardView.Ask -> {
            Text("Velo finds the computers on your tailnet that run Rex. You log in once, in your browser.", color = c.secondary)
            Spacer(Modifier.height(s.rowPadding))
            VeloButton("Log in to Tailscale", kind = ButtonKind.Primary, onClick = { model.logIn(step.url) })
            model.unopened?.let {
                Spacer(Modifier.height(s.menuPadding))
                SelectionContainer { Text("Open in a browser: $it", color = c.secondary) }
            }
        }
        LoginCardView.Waiting -> {
            Row(horizontalArrangement = Arrangement.spacedBy(s.menuPadding), verticalAlignment = Alignment.CenterVertically) {
                // The text says it; the spinner only shows it.
                CircularProgressIndicator(Modifier.size(s.statusIcon).clearAndSetSemantics {}, color = c.accent, strokeWidth = s.edge * 3)
                Text("Waiting for the browser…", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = c.text, style = VeloTheme.type.bodyStrong)
            }
            Spacer(Modifier.height(s.menuPadding))
            Text("Finish logging in in the browser, then come back to Velo. This screen moves on by itself.", color = c.secondary)
            Spacer(Modifier.height(s.rowPadding))
            CardButtons {
                VeloButton("Open the login page again", { model.logIn(model.loginPageAgain()) })
                VeloButton("Cancel", model.cancelWait)
            }
        }
    }
}

/** A card's buttons side by side, the next line when the card is too narrow for them. */
@Composable
private fun CardButtons(content: @Composable FlowRowScope.() -> Unit) {
    val s = VeloTheme.sizes
    FlowRow(horizontalArrangement = Arrangement.spacedBy(s.menuPadding), verticalArrangement = Arrangement.spacedBy(s.menuPadding),
        itemVerticalAlignment = Alignment.CenterVertically, content = content)
}

/** [ComputersHeader] with its count at the end: a narrow card cuts the heading, never the count. Read as one line. */
@Composable
private fun ComputersHeading(found: Int) {
    val count = computersCount(found)
    SectionHeader(ComputersHeader, Modifier.clearAndSetSemantics { contentDescription = listOfNotNull(ComputersHeader, count).joinToString(" ") },
        trailing = count?.let { { Text(it, Modifier.padding(start = VeloTheme.sizes.menuPadding), style = VeloTheme.type.header, color = VeloTheme.surface.secondary) } })
}

/** "Connected as <node>" with the green dot of a running node: one line, the end of a long MagicDNS name cut with "…". */
@Composable
private fun ConnectedAs(line: String) {
    val s = VeloTheme.sizes
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = line }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.menuPadding)) {
        StatusMark(Status.Ok, line)
        Text(line, Modifier.weight(1f), color = VeloTheme.surface.secondary, style = VeloTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The computers found on the tailnet that are not saved yet, a hairline between two; the first one's Connect is the card's one action. */
@Composable
private fun FoundRows(found: List<DiscoveredServer>, onAdd: (DiscoveredServer) -> Unit) {
    found.forEachIndexed { index, server ->
        if (index > 0) GroupDivider()
        ConnectRow(server, if (index == 0) ButtonKind.Primary else ButtonKind.Secondary) { onAdd(server) }
    }
}

/**
 * A found computer: its icon on a glass tile, its name and address, and Connect, which saves it and connects to it.
 * The text takes what Connect leaves, so a long name or address is cut with "…" and Connect stays in the row.
 */
@Composable
private fun ConnectRow(server: DiscoveredServer, kind: ButtonKind, onConnect: () -> Unit) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val label = connectLabel(server)
    Row(Modifier.fillMaxWidth().heightIn(min = s.row), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(s.tile).clip(rounded(VeloTheme.radii.tile)).background(c.glass), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Computer, null, Modifier.size(s.iconSmall), tint = c.text)
        }
        Spacer(Modifier.width(s.rowGap))
        Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "${server.name}, ${server.url}" }) {
            Text(server.name, style = VeloTheme.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(server.url, style = VeloTheme.type.caption, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(s.rowGap))
        // The clearing comes before VeloButton's own clickable, so it drops that click's action too: the button gets it back here.
        VeloButton("Connect", onConnect, Modifier.clearAndSetSemantics {
            contentDescription = label
            role = Role.Button
            onClick { onConnect(); true }
        }, kind = kind)
    }
}

/** Where "Get Rex for Mac" sends the user: Rex's installation guide. */
const val RexInstallationUrl = "https://www.superlogical.com/rex/docs/installation"

/** What the user can do when a search finds no computer, listed under the note in order. */
private val NothingFoundSteps = listOf(
    "Install Rex on your computer and log it in to the same tailnet.",
    "Or ask the owner to share their computer with you in Tailscale.",
)

/**
 * A search ended and found nothing ([note]): say so, announced without a tap, list what to do about it, and offer to
 * search again (not while [searching]) or to get Rex ([getRex]). When no browser opens it, the installation address
 * shows as selectable text instead.
 */
@Composable
private fun NothingFoundNote(note: FoundNote, searching: Boolean, searchAgain: () -> Unit, getRex: () -> Boolean) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    note.text?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = c.secondary) }
    Spacer(Modifier.height(s.menuPadding))
    Column(verticalArrangement = Arrangement.spacedBy(s.menuPadding)) {
        for (step in NothingFoundSteps) Row(horizontalArrangement = Arrangement.spacedBy(s.menuPadding)) {
            Text("•", Modifier.clearAndSetSemantics {}, color = c.secondary)
            Text(step, Modifier.weight(1f), color = c.secondary)
        }
    }
    Spacer(Modifier.height(s.rowPadding))
    var browserUnavailable by remember { mutableStateOf(false) }
    CardButtons {
        VeloButton("Search again", searchAgain, kind = ButtonKind.Primary, enabled = !searching)
        VeloButton("Get Rex for Mac", { browserUnavailable = !getRex() })
    }
    if (browserUnavailable) {
        Spacer(Modifier.height(s.menuPadding))
        SelectionContainer { Text("Open in a browser: $RexInstallationUrl", color = c.secondary) }
    }
}

/**
 * "Add by QR code or address" and "Settings" under the card; [withPrompt] puts "No Tailscale?" before the first.
 * [settingsBadge] puts the update dot on Settings.
 */
@Composable
private fun Footer(onAddByQr: () -> Unit, onSettings: () -> Unit, modifier: Modifier, withPrompt: Boolean, settingsBadge: Boolean) {
    val s = VeloTheme.sizes
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(s.rowPadding), verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically) {
        if (withPrompt) Text("No Tailscale?", color = VeloTheme.surface.secondary)
        StartLink("Add by QR code or address", onAddByQr)
        Spacer(Modifier.weight(1f))
        StartLink("Settings", onSettings, quiet = true, badge = settingsBadge)
    }
}

/**
 * A link-like text button: accent text, or secondary when [quiet], with a touch target of [VeloSizes.control] or more.
 * [badge] puts the raised accent dot after the text: an update was found ([AttentionBadge]).
 */
@Composable
private fun StartLink(text: String, onClick: () -> Unit, quiet: Boolean = false, badge: Boolean = false) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Row(Modifier.heightIn(min = s.control).clip(Capsule).clickable(role = Role.Button, onClick = onClick)
        .semantics { if (badge) stateDescription = "Update available" }
        .padding(horizontal = s.gapCompact), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.gapCompact)) {
        Text(text, color = if (quiet) c.secondary else c.accent, style = VeloTheme.type.body)
        if (badge) AttentionBadge(c.accent, Modifier.size(s.mark))
    }
}

/** The mark of step [number]: its number, a check once it is done, filled while it is the current one. */
@Composable
private fun StepMark(number: Int, current: Int) {
    val c = VeloTheme.surface
    val done = number < current
    val now = number == current
    val size = VeloTheme.sizes.icon + VeloTheme.sizes.gapCompact
    Box(Modifier.size(size).clip(CircleShape)
        .then(if (done) Modifier.background(c.ok) else if (now) Modifier.background(c.accent) else Modifier.border(VeloTheme.sizes.edge * 2, c.divider, CircleShape)),
        contentAlignment = Alignment.Center) {
        Text(if (done) "✓" else "$number", style = VeloTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
            color = if (done || now) c.onAccent else c.secondary)
    }
}

/** The steps one under another, with their hints when [withHints]: the band and the low column. */
@Composable
private fun VerticalSteps(current: Int, withHints: Boolean) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Column(Modifier.clearAndSetSemantics { contentDescription = startStepsDescription(current); liveRegion = LiveRegionMode.Polite }) {
        StartSteps.forEachIndexed { i, (label, hint) ->
            val number = i + 1
            if (i > 0) Box(Modifier.padding(start = (s.icon + s.gapCompact) / 2 - s.edge, top = s.gapCompact, bottom = s.gapCompact)
                .width(s.edge * 2).height(s.rowPadding).background(c.divider))
            Row(horizontalArrangement = Arrangement.spacedBy(s.rowGap), verticalAlignment = Alignment.Top) {
                StepMark(number, current)
                Column {
                    Text(label, color = if (number == current) c.text else c.secondary,
                        style = if (number == current) VeloTheme.type.bodyStrong else VeloTheme.type.body)
                    if (withHints) Text(hint, style = VeloTheme.type.caption, color = c.secondary)
                }
            }
        }
    }
}

/** The steps side by side, joined by lines: the unfolded screen held upright. */
@Composable
private fun HorizontalSteps(current: Int) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = startStepsDescription(current); liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Top) {
        StartSteps.forEachIndexed { i, (label, _) ->
            val number = i + 1
            if (i > 0) Box(Modifier.weight(1f).padding(top = (s.icon + s.gapCompact) / 2).height(s.edge * 2).background(c.divider))
            Column(Modifier.widthIn(max = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StepMark(number, current)
                Spacer(Modifier.height(s.gapCompact))
                Text(label, style = VeloTheme.type.caption, textAlign = TextAlign.Center,
                    color = if (number == current) c.text else c.secondary)
            }
        }
    }
}

/** A bar of three parts and "Step n of 3 · <step>": a compact upright window. */
@Composable
private fun ProgressSteps(current: Int) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Column(Modifier.clearAndSetSemantics { contentDescription = startStepsDescription(current); liveRegion = LiveRegionMode.Polite }) {
        Row(horizontalArrangement = Arrangement.spacedBy(s.menuPadding)) {
            for (number in 1..StartSteps.size) Box(Modifier.weight(1f).height(s.gapCompact).clip(Capsule)
                .background(if (number < current) c.ok else if (number == current) c.accent else c.divider))
        }
        Spacer(Modifier.height(s.menuPadding))
        Text("Step $current of ${StartSteps.size} · ${StartSteps[current - 1].first}", style = VeloTheme.type.caption, color = c.secondary)
    }
}

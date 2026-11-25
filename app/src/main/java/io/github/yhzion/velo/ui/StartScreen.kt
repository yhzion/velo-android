package io.github.yhzion.velo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.yhzion.velo.AppController
import io.github.yhzion.velo.DiscoveredServer
import io.github.yhzion.velo.R
import io.github.yhzion.velo.Tailnet
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.notYetRows
import io.github.yhzion.velo.ui.theme.AppMark
import io.github.yhzion.velo.ui.theme.AttentionBadge
import io.github.yhzion.velo.ui.theme.ButtonKind
import io.github.yhzion.velo.ui.theme.Capsule
import io.github.yhzion.velo.ui.theme.RaisedCard
import io.github.yhzion.velo.ui.theme.Status
import io.github.yhzion.velo.ui.theme.StatusMark
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloSurface
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.rounded
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.roundToInt

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

/** Where the start screen puts its steps, from the window's width ([startLayout]). */
enum class StartLayout {
    /** The steps above the card: the bar phone and the folded Fold8 (StartKit board, shapes bar and closed). */
    Stacked,
    /** The steps beside the card, both centred above the links: the unfolded Fold8 (shape open), and any window as wide. */
    SideBySide,
}

// The side-by-side layout (StartKit board, shape open): the steps' column, the gap, the card's widest and the margins.
private const val SideColumnDp = 300
private const val SideGapDp = 56
private const val SideCardMaxDp = 400
private const val SideMarginDp = 48

/** The narrowest card beside the steps: a 48 dp button with its label and the card's padding. derived: the low layout's card before the redesign. */
private const val SideCardMinDp = 320

/** The least window width that holds the steps beside a card. */
const val StartSideBySideMinWidthDp = (SideColumnDp + SideGapDp + SideCardMinDp + 2 * SideMarginDp).toFloat()

/** The start screen's layout for a window [widthDp] wide. A window too low for all of it scrolls, in either layout. */
fun startLayout(widthDp: Float): StartLayout =
    if (widthDp >= StartSideBySideMinWidthDp) StartLayout.SideBySide else StartLayout.Stacked

/** The height from which the stacked layout starts 48 dp down, as on the bar phone; lower windows start 36 down, as the folded Fold8. derived: half way from 751 to 894. */
private const val StartTallFromDp = 822f

/** The space above the stacked layout in a window [heightDp] high (StartKit board: 48 on the bar phone, 36 on the folded Fold8). */
fun startTopPadding(heightDp: Float): Float = if (heightDp >= StartTallFromDp) 48f else 36f

/** The three steps from a new install to a terminal; the third is shown while the computer picked opens ([openingView]). */
val StartSteps = listOf(
    "Log in to Tailscale" to "Once, in the browser",
    "Pick a computer" to "The ones that run Rex",
    "Use its terminals" to "Sessions and tabs as on the computer",
)

/** The step [step] stands at, counted from 1: logging in until the node is on the tailnet, then picking a computer, then [opening] it. */
fun startStepNumber(step: StartStep, opening: Boolean = false): Int = when {
    opening -> 3
    step == StartStep.Found -> 2
    else -> 1
}

/** What the step indicator says to TalkBack: one description, so its labels are not read (or tapped) as controls. */
fun startStepsDescription(current: Int): String =
    "Step $current of ${StartSteps.size}: ${StartSteps[current - 1].first}"

/** The line above the card: "Step 2 of 3 · Pick a computer". */
fun startStepLine(current: Int): String = "Step $current of ${StartSteps.size} · ${StartSteps[current - 1].first}"

/** How a step's disc is drawn. */
enum class StepMarker { Done, Now, Next }

/** The disc of step [number] while [current] is the current step: a check once done, filled while current. */
fun stepMarker(number: Int, current: Int): StepMarker = when {
    number < current -> StepMarker.Done
    number == current -> StepMarker.Now
    else -> StepMarker.Next
}

/** How long a Connect waits for its computer before the start screen shows step 3: a fast connection goes straight to the workspace (owner decision 4). */
const val StartOpeningDelayMs = 400L

/** What the screen shows after the start screen's Connect saved a computer ([openingView]). */
enum class OpeningView {
    /** No computer is opening, or it opened or failed: the workspace, as for any saved remote. */
    None,
    /** It is opening and the [StartOpeningDelayMs] has not passed: the start screen as it was. */
    Hold,
    /** It is taking a while: step 3, "Opening <computer>", with Cancel. */
    Show,
}

/**
 * The screen while a computer the start screen saved is [opening]: it is [connected], or [failed] (the workspace shows
 * why and how to try again), or still on its way, [slow] once [StartOpeningDelayMs] passed.
 */
fun openingView(opening: Boolean, connected: Boolean, failed: Boolean, slow: Boolean): OpeningView = when {
    !opening || connected || failed -> OpeningView.None
    slow -> OpeningView.Show
    else -> OpeningView.Hold
}

/**
 * The opening bar's fill [elapsedMs] after step 3 showed. The connection reports no progress, so the bar shows time:
 * it moves on quickly at first, then slower, and never reaches the end it cannot promise. derived.
 */
fun openingProgress(elapsedMs: Long): Float = 0.9f - 0.85f * exp(-elapsedMs / 4000f)

/** A computer the start screen's Connect saved while its first connection is under way ([openingView]). */
@Immutable
data class StartOpening(val name: String, val url: String)

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
    /** A search is under way: "Search again" waits for it, and the list says it is still looking. */
    val searching: Boolean,
    /** Opens the login page at a url, or asks the node for one (null). */
    val logIn: (String?) -> Unit,
    /** The page "Open the login page again" opens: the node's login page, or null for a new one ([loginPageAgain]). */
    val loginPageAgain: () -> String?,
    val tryAgain: () -> Unit,
    val searchAgain: () -> Unit,
    val connect: (DiscoveredServer) -> Unit,
    /** Opens Rex's installation guide; false when no browser can, so the card shows its address instead. */
    val getRex: () -> Boolean,
    /** The computer step 3 opens, or null while it is not shown ([OpeningView.Show]). */
    val opening: String? = null,
    /** The opening bar's fill ([openingProgress]). */
    val openingProgress: Float = 0f,
    /** Stops opening the computer and forgets it: back to picking one. */
    val cancelOpening: () -> Unit = {},
)

/**
 * Shown while no remote is saved, and while the first one opens. A node that is not logged in gets "Log in to Tailscale"
 * (it starts the node when it is off, then opens the login page); a node on the tailnet lists the Rex servers found
 * there, one tap to save one ([onConnect]). A computer that takes a while to open shows step 3 ([opening]); [keep] is
 * the one being opened, still listed until then. Every layout keeps "Add by QR code or address" and "Settings" reachable,
 * scrolling to them where the window is too low for all of it.
 */
@Composable
fun StartScreen(
    app: AppController, onAddByQr: () -> Unit, onSettings: () -> Unit,
    opening: StartOpening? = null, keep: String? = null,
    onConnect: (DiscoveredServer) -> Unit = { app.addRemote(it.name, it.url) }, onCancelOpening: () -> Unit = {},
) {
    val state = Tailnet.state
    val step = startStep(state)
    LaunchedEffect(step) {
        if (step == StartStep.Found) Tailnet.sweepSoon()
    }
    // Above the layouts: folding or turning the phone while a login waits for its page, or for the browser, must not drop it.
    val login = rememberTailnetLogin(app)
    val uri = LocalUriHandler.current
    val found = notYetRows(Tailnet.discovered, app.saved.filterNot { it.url == keep })
    val progress by produceState(openingProgress(0), opening) {
        if (opening == null) return@produceState
        val start = withFrameMillis { it }
        while (true) value = openingProgress(withFrameMillis { it } - start)
    }
    val model = StartCardModel(
        step = step, waitingFor = login.waitingFor, unopened = login.unopened, connectedAs = connectedAsLine(state),
        found = found, note = foundNote(found.size, Tailnet.paused, Tailnet.sweepsFinished), searching = Tailnet.searching,
        logIn = { url -> if (url != null) login.open(uri, url) else login.start() },
        loginPageAgain = { loginPageAgain(Tailnet.state) },
        tryAgain = { app.setTailnet(true) }, searchAgain = { Tailnet.sweepSoon() },
        connect = onConnect,
        getRex = { try { uri.openUri(RexInstallationUrl); true } catch (_: IllegalArgumentException) { false } },
        opening = opening?.name, openingProgress = progress, cancelOpening = onCancelOpening,
    )
    StartScreenBody(model, onAddByQr, onSettings, settingsBadge = updateDot())
}

/** A length the StartKit board gives at Medium, as text size at the current Size setting. */
@Composable @ReadOnlyComposable
private fun boardSp(value: Float): TextUnit = VeloTheme.medium(value.dp).value.sp

/** A line of text at [size] with the browser's "normal" line height (the font's own), as the board's single lines. */
@Composable @ReadOnlyComposable
private fun oneLine(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
    VeloTheme.type.body.copy(fontSize = boardSp(size), lineHeight = TextUnit.Unspecified, fontWeight = weight)

/**
 * The browser's "normal" line height of Roboto at [size]: its ascent and descent (1900 and 500 of 2048), each rounded
 * to a whole pixel, as the StartKit board's single lines measure. Compose rounds them up at the device's density instead.
 */
internal fun cssLineHeight(size: Float): Float = round(size * 1900f / 2048f) + round(size * 500f / 2048f)

/**
 * One line of [text] at [size] in a box as tall as the board's line ([cssLineHeight]), the glyphs centred in it. The box
 * is in sp, so it grows with the font scale as the text does; the text may draw past it but is never cut.
 */
@Composable
private fun LineText(
    text: String, size: Float, color: Color, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Normal,
    style: TextStyle = oneLine(size, weight),
) {
    val height = with(LocalDensity.current) { (VeloTheme.medium(cssLineHeight(size).dp).value).sp.toDp() }
    Text(text, modifier.height(height).wrapContentHeight(unbounded = true), style = style, color = color, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
}

/**
 * A column whose children are [gap] apart as the browser places them: the gaps are added up unrounded and each child
 * goes to the pixel nearest its sum. A Column rounds each gap to a pixel instead, and at the Velvet's density (2.625)
 * the board's gaps of 4, 12 and 28 dp all fall on half a pixel and round up, so a screen of them drifts down by a pixel
 * or two. [top] is space above the first child, added the same way. As in a Column, children may be narrower than the
 * column and start at its start edge.
 */
@Composable
private fun CssColumn(gap: Dp, modifier: Modifier = Modifier, top: Dp = 0.dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val step = gap.toPx()
        val tops = FloatArray(placeables.size)
        var y = top.toPx()
        placeables.forEachIndexed { i, p -> tops[i] = y; y += p.height + step }
        val end = if (placeables.isEmpty()) top.toPx() else y - step
        val width = maxOf(placeables.maxOfOrNull { it.width } ?: 0, constraints.minWidth)
        layout(width, end.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEachIndexed { i, p -> p.place(0, tops[i].roundToInt()) }
        }
    }
}

/**
 * A paragraph at [size], its lines [height] em apart with the space split above and below them, as CSS draws it. Compose
 * rounds each line up to a whole pixel, where the browser keeps the fraction; the line is set to the nearest whole pixel
 * instead, so that a paragraph of a few lines ends where the board's does.
 */
@Composable @ReadOnlyComposable
private fun paragraph(size: Float, height: Float = 1.5f): TextStyle {
    val fontSize = boardSp(size)
    val lineHeight = with(LocalDensity.current) {
        // Just under the whole pixel, which Compose's rounding up then lands on.
        (round((fontSize * height).toPx()) - 0.01f).toSp()
    }
    return VeloTheme.type.body.copy(
        fontSize = fontSize, lineHeight = lineHeight,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}

/** The start screen for [model], laid out for the window ([startLayout]) on the sidebar's colours (StartKit board). */
@Composable
fun StartScreenBody(model: StartCardModel, onAddByQr: () -> Unit, onSettings: () -> Unit, settingsBadge: Boolean = false) {
    val current = startStepNumber(model.step, opening = model.opening != null)
    VeloSurface(VeloTheme.colors.sidebar) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("screen-start").background(VeloTheme.surface.surface)
            .windowInsetsPadding(WindowInsets.screenSafeDrawing)) {
            val layout = startLayout(maxWidth.value)
            val top = startTopPadding(maxHeight.value)
            // Everything scrolls together, the links at the bottom of the window while they fit under the rest: a very low
            // window (split screen, a large size) then scrolls to them, instead of a fixed footer squeezing the card to nothing.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                verticalArrangement = Arrangement.SpaceBetween) {
                when (layout) {
                    StartLayout.Stacked -> CssColumn(VeloTheme.medium(28.dp),
                        Modifier.fillMaxWidth().padding(start = VeloTheme.medium(24.dp), end = VeloTheme.medium(24.dp)), top = VeloTheme.medium(top.dp),
                    ) {
                        StartIntro(current, wide = false, Modifier.fillMaxWidth())
                        StartAction(model, current, Modifier.fillMaxWidth())
                    }
                    StartLayout.SideBySide -> {
                        // An empty first child: the space between spreads evenly, so the row is centred above the links.
                        Spacer(Modifier)
                        Row(Modifier.fillMaxWidth().padding(horizontal = VeloTheme.medium(SideMarginDp.dp), vertical = VeloTheme.medium(24.dp)),
                            horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(SideGapDp.dp), Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically) {
                            StartIntro(current, wide = true, Modifier.width(VeloTheme.medium(SideColumnDp.dp)))
                            StartAction(model, current, Modifier.weight(1f, fill = false).widthIn(max = VeloTheme.medium(SideCardMaxDp.dp)))
                        }
                    }
                }
                StartFooter(onAddByQr, onSettings, settingsBadge, Modifier.fillMaxWidth(),
                    horizontal = VeloTheme.medium(if (layout == StartLayout.SideBySide) 40.dp else 16.dp))
            }
        }
    }
}

/** The app's mark, the wordmark, the line under them and the steps: the screen's start column. */
@Composable
private fun StartIntro(current: Int, wide: Boolean, modifier: Modifier) {
    val c = VeloTheme.surface
    CssColumn(VeloTheme.medium(28.dp), modifier.testTag("start-intro")) {
        CssColumn(VeloTheme.medium(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp))) {
                AppMark(Modifier.testTag("start-mark").size(VeloTheme.medium(44.dp), VeloTheme.medium(31.dp)))
                val word = VeloTheme.type.wordmark
                val size = if (wide) 44f else 38f
                // The board's line is as tall as the type (line-height 1), the glyphs centred in it; Compose does not draw a
                // line shorter than the font's own, so the box is set to that height and the text centred over it.
                val height = with(LocalDensity.current) { (word.fontSize * (size / 38f)).toDp() }
                Box(Modifier.testTag("start-wordmark").height(height).wrapContentHeight(unbounded = true),
                    contentAlignment = Alignment.Center) {
                    Text("Velo", Modifier.semantics { heading() }, color = c.text,
                        style = word.copy(fontSize = word.fontSize * (size / 38f), lineHeight = TextUnit.Unspecified))
                }
            }
            Text("Your computer's terminals, on this phone.", Modifier.fillMaxWidth().testTag("start-tagline"),
                style = paragraph(17f, 1.4f), color = VeloTheme.colors.textOnRaised)
        }
        StartStepList(current)
    }
}

/** The filled disc of a step that is done (StartKit board: a dark green behind the green check). */
private val StepDoneFill = Color(0xFF1E3A2A)

/**
 * The three steps, one under another, with their hints: where the user is and what comes next. TalkBack reads the
 * list as one description, announced as it changes, so its labels are not read (or tapped) as controls.
 */
@Composable
private fun StartStepList(current: Int) {
    val c = VeloTheme.surface
    CssColumn(VeloTheme.medium(4.dp), Modifier.fillMaxWidth().testTag("start-steps").semantics {
        contentDescription = startStepsDescription(current)
        liveRegion = LiveRegionMode.Polite
    }) {
        StartSteps.forEachIndexed { i, (title, hint) ->
            val number = i + 1
            val marker = stepMarker(number, current)
            Row(Modifier.fillMaxWidth().testTag("start-step-$number").semantics { hideFromAccessibility() }.padding(vertical = VeloTheme.medium(6.dp)),
                horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp))) {
                StepDisc(number, marker, Modifier.testTag("start-step-$number-marker"))
                // As wide as the longer of its two lines, as the board's column is.
                Column(Modifier.width(IntrinsicSize.Max).padding(top = VeloTheme.medium(3.dp)), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(2.dp))) {
                    LineText(title, 16f, modifier = Modifier.fillMaxWidth().testTag("start-step-$number-title"),
                        weight = if (marker == StepMarker.Now) FontWeight.SemiBold else FontWeight.Normal,
                        color = when (marker) {
                            StepMarker.Now -> c.text
                            StepMarker.Done -> VeloTheme.colors.textOnRaised
                            StepMarker.Next -> c.secondary
                        })
                    LineText(hint, 13f, c.secondary, Modifier.fillMaxWidth().testTag("start-step-$number-subtitle"))
                }
            }
        }
    }
}

/** The disc of a step: its number, a check once it is done, filled with the accent while it is the current one. */
@Composable
private fun StepDisc(number: Int, marker: StepMarker, modifier: Modifier) {
    val c = VeloTheme.surface
    val size = VeloTheme.medium(28.dp)
    val frame = when (marker) {
        StepMarker.Done -> Modifier.background(StepDoneFill, CircleShape)
        StepMarker.Now -> Modifier.background(c.accent, CircleShape)
        StepMarker.Next -> Modifier.border(VeloTheme.medium(1.5.dp), c.glassSelected, CircleShape)
    }
    Box(modifier.size(size).then(frame), contentAlignment = Alignment.Center) {
        Text(if (marker == StepMarker.Done) "✓" else "$number", style = oneLine(14f, FontWeight.SemiBold), color = when (marker) {
            StepMarker.Done -> c.ok
            StepMarker.Now -> c.onAccent
            StepMarker.Next -> c.secondary
        })
    }
}

/** "Step n of 3 · <step>" and the card of the current step: the screen's action column. */
@Composable
private fun StartAction(model: StartCardModel, current: Int, modifier: Modifier) {
    val c = VeloTheme.surface
    CssColumn(VeloTheme.medium(12.dp), modifier.testTag("start-action")) {
        // The steps' description says the same to TalkBack.
        LineText(startStepLine(current), 13f, c.secondary, Modifier.fillMaxWidth().testTag("start-step-line")
            .semantics { hideFromAccessibility() }.padding(horizontal = VeloTheme.medium(4.dp)))
        val opening = model.opening
        if (opening != null) {
            OpeningCard(opening, model.openingProgress, model.cancelOpening)
            return@CssColumn
        }
        when (val step = model.step) {
            is StartStep.LogIn -> when (loginCardView(model.waitingFor, step)) {
                LoginCardView.Ask -> LoginCard(step, model)
                LoginCardView.Waiting -> WaitingCard(model)
            }
            StartStep.Starting -> StartCard {
                CardHeading("Starting Tailscale…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { Spinner(VeloTheme.medium(28.dp), c.accent) }
            }
            is StartStep.Failed -> StartCard {
                CardHeading("Log in to Tailscale") { TailscaleLogo() }
                Text("Tailscale: ${step.message}", Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    style = paragraph(16f), color = c.dangerText)
                VeloButton("Try again", model.tryAgain, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
            }
            StartStep.Found -> {
                model.connectedAs?.let { ConnectedAs(it) }
                if (model.note.offersSearchAgain) NoComputerCard(model) else PickerCard(model)
            }
        }
    }
}

/**
 * A card of the start screen: the raised card at the StartKit board's padding and spacing. The card's 1 dp border sits
 * inside its box, as CSS draws it, so the content starts a border further in.
 */
@Composable
private fun StartCard(
    modifier: Modifier = Modifier, padding: Dp = VeloTheme.medium(20.dp), spacing: Dp = VeloTheme.medium(16.dp),
    content: @Composable () -> Unit,
) {
    // The card's own spacing is left out: its content is spaced as the browser does ([CssColumn]).
    RaisedCard(modifier, padding = padding + VeloTheme.sizes.edge, spacing = 0.dp) {
        CssColumn(spacing, Modifier.fillMaxWidth(), content = content)
    }
}

/** A card's [title] after its [lead] (a logo, a spinner, an icon). */
@Composable
private fun CardHeading(title: String, modifier: Modifier = Modifier, titleTag: String? = null, lead: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(12.dp))) {
        lead()
        LineText(title, 20f, VeloTheme.surface.text, modifier.then(if (titleTag != null) Modifier.testTag(titleTag) else Modifier)
            .semantics { heading() }, weight = FontWeight.SemiBold)
    }
}

/** Tailscale's logo on a glass tile, before the login card's title. */
@Composable
private fun TailscaleLogo() {
    val c = VeloTheme.surface
    Box(Modifier.testTag("login-tailscale-logo").size(VeloTheme.medium(40.dp)).clip(rounded(VeloTheme.medium(10.dp))).background(c.glass),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_start_tailscale), null, Modifier.size(VeloTheme.medium(22.dp)), tint = c.text)
    }
}

/**
 * The login step before the login page opens: why to log in, and the button that opens it. Without a browser the page's
 * address shows under it, to copy.
 */
@Composable
private fun LoginCard(step: StartStep.LogIn, model: StartCardModel) {
    StartCard(Modifier.testTag("login-card")) {
        CardHeading("Log in to Tailscale", titleTag = "login-title") { TailscaleLogo() }
        Text("Velo finds the computers on your tailnet that run Rex. You log in once, in your browser.",
            Modifier.fillMaxWidth().testTag("login-body"), style = paragraph(16f), color = VeloTheme.colors.textOnRaised)
        VeloButton("Log in to Tailscale", { model.logIn(step.url) }, Modifier.fillMaxWidth().testTag("login-button"),
            kind = ButtonKind.Primary, icon = painterResource(R.drawable.ic_open_external), iconAtEnd = true)
        model.unopened?.let {
            SelectionContainer { Text("Open in a browser: $it", style = paragraph(15f), color = VeloTheme.colors.textOnRaised) }
        }
    }
}

/** The login page is open ([LoginCardView.Waiting]): that Velo waits, announced without a tap, and a way back to the page. */
@Composable
private fun WaitingCard(model: StartCardModel) {
    StartCard(Modifier.testTag("login-wait-card")) {
        CardHeading("Waiting for the browser…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, titleTag = "login-wait-title") {
            Spinner(VeloTheme.medium(28.dp), VeloTheme.surface.accent, Modifier.testTag("login-wait-spinner"))
        }
        Text("Finish logging in in the browser, then come back to Velo. This screen moves on by itself.",
            Modifier.fillMaxWidth().testTag("login-wait-body"), style = paragraph(16f), color = VeloTheme.colors.textOnRaised)
        VeloButton("Open the login page again", { model.logIn(model.loginPageAgain()) }, Modifier.fillMaxWidth().testTag("login-reopen-button"))
    }
}

/**
 * A spinner: a quarter of a ring in [color] over the ring in the divider's colour, turning. Only drawn: the text beside it
 * says what it waits for.
 */
@Composable
private fun Spinner(size: Dp, color: Color, modifier: Modifier = Modifier, stroke: Float = 3f / 28f) {
    val track = VeloTheme.surface.divider
    val turn by rememberInfiniteTransition(label = "spinner")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "turn")
    Canvas(modifier.size(size).graphicsLayer { rotationZ = turn }) {
        val width = this.size.minDimension * stroke
        val radius = this.size.minDimension * 11f / 28f
        drawCircle(track, radius, style = Stroke(width))
        drawArc(color, -90f, 90f, false, topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2),
            style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** "Connected as <node>" with the green dot of a running node: one line, the end of a long MagicDNS name cut with "…". */
@Composable
private fun ConnectedAs(line: String) {
    Row(Modifier.fillMaxWidth().testTag("tailnet-connected").clearAndSetSemantics { contentDescription = line }.padding(horizontal = VeloTheme.medium(4.dp)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(8.dp))) {
        StatusMark(Status.Ok, line)
        LineText(line, 14f, VeloTheme.colors.textOnRaised, Modifier.weight(1f))
    }
}

/** The card's title over the computers found ([PickerCard], [NoComputerCard]). */
@Composable
private fun PickerTitle() {
    LineText("Pick a computer", 20f, VeloTheme.surface.text, Modifier.fillMaxWidth().testTag("computer-picker-title").semantics { heading() },
        weight = FontWeight.SemiBold)
}

/**
 * The computers found on the tailnet that are not saved yet, each with its Connect, and under them whether Velo is
 * still looking for more (or paused in the background).
 */
@Composable
private fun PickerCard(model: StartCardModel) {
    // No padding of the card's own: the rows run from border to border.
    StartCard(Modifier.testTag("computer-picker-card"), padding = 0.dp, spacing = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(start = VeloTheme.medium(20.dp), end = VeloTheme.medium(20.dp), top = VeloTheme.medium(18.dp), bottom = VeloTheme.medium(6.dp)),
            verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(4.dp))) {
            PickerTitle()
            ComputersHeading(model.found.size)
        }
        model.found.forEach { ComputerRow(it, model.connect) }
        val status = when {
            model.note == FoundNote.Paused -> FoundNote.Paused.text
            model.note == FoundNote.Looking || model.searching -> FoundNote.Looking.text
            else -> null
        }
        status?.let { SearchingRow(it, spinning = model.note != FoundNote.Paused) }
    }
}

/** [ComputersHeader] with its count at the end: a narrow card cuts the heading, never the count. Read as one line. */
@Composable
private fun ComputersHeading(found: Int) {
    val c = VeloTheme.surface
    val count = computersCount(found)
    val style = VeloTheme.type.eyebrow.copy(fontWeight = FontWeight.SemiBold, lineHeight = TextUnit.Unspecified)
    Row(Modifier.fillMaxWidth().testTag("computer-picker-header")
        .clearAndSetSemantics { contentDescription = listOfNotNull(ComputersHeader, count).joinToString(" ") }
        .padding(top = VeloTheme.medium(10.dp))) {
        LineText(ComputersHeader, 12f, c.secondary, Modifier.weight(1f, fill = false), style = style)
        count?.let { LineText(" $it", 12f, c.secondary, style = style) }
    }
}

/** A hairline along the top of a row of a card, inside the row's box (the StartKit board's border-top). */
private fun Modifier.topRule(color: Color, width: Dp): Modifier = drawBehind {
    drawRect(color, size = Size(size.width, width.toPx()))
}.padding(top = width)

/**
 * A found computer: its icon, its name and address, and Connect, which saves it and connects to it. The text takes what
 * Connect leaves, so a long name or address is cut with "…" and Connect stays in the row.
 */
@Composable
private fun ComputerRow(server: DiscoveredServer, onConnect: (DiscoveredServer) -> Unit) {
    val c = VeloTheme.surface
    val label = connectLabel(server)
    val connect = { onConnect(server) }
    // 68 under its 1 dp rule; the least height is given whole, so that the two do not round up one by one.
    Row(Modifier.fillMaxWidth().testTag("computer-row-${server.name}").heightIn(min = VeloTheme.medium(69.dp)).topRule(c.divider, VeloTheme.sizes.edge)
        .padding(start = VeloTheme.medium(20.dp), end = VeloTheme.medium(16.dp)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp))) {
        Icon(painterResource(R.drawable.ic_start_computer), null, Modifier.size(VeloTheme.medium(24.dp)), tint = c.accent)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(2.dp))) {
            LineText(server.name, 16f, c.text, Modifier.fillMaxWidth().testTag("computer-name-${server.name}"), weight = FontWeight.Medium)
            LineText(server.url, 13f, c.secondary, Modifier.fillMaxWidth().testTag("computer-detail-${server.name}"))
        }
        // The clearing comes before VeloButton's own clickable, so it drops that click's action too: the button gets it back here.
        VeloButton("Connect", connect, Modifier.testTag("computer-connect-${server.name}").clearAndSetSemantics {
            contentDescription = label
            role = Role.Button
            onClick { connect(); true }
        }, kind = ButtonKind.Primary, small = true)
    }
}

/** The last row of the list: Velo is still looking for computers ([spinning]), or paused while in the background. Announced without a tap. */
@Composable
private fun SearchingRow(text: String, spinning: Boolean) {
    val c = VeloTheme.surface
    Row(Modifier.fillMaxWidth().testTag("computer-searching").heightIn(min = VeloTheme.medium(53.dp)).topRule(c.divider, VeloTheme.sizes.edge)
        .padding(horizontal = VeloTheme.medium(20.dp)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(10.dp))) {
        if (spinning) Spinner(VeloTheme.medium(16.dp), c.secondary, stroke = 3.5f / 28f)
        LineText(text, 14f, c.secondary, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** Where "Get Rex for Mac" sends the user: Rex's installation guide. */
const val RexInstallationUrl = "https://www.superlogical.com/rex/docs/installation"

/** What the user can do when a search finds no computer: set up Rex, or have a computer shared. */
private val NothingFoundSteps = listOf(
    "Install Rex on your computer and log it in to the same tailnet.",
    "Or ask the owner to share their computer with you in Tailscale.",
)

/**
 * A search ended and found nothing: say so, announced without a tap, say what to do about it, and offer to get Rex
 * ([StartCardModel.getRex]) or to search again (not while one is under way). When no browser opens the guide, its
 * address shows as selectable text instead.
 */
@Composable
private fun NoComputerCard(model: StartCardModel) {
    val c = VeloTheme.surface
    var browserUnavailable by remember { mutableStateOf(false) }
    StartCard(Modifier.testTag("no-computer-card"), spacing = VeloTheme.medium(14.dp)) {
        PickerTitle()
        Text(FoundNote.NothingFound.text!!, Modifier.fillMaxWidth().testTag("no-computer-message").semantics { liveRegion = LiveRegionMode.Polite },
            style = paragraph(16f), color = c.text)
        Text(NothingFoundSteps[0], Modifier.fillMaxWidth().testTag("no-computer-hint"), style = paragraph(15f), color = VeloTheme.colors.textOnRaised)
        VeloButton("Get Rex for Mac", { browserUnavailable = !model.getRex() }, Modifier.fillMaxWidth().testTag("get-rex-button"),
            kind = ButtonKind.Primary, icon = painterResource(R.drawable.ic_open_external), iconAtEnd = true)
        if (browserUnavailable) SelectionContainer {
            Text("Open in a browser: $RexInstallationUrl", style = paragraph(15f), color = VeloTheme.colors.textOnRaised)
        }
        Text(NothingFoundSteps[1], Modifier.fillMaxWidth().testTag("share-hint"), style = paragraph(14f), color = c.secondary)
        VeloButton("Search again", model.searchAgain, Modifier.fillMaxWidth().testTag("search-again-button"), enabled = !model.searching)
    }
}

/** Step 3: the computer picked takes a while to open. A bar that moves on, and Cancel, which forgets the computer again. */
@Composable
private fun OpeningCard(name: String, progress: Float, cancel: () -> Unit) {
    val c = VeloTheme.surface
    val bar = rounded(VeloTheme.medium(2.dp))
    StartCard(Modifier.testTag("connecting-card")) {
        CardHeading("Opening $name", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, titleTag = "connecting-title") {
            Icon(painterResource(R.drawable.ic_start_computer), null, Modifier.size(VeloTheme.medium(24.dp)), tint = c.accent)
        }
        // The bar shows time, not a measure ([openingProgress]): TalkBack hears that something is under way.
        Box(Modifier.fillMaxWidth().testTag("connecting-progress").progressSemantics().height(VeloTheme.medium(4.dp)).clip(bar).background(c.divider)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(bar).background(c.accent))
        }
        Text("Sessions and tabs as on the computer. Your tabs appear here as soon as they load.",
            Modifier.fillMaxWidth().testTag("connecting-body"), style = paragraph(16f), color = VeloTheme.colors.textOnRaised)
        VeloButton("Cancel", cancel, Modifier.fillMaxWidth().testTag("connecting-cancel"))
    }
}

/** "Add by QR code or address" and "Settings", at the bottom of the screen; [settingsBadge] puts the update dot on Settings. */
@Composable
private fun StartFooter(onAddByQr: () -> Unit, onSettings: () -> Unit, settingsBadge: Boolean, modifier: Modifier, horizontal: Dp) {
    val c = VeloTheme.surface
    Row(modifier.testTag("start-footer").padding(start = horizontal, end = horizontal, top = VeloTheme.medium(8.dp), bottom = VeloTheme.medium(20.dp)),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.testTag("add-by-qr-link").heightIn(min = VeloTheme.sizes.control).clip(Capsule).clickable(role = Role.Button, onClick = onAddByQr),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(8.dp))) {
            Icon(painterResource(R.drawable.ic_start_qr), null, Modifier.size(VeloTheme.medium(20.dp)), tint = c.accent)
            Text("Add by QR code or address", style = oneLine(16f), color = c.accent)
        }
        Row(Modifier.testTag("start-settings-link").heightIn(min = VeloTheme.sizes.control).clip(Capsule)
            .clickable(role = Role.Button, onClick = onSettings).semantics { if (settingsBadge) stateDescription = "Update available" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(8.dp))) {
            Text("Settings", style = oneLine(16f), color = VeloTheme.colors.textOnRaised)
            if (settingsBadge) AttentionBadge(c.accent, Modifier.testTag("start-settings-update-dot").size(VeloTheme.sizes.dot))
        }
    }
}

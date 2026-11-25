package io.github.yhzion.velo

import java.io.InputStream

// The third-party software Velo ships, as Settings > About > Open source licenses lists it. The
// texts are the notices the build packages (app/build.gradle.kts, copyNotices): LICENSE and
// NOTICE under assets/notices/, the licenses/ tree under assets/notices/third-party/. Nothing
// is fetched; OpenSourceLicensesTest checks every path against the source tree.

/** The groups of the list, in the order it shows them. */
enum class LicenseGroup(val title: String) {
    Velo("Velo"),
    TerminalCore("Terminal core"),
    Networking("Networking"),
    Android("Android"),
    Fonts("Fonts"),
    Artwork("Artwork"),
}

/**
 * One license or notice text in the APK: what it is ([title]) and its path under assets/. With a
 * [section], the page shows only the section of the notice under that heading ([noticeSection]).
 */
data class LicenseText(val title: String, val asset: String, val section: String? = null)

/**
 * One entry of the list. [license] is an SPDX-style expression or a short description. A
 * collection such as the Go modules has [parts] instead of texts of its own: its page lists them.
 */
data class LicenseComponent(
    val id: String,
    val name: String,
    val version: String,
    val license: String,
    val group: LicenseGroup,
    val url: String?,
    val texts: List<LicenseText>,
    val parts: List<LicenseComponent> = emptyList(),
)

/** A text from the licenses/ tree, by its path there. */
private fun third(path: String, title: String = path.substringAfterLast('/')) = LicenseText(title, "notices/third-party/$path")

/** A Go module linked into libtailscale.so, with its license and, where the module has one, its patent grant. */
private fun goModule(path: String, version: String, license: String, patents: Boolean = false) = LicenseComponent(
    id = "go:$path", name = path, version = version, license = license, group = LicenseGroup.Networking,
    url = "https://pkg.go.dev/$path",
    texts = listOfNotNull(
        third("go/$path/$version-LICENSE.txt", "License"),
        if (patents) third("go/$path/$version-PATENTS.txt", "Patents") else null,
    ),
)

private const val OFL = "OFL-1.1"

object OpenSourceLicenses {
    /** The Go modules `go version -m` reports for the ARM64 libtailscale.so, except Tailscale's own two. */
    private val goModules = listOf(
        goModule("filippo.io/edwards25519", "v1.2.0", "BSD-3-Clause"),
        goModule("github.com/creachadair/msync", "v0.10.1", "BSD-3-Clause"),
        goModule("github.com/fxamacker/cbor/v2", "v2.9.3", "MIT"),
        goModule("github.com/gaissmai/bart", "v0.29.0", "MIT"),
        goModule("github.com/go-json-experiment/json", "v0.0.0-20260820222146-c27c302e5fc3", "BSD-3-Clause"),
        goModule("github.com/golang/groupcache", "v0.0.0-20241129210726-2c02b8208cf8", "Apache-2.0"),
        goModule("github.com/google/btree", "v1.1.3", "Apache-2.0"),
        goModule("github.com/hdevalence/ed25519consensus", "v0.2.0", "BSD-3-Clause"),
        goModule("github.com/huin/goupnp", "v1.3.0", "BSD-2-Clause"),
        goModule("github.com/klauspost/compress", "v1.20.0", "BSD-3-Clause AND Apache-2.0 AND MIT"),
        goModule("github.com/mdlayher/socket", "v0.7.0", "MIT"),
        goModule("github.com/pires/go-proxyproto", "v0.15.0", "Apache-2.0"),
        goModule("github.com/tailscale/peercred", "v0.0.0-20250107143737-35a0c7bd7edc", "BSD-3-Clause"),
        goModule("github.com/tailscale/wireguard-go", "v0.0.0-20260928213032-417aef361226", "MIT"),
        goModule("github.com/x448/float16", "v0.8.4", "MIT"),
        goModule("go4.org/mem", "v0.0.0-20240501181205-ae6ca9944745", "Apache-2.0"),
        goModule("go4.org/netipx", "v0.0.0-20260823151212-3075585bcbeb", "BSD-3-Clause"),
        goModule("golang.org/x/crypto", "v0.57.0", "BSD-3-Clause", patents = true),
        goModule("golang.org/x/exp", "v0.0.0-20260908205506-85c1c2202aba", "BSD-3-Clause", patents = true),
        goModule("golang.org/x/net", "v0.59.0", "BSD-3-Clause", patents = true),
        goModule("golang.org/x/oauth2", "v0.37.0", "BSD-3-Clause"),
        goModule("golang.org/x/sync", "v0.23.0", "BSD-3-Clause", patents = true),
        goModule("golang.org/x/sys", "v0.48.0", "BSD-3-Clause", patents = true),
        goModule("golang.org/x/term", "v0.46.0", "BSD-3-Clause", patents = true),
        goModule("golang.org/x/text", "v0.42.0", "BSD-3-Clause", patents = true),
        goModule("golang.org/x/time", "v0.16.0", "BSD-3-Clause", patents = true),
        goModule("gvisor.dev/gvisor", "v0.0.0-20260915211658-a6f909f08a72", "Apache-2.0"),
    )

    val all: List<LicenseComponent> = listOf(
        LicenseComponent("velo", "Velo", "", "MIT", LicenseGroup.Velo, "https://github.com/yhzion/velo-android",
            listOf(LicenseText("LICENSE", "notices/LICENSE"))),
        LicenseComponent("notice", "Third-party notices", "", "", LicenseGroup.Velo, "https://github.com/yhzion/velo-android/blob/main/NOTICE",
            listOf(LicenseText("NOTICE", "notices/NOTICE"))),

        LicenseComponent("ghostty", "Ghostty (libghostty-vt)", "1.0.1", "MIT", LicenseGroup.TerminalCore, "https://github.com/ghostty-org/ghostty",
            listOf(third("native/ghostty-MIT.txt"))),
        LicenseComponent("simdutf", "simdutf", "9.0.0", "Apache-2.0 OR MIT", LicenseGroup.TerminalCore, "https://github.com/simdutf/simdutf",
            listOf(third("native/simdutf-Apache-2.0.txt"), third("native/simdutf-MIT.txt"))),
        LicenseComponent("highway", "Google Highway", "1.2.0", "Apache-2.0 OR BSD-3-Clause", LicenseGroup.TerminalCore, "https://github.com/google/highway",
            listOf(third("native/highway-Apache-2.0.txt"), third("native/highway-BSD-3-Clause.txt"))),
        LicenseComponent("wuffs", "Wuffs", "0.4.0-alpha.10", "Apache-2.0 OR MIT", LicenseGroup.TerminalCore, "https://github.com/google/wuffs",
            listOf(third("native/wuffs-Apache-2.0.txt"), third("native/wuffs-MIT.txt"))),
        LicenseComponent("uucode", "uucode and Unicode data", "0.2.0", "MIT AND Unicode-3.0", LicenseGroup.TerminalCore, "https://github.com/jacobsandlund/uucode",
            listOf(third("native/uucode-MIT.txt"), third("native/unicode-data.txt"))),
        LicenseComponent("utf8-dfa", "UTF-8 decoder (Bjoern Hoehrmann)", "", "MIT", LicenseGroup.TerminalCore, "https://bjoern.hoehrmann.de/utf-8/decoder/dfa/",
            listOf(third("native/utf8-dfa-MIT.txt"))),
        LicenseComponent("xorg-rgb", "X.Org rgb color table", "", "MIT-style", LicenseGroup.TerminalCore, "https://gitlab.freedesktop.org/xorg/app/rgb",
            listOf(third("native/xorg-rgb-COPYING.txt"))),
        LicenseComponent("zig", "Zig toolchain", "0.17.1", "MIT", LicenseGroup.TerminalCore, "https://ziglang.org",
            listOf(third("native/zig-MIT.txt"))),
        LicenseComponent("android-ndk", "Android NDK", "30.0.16248370", "Apache-2.0 and others", LicenseGroup.TerminalCore, "https://developer.android.com/ndk",
            listOf(third("native/NOTICE", "NDK notices"), third("native/NOTICE.toolchain", "NDK toolchain notices"))),

        LicenseComponent("tailscale", "Tailscale", "1.104.1", "BSD-3-Clause", LicenseGroup.Networking, "https://github.com/tailscale/tailscale",
            listOf(third("go/tailscale.com/v1.104.1-LICENSE.txt", "License"), third("go/tailscale.com/v1.104.1-PATENTS.txt", "Patents"))),
        LicenseComponent("libtailscale", "libtailscale", "dacde75057ff", "BSD-3-Clause", LicenseGroup.Networking, "https://github.com/tailscale/libtailscale",
            listOf(third("go/github.com/tailscale/libtailscale/v0.0.0-20261105200826-dacde75057ff-LICENSE.txt", "License"))),
        LicenseComponent("go", "Go runtime and standard library", "1.27.1", "BSD-3-Clause", LicenseGroup.Networking, "https://go.dev",
            listOf(
                third("go/LICENSE", "License"), third("go/PATENTS", "Patents"),
                third("go/std-vendor/golang.org/x/crypto/LICENSE", "Vendored golang.org/x/crypto: license"),
                third("go/std-vendor/golang.org/x/crypto/PATENTS", "Vendored golang.org/x/crypto: patents"),
                third("go/std-vendor/golang.org/x/net/LICENSE", "Vendored golang.org/x/net: license"),
                third("go/std-vendor/golang.org/x/net/PATENTS", "Vendored golang.org/x/net: patents"),
                third("go/std-vendor/golang.org/x/text/LICENSE", "Vendored golang.org/x/text: license"),
                third("go/std-vendor/golang.org/x/text/PATENTS", "Vendored golang.org/x/text: patents"),
            )),
        LicenseComponent("go-modules", "Go modules", "${goModules.size} modules", "BSD-3-Clause, MIT, Apache-2.0 and others",
            LicenseGroup.Networking, null, emptyList(), parts = goModules),

        LicenseComponent("androidx", "AndroidX and Jetpack Compose", "Compose 1.13.0 and others", "Apache-2.0", LicenseGroup.Android,
            "https://developer.android.com/jetpack/androidx",
            listOf(third("android/androidx-LICENSE.txt", "License"), third("android/RELEASE-RUNTIME.md", "Release runtime inventory"))),
        LicenseComponent("material-icons", "Compose Material Icons", "1.7.8", "Apache-2.0", LicenseGroup.Android,
            "https://developer.android.com/jetpack/androidx/releases/compose-material",
            listOf(third("android/material-icons-LICENSE.txt"))),
        LicenseComponent("camerax", "CameraX with libyuv", "1.6.2", "Apache-2.0 AND BSD-3-Clause", LicenseGroup.Android,
            "https://developer.android.com/jetpack/androidx/releases/camera",
            listOf(third("android/androidx-LICENSE.txt", "CameraX license"), third("android/libyuv-LICENSE.txt", "libyuv license"),
                third("android/libyuv-PATENTS.txt", "libyuv patents"))),
        LicenseComponent("kotlin-stdlib", "Kotlin standard library", "2.4.21", "Apache-2.0", LicenseGroup.Android, "https://kotlinlang.org",
            listOf(third("android/kotlin-stdlib-2.4.21-LICENSE.txt"))),
        LicenseComponent("kotlinx-coroutines", "kotlinx.coroutines", "1.11.0", "Apache-2.0", LicenseGroup.Android,
            "https://github.com/Kotlin/kotlinx.coroutines", listOf(third("android/kotlinx-coroutines-1.11.0-LICENSE.txt"))),
        LicenseComponent("kotlinx-serialization", "kotlinx.serialization", "1.13.0", "Apache-2.0", LicenseGroup.Android,
            "https://github.com/Kotlin/kotlinx.serialization", listOf(third("android/kotlinx-serialization-1.13.0-LICENSE.txt"))),
        LicenseComponent("atomicfu", "kotlinx.atomicfu", "0.28.0", "Apache-2.0", LicenseGroup.Android, "https://github.com/Kotlin/kotlinx-atomicfu",
            listOf(third("android/atomicfu-0.28.0-COPYRIGHT.txt", "Copyright"), third("android/Apache-2.0.txt", "License"))),
        LicenseComponent("jetbrains-annotations", "JetBrains Java Annotations", "23.0.0", "Apache-2.0", LicenseGroup.Android,
            "https://github.com/JetBrains/java-annotations", listOf(third("android/jetbrains-annotations-23.0.0-LICENSE.txt"))),
        LicenseComponent("zxing", "ZXing Core", "3.5.4", "Apache-2.0", LicenseGroup.Android, "https://github.com/zxing/zxing",
            listOf(third("android/zxing-core-3.5.4-LICENSE.txt", "License"), third("android/zxing-core-3.5.4-NOTICE.txt", "Notice"))),
        LicenseComponent("dagger", "Dagger", "2.59", "Apache-2.0", LicenseGroup.Android, "https://dagger.dev",
            listOf(third("android/dagger-2.59-LICENSE.txt"))),
        LicenseComponent("jakarta-inject", "Jakarta Dependency Injection", "2.0.1", "Apache-2.0", LicenseGroup.Android,
            "https://github.com/jakartaee/inject",
            listOf(third("android/jakarta-inject-LICENSE.txt", "License"), third("android/jakarta-inject-NOTICE.txt", "Notice"))),
        LicenseComponent("guava", "Guava and annotation libraries", "33.3.1-android and others", "Apache-2.0", LicenseGroup.Android,
            "https://github.com/google/guava", listOf(third("android/Apache-2.0.txt", "License"))),
        LicenseComponent("checker-qual", "Checker Framework qualifiers", "3.43.0", "MIT", LicenseGroup.Android, "https://checkerframework.org",
            listOf(third("android/checker-qual-3.43.0-LICENSE.txt"))),

        LicenseComponent("jetbrains-mono", "JetBrains Mono", "2.304", OFL, LicenseGroup.Fonts, "https://github.com/JetBrains/JetBrainsMono",
            listOf(third("fonts/JetBrainsMono-OFL.txt"))),
        LicenseComponent("fira-code", "Fira Code", "6.2", OFL, LicenseGroup.Fonts, "https://github.com/tonsky/FiraCode",
            listOf(third("fonts/FiraCode-OFL.txt"))),
        LicenseComponent("cascadia-code", "Cascadia Code", "2407.24", OFL, LicenseGroup.Fonts, "https://github.com/microsoft/cascadia-code",
            listOf(third("fonts/CascadiaCode-OFL.txt"))),
        LicenseComponent("d2coding", "D2Coding Ligature", "1.4.0", OFL, LicenseGroup.Fonts, "https://github.com/naver/d2-coding-font",
            listOf(third("fonts/D2Coding-OFL.txt", "D2Coding"), third("fonts/Nanum-OFL.txt", "Nanum Barun Gothic"))),
        LicenseComponent("noto-emoji", "Noto Emoji COLRv1", "2.051", OFL, LicenseGroup.Fonts, "https://github.com/googlefonts/noto-emoji",
            listOf(third("fonts/Noto-LICENSE.txt"))),
        LicenseComponent("powerline", "Powerline symbols", "1.000", "MIT", LicenseGroup.Fonts, "https://github.com/powerline/powerline",
            listOf(third("fonts/Powerline-MIT.txt"))),

        // The tab tiles' logos: their licenses and the trademark note are the NOTICE's section on them.
        LicenseComponent("simple-icons", "Program logos (Simple Icons)", "16.38.1", "CC0-1.0, CC-BY-3.0, CC-BY-SA-3.0", LicenseGroup.Artwork,
            "https://simpleicons.org", listOf(LicenseText("Program logos", "notices/NOTICE", section = "Program logos"))),
    )
}

/** The components of [components] by group, in [LicenseGroup] order; a group without any is left out. */
fun licenseGroups(components: List<LicenseComponent>): List<Pair<LicenseGroup, List<LicenseComponent>>> =
    LicenseGroup.entries.mapNotNull { group -> components.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it } }

/** The component or part with [id]. */
fun findLicense(id: String): LicenseComponent? =
    OpenSourceLicenses.all.firstNotNullOfOrNull { c -> if (c.id == id) c else c.parts.firstOrNull { it.id == id } }

/** The component whose [LicenseComponent.parts] hold [id]; null for a top-level component. */
fun licenseParent(id: String): LicenseComponent? = OpenSourceLicenses.all.firstOrNull { c -> c.parts.any { it.id == id } }

/** The line under a component's name: its version and its license, whichever it has. */
fun licenseSubtitle(c: LicenseComponent): String = listOf(c.version, c.license).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * The section of a notice [text] under the heading that starts with [heading]: the heading, its
 * underline of dashes and its lines up to the next heading, without the blank lines before it.
 * Null when the notice has no such heading.
 */
fun noticeSection(text: String, heading: String): String? {
    val lines = text.replace("\r\n", "\n").split('\n')
    fun headingAt(i: Int) = i + 1 < lines.size && lines[i].isNotBlank() && lines[i + 1].matches(Regex("-{3,}"))
    val start = lines.indices.firstOrNull { headingAt(it) && lines[it].startsWith(heading) } ?: return null
    val end = (start + 2 until lines.size).firstOrNull { headingAt(it) } ?: lines.size
    return lines.subList(start, end).dropLastWhile { it.isBlank() }.joinToString("\n")
}

/** Where the build takes the asset at [asset] from, relative to the repository root. */
fun noticeSource(asset: String): String {
    val rest = asset.removePrefix("notices/")
    return if (rest.startsWith("third-party/")) "licenses/" + rest.removePrefix("third-party/") else rest
}

/** The text of [asset], read as UTF-8 through [open] (the app passes AssetManager.open). */
fun readNotice(asset: String, open: (String) -> InputStream): String = open(asset).use { it.readBytes().toString(Charsets.UTF_8) }

/**
 * [text] in blocks of at most [maxChars], split at line ends, so a long notice (the NDK's is
 * hundreds of kilobytes) lays out a screen at a time. Joined with newlines they give [text] back.
 * A line longer than a block is a block of its own.
 */
/**
 * [text] with its hard-wrapped prose joined into one line per paragraph, so that the screen wraps it at
 * its own width instead of breaking an 80-column line again in the middle. A line runs on into the next
 * when both are text, the line is long enough to have been wrapped (40 characters or more) and does not
 * end with a colon, and the next one does not start a list item and continues the paragraph's indent: the
 * same indent as the line before it, or deeper than the list item the paragraph started with (the Apache
 * License hangs an item's text under its number). Everything else, blank lines, headings, lists and short
 * "Name: value" lines, keeps its break.
 */
fun reflowLicense(text: String): String {
    val out = mutableListOf<String>()
    var lastLength = 0
    var lastIndent = 0
    var itemIndent = -1 // The indent of the list item the current output line starts with, else -1.
    for (line in text.replace("\r\n", "\n").split('\n')) {
        val previous = out.lastOrNull()
        val indent = indentOf(line)
        val continues = indent == lastIndent || (itemIndent >= 0 && indent > itemIndent)
        val runsOn = previous != null && previous.isNotBlank() && line.isNotBlank() && continues &&
            lastLength >= 40 && !previous.trimEnd().endsWith(':') && !startsListItem(line)
        if (runsOn) {
            out[out.lastIndex] = previous!!.trimEnd() + " " + line.trim()
        } else {
            out += line
            itemIndent = if (startsListItem(line)) indent else -1
        }
        lastLength = line.trim().length
        lastIndent = indent
    }
    return out.joinToString("\n")
}

private fun indentOf(line: String) = line.length - line.trimStart().length

private val listItem = Regex("""^([-*•]|\(?[0-9]{1,3}[.)]|\([a-z]{1,3}\))\s""")

private fun startsListItem(line: String) = listItem.containsMatchIn(line.trimStart())

fun licenseBlocks(text: String, maxChars: Int = 4000): List<String> {
    val blocks = mutableListOf<String>()
    val block = StringBuilder()
    var started = false
    for (line in text.split('\n')) {
        if (started && block.length + 1 + line.length > maxChars) {
            blocks += block.toString()
            block.clear()
            started = false
        }
        if (started) block.append('\n')
        block.append(line)
        started = true
    }
    blocks += block.toString()
    return blocks
}

// Build-time gates for Constitution Principles I, II and VI. Applied from app/build.gradle.kts.
//
//   verifyNoForbiddenDependencies  fails when the release runtime classpath contains Google Play
//                                  Services, Firebase or other proprietary SDKs (Principle II).
//   verifyManifestPermissions      fails when the merged release manifest requests anything outside
//                                  the allow-list, and when INTERNET is not contained by the network
//                                  security configuration (Principles I and VI).
//   verifyNoPrebuiltBinaries       fails when the sources contain binaries, so everything native is
//                                  built from source (Principle II, F-Droid).
//   verifyWhisperPin               fails when the engine submodule is not at the pinned commit.
//   verifyReleaseMetadata          fails when the F-Droid recipe copy, the store metadata, the version or the
//                                  author data disagree with the build, or signing material is tracked
//                                  (Principle II, specs/004-fdroid-release).

import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

val appId = "app.meetingrecorder"

val allowedPermissions = setOf(
    // Phase 1: the recording itself.
    "android.permission.RECORD_AUDIO",
    // Phase 1: photos during a recording.
    "android.permission.CAMERA",
    // Phase 1: the recording indicator notification.
    "android.permission.POST_NOTIFICATIONS",
    // Phase 1: the recording service runs in the foreground.
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    // Phase 2: ONLY the explicit, user-initiated model download (FR-003, FR-036; Principles I and VI).
    // Contained by the network rule below.
    "android.permission.INTERNET",
    // Phase 2: keep the CPU running while a transcription or a download runs with the screen off (FR-004, FR-015).
    "android.permission.WAKE_LOCK",
    // Phase 2: foreground service type of the model download, and of transcription before Android 15.
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    // Phase 2: foreground service type of transcription on Android 15 and newer.
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
    // Added by androidx.core to protect dynamically registered receivers; scoped to this app.
    "$appId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
)

// The only hosts the app may talk to (the model download), research R-11.
val allowedNetworkDomains = setOf("huggingface.co", "hf.co")

val forbiddenGroupPrefixes = listOf(
    "com.google.android.gms",
    "com.google.firebase",
    "com.google.android.play",
    "com.google.mlkit",
    "com.google.android.datatransport",
    "com.crashlytics",
    "io.fabric",
    "com.facebook",
    "com.adjust",
    "com.appsflyer",
    "com.mixpanel",
    "com.amplitude",
    "io.sentry",
    "com.bugsnag",
)

tasks.register("verifyNoForbiddenDependencies") {
    group = "verification"
    description = "Fails when the release runtime classpath contains proprietary or tracking SDKs."
    doLast {
        val components = configurations.getByName("releaseRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
        val forbidden = components
            .filter { id -> forbiddenGroupPrefixes.any { id.group == it || id.group.startsWith("$it.") } }
            .map { "${it.group}:${it.module}:${it.version}" }
            .sorted()
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "Forbidden dependencies on the release runtime classpath (Principle II):\n  " +
                    forbidden.joinToString("\n  "),
            )
        }
        logger.lifecycle("verifyNoForbiddenDependencies: ${components.size} components checked, none forbidden")
    }
}

tasks.register("verifyManifestPermissions") {
    group = "verification"
    description = "Fails when the merged release manifest requests permissions outside the allow-list."
    dependsOn("processReleaseManifest")
    val merged = layout.buildDirectory.file("intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml")
    doLast {
        val file = merged.get().asFile
        if (!file.isFile) throw GradleException("Merged release manifest not found: $file")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val requested = listOf("uses-permission", "uses-permission-sdk-23")
            .flatMap { tag ->
                val nodes = document.getElementsByTagName(tag)
                (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(androidNs, "name") }
            }
            .toSet()
        val unexpected = (requested - allowedPermissions).sorted()
        val missing = (allowedPermissions - requested).sorted()
        if (unexpected.isNotEmpty() || missing.isNotEmpty()) {
            throw GradleException(
                "Merged release manifest permissions differ from the allow-list (Principles I and VI).\n" +
                    "  unexpected: $unexpected\n  missing: $missing",
            )
        }
        if ("android.permission.INTERNET" in requested) {
            verifyNetworkRule(document, androidNs)
        }
        logger.lifecycle("verifyManifestPermissions: ${requested.size} permissions, all allowed; INTERNET is contained by the network security config")
    }
}

// The network rule (research R-11): INTERNET is acceptable only together with a configuration that
// forbids cleartext and trusts nothing but the two model hosts.
fun verifyNetworkRule(document: org.w3c.dom.Document, androidNs: String) {
    val problems = mutableListOf<String>()
    val application = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
    if (application.getAttributeNS(androidNs, "usesCleartextTraffic") != "false") {
        problems += "the application element must have android:usesCleartextTraffic=\"false\""
    }
    val reference = application.getAttributeNS(androidNs, "networkSecurityConfig")
    if (!reference.startsWith("@xml/")) {
        problems += "the application element must reference a network security configuration (android:networkSecurityConfig=\"@xml/...\")"
    } else {
        val name = reference.removePrefix("@xml/")
        // The release variant uses src/release when it overrides the file, src/main otherwise.
        val file = listOf("release", "main")
            .map { layout.projectDirectory.file("src/$it/res/xml/$name.xml").asFile }
            .firstOrNull { it.isFile }
        if (file == null) {
            problems += "network security configuration $reference not found in src/release or src/main"
        } else {
            val config = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            fun elements(tag: String): List<org.w3c.dom.Element> {
                val nodes = config.getElementsByTagName(tag)
                return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
            }
            if (elements("debug-overrides").isNotEmpty()) {
                problems += "the release configuration must not contain debug-overrides"
            }
            val bases = elements("base-config")
            if (bases.size != 1 || bases[0].getAttribute("cleartextTrafficPermitted") != "false") {
                problems += "exactly one base-config with cleartextTrafficPermitted=\"false\" is required"
            } else {
                val base = bases[0]
                if (base.getElementsByTagName("trust-anchors").length == 0) {
                    problems += "the base-config needs an empty trust-anchors element (without it every host is trusted)"
                }
                if (base.getElementsByTagName("certificates").length != 0) {
                    problems += "the base-config must not trust any certificates"
                }
            }
            elements("domain-config").forEach { domainConfig ->
                if (domainConfig.getAttribute("cleartextTrafficPermitted") == "true") {
                    problems += "a domain-config permits cleartext (a loopback or test entry belongs in src/debug only)"
                }
            }
            val domains = elements("domain")
            val names = domains.map { it.textContent.trim() }.toSet()
            if (names != allowedNetworkDomains) {
                problems += "domains are $names but must be exactly $allowedNetworkDomains"
            }
            domains.filter { it.getAttribute("includeSubdomains") != "true" }.forEach {
                problems += "domain ${it.textContent.trim()} must have includeSubdomains=\"true\""
            }
        }
    }
    if (problems.isNotEmpty()) {
        throw GradleException(
            "INTERNET is requested but not contained (Principles I and VI, research R-11):\n  " +
                problems.joinToString("\n  "),
        )
    }
}

// Mirrors the F-Droid `rm:` list of research R-16: directories of the engine that are never built.
val skippedEngineDirectories = setOf("examples", "bindings", "models", "samples", "tests", "ci")
val binaryExtensions = setOf("so", "a", "jar", "aar", "dll", "exe", "dylib", "class", "apk", "bin", "onnx", "mlmodelc")
val engineRelativeDir = "whisper/src/main/cpp/whisper.cpp"

tasks.register("verifyNoPrebuiltBinaries") {
    group = "verification"
    description = "Fails when the engine sources or the project sources contain prebuilt binaries."
    val root = rootProject.layout.projectDirectory.asFile
    doLast {
        val engine = File(root, engineRelativeDir)
        if (!File(engine, "CMakeLists.txt").isFile) {
            throw GradleException("The engine submodule is not checked out: $engine (run git submodule update --init)")
        }
        val found = mutableListOf<String>()
        listOf("app/src", "core/src", "whisper/src").forEach { dir ->
            File(root, dir).walkTopDown()
                .onEnter { directory ->
                    val relative = directory.relativeTo(engine).path
                    val insideEngine = !relative.startsWith("..")
                    !(insideEngine && relative.substringBefore(File.separatorChar) in skippedEngineDirectories)
                }
                .filter { it.isFile && it.extension.lowercase() in binaryExtensions }
                .forEach { found += it.relativeTo(root).path }
        }
        val cmake = File(root, "whisper/src/main/cpp/CMakeLists.txt").readText()
        skippedEngineDirectories.forEach { skipped ->
            if (Regex("""add_subdirectory\(\s*(whisper\.cpp/)?$skipped\b""").containsMatchIn(cmake)) {
                found += "whisper/src/main/cpp/CMakeLists.txt adds the skipped directory $skipped"
            }
        }
        if (found.isNotEmpty()) {
            throw GradleException("Prebuilt binaries in the sources (Principle II):\n  " + found.sorted().joinToString("\n  "))
        }
        logger.lifecycle("verifyNoPrebuiltBinaries: no prebuilt binaries in app, core, whisper or the engine")
    }
}

tasks.register("verifyWhisperPin") {
    group = "verification"
    description = "Fails when the engine submodule is not at the commit named in gradle/whisper.pin."
    val root = rootProject.layout.projectDirectory.asFile
    doLast {
        val pin = File(root, "gradle/whisper.pin").readLines()
            .filter { "=" in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val tag = pin["tag"] ?: throw GradleException("gradle/whisper.pin has no tag= line")
        val commit = pin["commit"] ?: throw GradleException("gradle/whisper.pin has no commit= line")
        val process = ProcessBuilder("git", "-C", File(root, engineRelativeDir).path, "rev-parse", "HEAD")
            .redirectErrorStream(true).start()
        val actual = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0 || actual != commit) {
            throw GradleException(
                "The engine is at '$actual' but gradle/whisper.pin pins $tag = $commit.\n" +
                    "Bumping the engine means changing the pin deliberately and re-running the spike's accuracy check (research R-1).",
            )
        }
        logger.lifecycle("verifyWhisperPin: engine at $tag ($commit)")
    }
}

// ---- Release metadata (Phase 4, specs/004-fdroid-release, research R-8) -------------------------------------------------
//
// verifyReleaseMetadata keeps what F-Droid reads (the recipe copy, the store metadata, the version, the author data)
// consistent with the build, and keeps signing material and the author's private commit address out of the repository.
// Each later check of the phase adds its findings to the list in the task below.

// The only author address that is published (README, recipe, About screen documentation).
val publishedAuthorAddress = "vve-f-droid@gmx.net"

// Runs git in the project root; null when git or the repository is not available.
fun gitText(root: File, vararg args: String): String? = try {
    val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().readText()
    if (process.waitFor() == 0) text else null
} catch (e: java.io.IOException) {
    null
}

// A tracked file is scanned as text when it is small and has no NUL byte at its start.
fun isScannableText(file: File): Boolean {
    if (!file.isFile || file.length() > 2_000_000L) return false
    val head = file.inputStream().use { it.readNBytes(8000) }
    return head.none { it == 0.toByte() }
}

// The build files that may never contain signing configuration (never this gate's own source, the specs, the
// documentation or the engine submodule: those talk about signing and would match their own words).
val buildFilesWithoutSigning = listOf(
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
    "app/build.gradle.kts", "core/build.gradle.kts", "whisper/build.gradle.kts",
)
val signingWords = Regex("""storePassword|keyPassword|keyAlias|\bsigningConfigs?\b""")
val keystoreFileName = Regex("""(^|/)[^/]+\.(jks|keystore)$|(^|/)keystore\.properties$""")

// ---- Check 1: the reviewed copy of the F-Droid recipe agrees with the build (FR-015, FR-016, research R-2) ----------------

val recipeCopyPath = "specs/004-fdroid-release/contracts/$appId.yml"
val engineDirectoryInRecipe = "whisper/src/main/cpp/whisper.cpp"
val expectedAuthorName = "Volker von Einem"

// The value of `key:` in the recipe (first occurrence, also inside the Builds list), without quotes.
fun recipeValue(text: String, key: String): String? =
    Regex("""(?m)^[ \t]*(?:-[ \t]+)?${Regex.escape(key)}:[ \t]*(.*?)[ \t]*$""").find(text)?.groupValues?.get(1)
        ?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.takeIf { it.isNotEmpty() }

// The entries of the YAML list under `key:` ("      - value" lines directly below it).
fun recipeList(text: String, key: String): List<String> {
    val lines = text.lines()
    val start = lines.indexOfFirst { Regex("""^[ \t]*${Regex.escape(key)}:[ \t]*$""").matches(it) }
    if (start < 0) return emptyList()
    return lines.drop(start + 1).takeWhile { Regex("""^[ \t]*-[ \t]+\S.*$""").matches(it) }
        .map { it.trim().removePrefix("-").trim() }
}

fun recipeFindings(root: File): List<String> {
    val findings = mutableListOf<String>()
    val recipeFile = File(root, recipeCopyPath)
    if (!recipeFile.isFile) return listOf("The recipe copy is missing: $recipeCopyPath")
    val recipe = recipeFile.readText()
    val appBuild = File(root, "app/build.gradle.kts").readText()
    val whisperBuild = File(root, "whisper/build.gradle.kts").readText()

    fun single(source: String, regex: Regex, what: String): String? {
        val matches = regex.findAll(source).toList()
        if (matches.size != 1) {
            findings += "app/build.gradle.kts must contain $what exactly once (found ${matches.size})"
            return null
        }
        return matches.single().groupValues[1]
    }
    val buildName = single(appBuild, Regex("""versionName\s*=\s*"([^"]+)""""), "versionName")
    val buildCode = single(appBuild, Regex("""versionCode\s*=\s*(\d+)"""), "versionCode")

    fun expectEqual(field: String, actual: String?, expected: String?) {
        if (expected != null && actual != expected) {
            findings += "$recipeCopyPath: $field is '${actual ?: "missing"}' but the build says '$expected'"
        }
    }
    expectEqual("versionName", recipeValue(recipe, "versionName"), buildName)
    expectEqual("versionCode", recipeValue(recipe, "versionCode"), buildCode)
    expectEqual("CurrentVersion", recipeValue(recipe, "CurrentVersion"), buildName)
    expectEqual("CurrentVersionCode", recipeValue(recipe, "CurrentVersionCode"), buildCode)

    val commit = recipeValue(recipe, "commit")
    if (commit == null || !(commit == "TAG_COMMIT_PLACEHOLDER" || Regex("[0-9a-f]{40}").matches(commit))) {
        findings += "$recipeCopyPath: commit must be the placeholder TAG_COMMIT_PLACEHOLDER or 40 hex digits, not '${commit ?: "missing"}'"
    }

    val expectedRm = skippedEngineDirectories.map { "$engineDirectoryInRecipe/$it" }.sorted()
    val actualRm = recipeList(recipe, "rm").sorted()
    if (actualRm != expectedRm) {
        findings += "$recipeCopyPath: the rm: list must equal the directories verifyNoPrebuiltBinaries skips " +
            "(missing: ${(expectedRm - actualRm.toSet()).ifEmpty { listOf("none") }}, unexpected: ${(actualRm - expectedRm.toSet()).ifEmpty { listOf("none") }})"
    }

    val ndkVersion = Regex("""ndkVersion\s*=\s*"([^"]+)"""").find(whisperBuild)?.groupValues?.get(1)
    val cmakeVersion = Regex("""cmake\s*\{[^}]*?version\s*=\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(whisperBuild)?.groupValues?.get(1)
    val recipeNdk = recipeValue(recipe, "ndk")
    if (ndkVersion != null) {
        val major = ndkVersion.substringBefore('.')
        if (recipeNdk != "r$major") findings += "$recipeCopyPath: ndk is '${recipeNdk ?: "missing"}' but whisper/build.gradle.kts pins NDK $ndkVersion (expected r$major)"
        // The fallback of research R-3: an sdkmanager line must name the same NDK and CMake as the build.
        Regex("""ndk;([0-9.]+)""").findAll(recipe).forEach {
            if (it.groupValues[1] != ndkVersion) findings += "$recipeCopyPath: sdkmanager installs NDK ${it.groupValues[1]} but the build pins $ndkVersion"
        }
    }
    if (cmakeVersion != null) {
        Regex("""cmake;([0-9.]+)""").findAll(recipe).forEach {
            if (it.groupValues[1] != cmakeVersion) findings += "$recipeCopyPath: sdkmanager installs CMake ${it.groupValues[1]} but the build pins $cmakeVersion"
        }
    }

    if (recipeValue(recipe, "submodules") != "true") findings += "$recipeCopyPath: submodules must be true (the speech engine is a git submodule)"
    expectEqual("License", recipeValue(recipe, "License"), "GPL-3.0-or-later")
    expectEqual("AuthorName", recipeValue(recipe, "AuthorName"), expectedAuthorName)
    expectEqual("AuthorEmail", recipeValue(recipe, "AuthorEmail"), publishedAuthorAddress)

    // An anti-feature is allowed only when the decision record (R-16) names the same outcome.
    recipeValue(recipe, "AntiFeatures")?.let { declared ->
        val decision = File(root, "specs/004-fdroid-release/r16-decision.md")
        val recorded = decision.isFile && declared.split(',').map { it.trim() }.all { name ->
            Regex("""anti-feature:\s*$name\b""").containsMatchIn(decision.readText())
        }
        if (!recorded) findings += "$recipeCopyPath declares AntiFeatures '$declared' but specs/004-fdroid-release/r16-decision.md does not record that outcome"
    }
    return findings
}

// ---- Check 2: the store metadata F-Droid reads from fastlane/ (FR-010 to FR-014, contracts/store-metadata.md) ------------

val storeLocales = listOf("en-US", "de-DE")
val storeDirectory = "fastlane/metadata/android"
val storeTitleLimit = 50
val storeShortLimit = 80
val storeFullLimit = 4000
val storeChangelogLimit = 500
val storeMinimumScreenshots = 4
// Facts that every full description states, in the language of the description (FR-013, FR-014).
val storeRequiredWords = mapOf(
    "en-US" to listOf("arm64-v8a", "x86_64", "huggingface.co", "unencrypted", "agreed"),
    "de-DE" to listOf("arm64-v8a", "x86_64", "huggingface.co", "unverschlüsselt", "zugestimmt"),
)

fun storeFindings(root: File, versionCode: String?): List<String> {
    val findings = mutableListOf<String>()
    fun text(locale: String, name: String): String? =
        File(root, "$storeDirectory/$locale/$name").takeIf { it.isFile }?.readText()?.trimEnd('\n', '\r')
    for (locale in storeLocales) {
        val where = "$storeDirectory/$locale"
        fun limited(name: String, limit: Int, oneLine: Boolean = false) {
            val value = text(locale, name)
            when {
                value == null -> findings += "$where/$name is missing"
                value.length > limit -> findings += "$where/$name has ${value.length} characters, the limit is $limit"
                oneLine && value.contains('\n') -> findings += "$where/$name must be one line"
                value.isBlank() -> findings += "$where/$name is empty"
            }
        }
        limited("title.txt", storeTitleLimit, oneLine = true)
        limited("short_description.txt", storeShortLimit, oneLine = true)
        limited("full_description.txt", storeFullLimit)
        if (versionCode != null) {
            limited("changelogs/$versionCode.txt", storeChangelogLimit)
            text(locale, "changelogs/$versionCode.txt")?.let {
                if (it.toByteArray().size > storeChangelogLimit) findings += "$where/changelogs/$versionCode.txt has more than $storeChangelogLimit bytes"
            }
        }
        val full = text(locale, "full_description.txt")
        if (full != null) {
            storeRequiredWords.getValue(locale).filter { !full.contains(it) }.forEach {
                findings += "$where/full_description.txt does not mention '$it' (architectures, download source, storage and consent must be stated, FR-013, FR-014)"
            }
        }
    }
    // The images are shared and live under the fallback locale.
    val images = "$storeDirectory/en-US/images"
    val icon = File(root, "$images/icon.png")
    if (!icon.isFile) {
        findings += "$images/icon.png is missing"
    } else {
        val header = icon.inputStream().use { it.readNBytes(24) }
        val isPng = header.size == 24 && header.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun int(offset: Int) = ((header[offset].toInt() and 255) shl 24) or ((header[offset + 1].toInt() and 255) shl 16) or
            ((header[offset + 2].toInt() and 255) shl 8) or (header[offset + 3].toInt() and 255)
        if (!isPng) findings += "$images/icon.png is not a PNG file"
        else if (int(16) != 512 || int(20) != 512) findings += "$images/icon.png is ${int(16)} x ${int(20)}, it must be 512 x 512"
    }
    val shots = File(root, "$images/phoneScreenshots").listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
    val numbered = shots.mapNotNull { Regex("""(\d+)\.png""").matchEntire(it)?.groupValues?.get(1)?.toInt() }.sorted()
    if (numbered.size < storeMinimumScreenshots) {
        findings += "$images/phoneScreenshots has ${numbered.size} numbered PNG screenshots, at least $storeMinimumScreenshots are required"
    } else if (numbered != (1..numbered.size).toList()) {
        findings += "$images/phoneScreenshots must be numbered 1.png, 2.png, ... without gaps (found $numbered)"
    }
    return findings
}

// ---- Author and copyright data (FR-001 to FR-003, SC-005) -------------------------------------------------------------

// Files that may contain the published author address; everything else must not repeat it.
fun mayContainAuthorAddress(path: String): Boolean =
    path == "README.md" || path == "gradle/verification-tasks.gradle.kts" || path.startsWith("specs/")

fun authorFindings(root: File, tracked: List<String>?): List<String> {
    val findings = mutableListOf<String>()
    for (res in listOf("app/src/main/res/values/strings.xml", "app/src/main/res/values-de/strings.xml")) {
        val strings = File(root, res).takeIf { it.isFile }?.readText()
        val line = strings?.let { Regex("""<string name="settings_copyright">([^<]*)</string>""").find(it)?.groupValues?.get(1) }
        when {
            line == null -> findings += "$res has no settings_copyright string (the About screen shows the author, FR-001)"
            !line.contains(expectedAuthorName) -> findings += "$res: settings_copyright '$line' does not name $expectedAuthorName"
        }
    }
    val readme = File(root, "README.md").takeIf { it.isFile }?.readText().orEmpty()
    if (!readme.contains(expectedAuthorName)) findings += "README.md does not name the author $expectedAuthorName (FR-002)"
    if (!readme.contains(publishedAuthorAddress)) findings += "README.md does not contain the author address $publishedAuthorAddress (FR-002)"
    tracked?.filter { !mayContainAuthorAddress(it) }?.map { File(root, it) }?.filter { isScannableText(it) }?.forEach { file ->
        if (file.readText().contains(publishedAuthorAddress)) {
            findings += "${file.relativeTo(root).path} contains $publishedAuthorAddress; it belongs only in README.md, the recipe copy and the specs (SC-005)"
        }
    }
    return findings
}

tasks.register("verifyReleaseMetadata") {
    group = "verification"
    description = "Fails when the recipe copy, the store metadata, the version or the author data disagree with the build."
    val root = rootProject.layout.projectDirectory.asFile
    doLast {
        val findings = mutableListOf<String>()
        val tracked = gitText(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard")?.split('\u0000')?.filter { it.isNotEmpty() }
        if (tracked == null) {
            logger.warn("verifyReleaseMetadata: git is not available here, the signing and address checks are skipped")
        }

        // Check 1 (FR-015, FR-016): the recipe copy agrees with the build.
        findings += recipeFindings(root)

        // Check 2 (FR-010 to FR-014): the store metadata exists, fits F-Droid's limits and states the required facts.
        val versionCode = Regex("""versionCode\s*=\s*(\d+)""").find(File(root, "app/build.gradle.kts").readText())?.groupValues?.get(1)
        findings += storeFindings(root, versionCode)

        // Check 5 and the author data (FR-001 to FR-003, SC-005).
        findings += authorFindings(root, tracked)

        // Check 3 (FR-007): no signing material. Keystore files anywhere; signing words only in the build files.
        if (tracked != null) {
            tracked.filter { keystoreFileName.containsMatchIn(it) }
                .forEach { findings += "Signing material is tracked: $it (F-Droid signs the app; no key belongs in the repository)" }
            buildFilesWithoutSigning.map { File(root, it) }.filter { it.isFile }.forEach { file ->
                signingWords.find(file.readText())?.let { hit ->
                    findings += "${file.relativeTo(root).path} contains '${hit.value}': the release build must not need signing configuration"
                }
            }
        }

        // Check 4 (FR-003): the author's private commit address is never published. The forbidden addresses are taken
        // from the commit history at run time, so neither this gate nor any document has to repeat them.
        if (tracked != null) {
            val history = gitText(root, "log", "--format=%ae%n%ce")
            if (history == null) {
                logger.warn("verifyReleaseMetadata: no git history here, the author address check is skipped")
            } else {
                val forbidden = history.lines().map { it.trim() }
                    .filter { it.contains('@') && it != publishedAuthorAddress && !it.contains("noreply") }
                    .toSet()
                tracked.map { File(root, it) }.filter { isScannableText(it) }.forEach { file ->
                    val text = file.readText()
                    forbidden.filter { text.contains(it) }.forEach { _ ->
                        findings += "${file.relativeTo(root).path} contains the git commit address of the author; " +
                            "only $publishedAuthorAddress may be published (FR-003)"
                    }
                }
            }
        }

        if (findings.isNotEmpty()) {
            throw GradleException("Release metadata problems (specs/004-fdroid-release):\n  " + findings.sorted().joinToString("\n  "))
        }
        logger.lifecycle("verifyReleaseMetadata: no signing material, no private author address")
    }
}

tasks.named("check") {
    dependsOn(
        "verifyNoForbiddenDependencies", "verifyManifestPermissions", "verifyNoPrebuiltBinaries", "verifyWhisperPin",
        "verifyReleaseMetadata",
    )
}

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

tasks.named("check") {
    dependsOn("verifyNoForbiddenDependencies", "verifyManifestPermissions", "verifyNoPrebuiltBinaries", "verifyWhisperPin")
}

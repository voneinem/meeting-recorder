// Build-time gates for Constitution Principles I, II and VI. Applied from app/build.gradle.kts.
//
//   verifyNoForbiddenDependencies  fails when the release runtime classpath contains Google Play
//                                  Services, Firebase or other proprietary SDKs (Principle II).
//   verifyManifestPermissions      fails when the merged release manifest requests anything outside
//                                  the allow-list, in particular INTERNET (Principles I and VI).

import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

val appId = "app.meetingrecorder"

val allowedPermissions = setOf(
    "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    // Added by androidx.core to protect dynamically registered receivers; scoped to this app.
    "$appId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
)

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
        logger.lifecycle("verifyManifestPermissions: ${requested.size} permissions, all allowed, no INTERNET")
    }
}

tasks.named("check") {
    dependsOn("verifyNoForbiddenDependencies", "verifyManifestPermissions")
}

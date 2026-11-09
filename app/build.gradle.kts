import groovy.json.JsonSlurper
import java.time.Duration
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "app.honmani.reader"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.honmani.reader"
        minSdk = 26
        targetSdk = 36
        // Play needs a higher version code for every upload: the release workflow's run number
        // (.github/workflows/release.yml); 1 for every other build.
        versionCode = System.getenv("HONMANI_VERSION_CODE")?.toInt() ?: 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // The Play upload key, only in the release workflow (from repository secrets). Every
        // other release build stays unsigned; Play re-signs uploads with the app signing key.
        System.getenv("HONMANI_UPLOAD_KEYSTORE")?.let { keystore ->
            create("upload") {
                storeFile = file(keystore)
                storePassword = System.getenv("HONMANI_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HONMANI_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("HONMANI_UPLOAD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            manifestPlaceholders["appLabel"] = "@string/app_name"
        }
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Honmani debug"
        }
        // Release code (R8, not debuggable) signed with the debug key, so test builds from CI
        // install directly and show real-world performance.
        create("internal") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".internal"
            manifestPlaceholders["appLabel"] = "Honmani internal"
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // The default pattern also drops directories starting with "_", which removes ttu's
        // _app/ (all of its JavaScript and CSS). Same list without that entry.
        @Suppress("DEPRECATION")
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // The exported database schemas, for the migration tests: MigrationTestHelper reads them as
    // assets, and Robolectric only sees the app's merged assets (not the test source set's). Debug
    // builds only, which the unit tests run against; release and internal don't carry them.
    sourceSets.getByName("debug").assets.directories += "$projectDir/schemas"

    lint {
        abortOnError = true
        // Dependency freshness is handled by Dependabot, not by failing lint.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

/**
 * Builds ッツ Ebook Reader from the submodule (needs Node.js and pnpm, versions as in ttu's CI)
 * and copies it into generated assets under `ttu/`.
 *
 * The build runs in a copy of the submodule with the patches in
 * `third_party/patches/ttu-ebook-reader` applied, so the submodule itself stays pristine. ttu's bundled web fonts (~150 MB) are left
 * out; ttu-defaults.js points ttu at the system's CJK fonts instead. The service worker is left
 * out because the app serves every file locally.
 *
 * For the app's licences screen it also writes `licenses/ttu.txt` (ttu's own licence) and
 * `licenses/ttu-libraries.txt`: the libraries that end up in ttu's bundle, found through source
 * maps of the build (which are not shipped), with their licence texts.
 */
abstract class BuildTtuTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val patches: ConfigurableFileCollection

    @get:Internal
    abstract val ttuDir: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:Input
    abstract val basePath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun build() {
        val root = ttuDir.get().asFile
        check(root.resolve("package.json").isFile) {
            "ッツ Ebook Reader sources not found in $root. Run: git submodule update --init"
        }
        val work = workDir.get().asFile

        // Fresh sources each run; installed dependencies are kept to make reinstalls fast.
        fileSystemOperations.sync {
            from(root) {
                exclude("**/node_modules/**", "apps/web/build/**", "**/.svelte-kit/**", ".git")
            }
            into(work)
            preserve { include("**/node_modules/**") }
        }

        for (patch in patches.files.sortedBy { it.name }) {
            execOperations.exec {
                workingDir = work
                // Without a ceiling, git would treat the work dir as part of this repository
                // and silently skip every path in the patch.
                environment("GIT_CEILING_DIRECTORIES", work.parentFile.absolutePath)
                commandLine("git", "apply", "--whitespace=nowarn", patch.absolutePath)
            }
        }

        val pnpm = if (System.getProperty("os.name").startsWith("Windows")) "pnpm.cmd" else "pnpm"
        try {
            execOperations.exec {
                workingDir = work
                commandLine(pnpm, "install", "--frozen-lockfile")
            }
        } catch (e: Exception) {
            throw GradleException(
                "Building ッツ Ebook Reader needs pnpm and Node.js on PATH (see README).",
                e,
            )
        }
        execOperations.exec {
            workingDir = work.resolve("apps/web")
            environment("VITE_BASE_PATH", basePath.get())
            // Hidden source maps: the bundle doesn't point to them; they only name the libraries.
            commandLine(pnpm, "build", "--sourcemap", "hidden")
        }

        val build = work.resolve("apps/web/build")
        fileSystemOperations.sync {
            from(build)
            into(outputDir.dir("ttu"))
            exclude("**/*.woff", "**/*.woff2", "service-worker.js", "**/*.map")
        }
        val licenses = outputDir.dir("licenses").get().asFile.apply { mkdirs() }
        root.resolve("LICENSE").copyTo(licenses.resolve("ttu.txt"), overwrite = true)
        licenses.resolve("ttu-libraries.txt").writeText(bundledLibraries(build, work))
    }

    /**
     * The libraries in the bundle at [build], each with its licence, from the bundle's source maps.
     * Their paths are relative to SvelteKit's intermediate output, so they are read from their
     * `node_modules/` part, which pnpm keeps at the root of [work].
     */
    private fun bundledLibraries(build: File, work: File): String {
        val packageRoot = Regex("node_modules/.*node_modules/(?:@[^/]+/)?[^/]+/|node_modules/(?:@[^/]+/)?[^/]+/")
        val roots = sortedSetOf<String>()
        build.walkTopDown().filter { it.name.endsWith(".map") }.forEach { map ->
            val sources = (JsonSlurper().parse(map) as Map<*, *>)["sources"] as? List<*> ?: return@forEach
            for (source in sources.filterIsInstance<String>()) {
                val modules = source.indexOf("node_modules/").takeIf { it >= 0 } ?: continue
                packageRoot.find(source.substring(modules))?.let { roots += it.value }
            }
        }
        check(roots.isNotEmpty()) { "No libraries found in the source maps of $build" }
        val entries = roots.map { root ->
            listOf(work, work.resolve("apps/web")).map { it.resolve(root) }.firstOrNull { it.resolve("package.json").isFile }
                ?: throw GradleException("No package.json for $root in $work")
        }.map { root ->
            val manifest = JsonSlurper().parse(root.resolve("package.json")) as Map<*, *>
            val name = manifest["name"].toString()
            val author = when (val value = manifest["author"]) {
                is Map<*, *> -> value["name"]?.toString()
                null -> null
                else -> value.toString()
            }
            val licenseFile = root.listFiles().orEmpty()
                .filter { it.isFile }
                .firstOrNull { file -> listOf("license", "licence", "copying").any { file.name.lowercase().startsWith(it) } }
            // zip.js has no licence file; its notice heads its source.
            val text = licenseFile?.readText()
                ?: root.resolve("index.js").takeIf { it.isFile }?.readText()
                    ?.let { Regex("""/\*([\s\S]*?Copyright[\s\S]*?)\*/""").find(it)?.groupValues?.get(1)?.trimIndent() }
                ?: listOfNotNull("${manifest["license"]} licence", author?.let { "Copyright: $it" }).joinToString("\n")
            Library(name, manifest["version"].toString(), manifest["license"].toString().removeSurrounding("(", ")"), text.trim())
        }.distinctBy { it.name to it.version }
        val separator = "\n\n" + "-".repeat(40) + "\n\n"
        return entries.joinToString(separator) { "${it.name} ${it.version} (${it.license})\n\n${it.text}" } + "\n"
    }

    private data class Library(val name: String, val version: String, val license: String, val text: String)
}

val buildTtu = tasks.register<BuildTtuTask>("buildTtu") {
    val ttu = rootProject.layout.projectDirectory.dir("third_party/ttu-ebook-reader")
    ttuDir.set(ttu)
    sources.from(
        fileTree(ttu) {
            exclude("**/node_modules/**", "**/build/**", "**/.svelte-kit/**", "**/.git")
        },
    )
    patches.from(
        fileTree(rootProject.layout.projectDirectory.dir("third_party/patches/ttu-ebook-reader")) {
            include("*.patch")
        },
    )
    // Must match READER_ORIGIN in ReaderWebView.kt.
    basePath.set("https://appassets.androidplatform.net")
    workDir.set(layout.buildDirectory.dir("ttu-work"))
    outputDir.set(layout.buildDirectory.dir("generated/ttu"))
}

// A unit test that hangs (a socket or a flow waiting forever) fails the build after a while and
// is the last one named in the log, instead of holding CI until it is cancelled. The whole
// suite takes about three minutes.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(6))
    testLogging { events("started", "failed") }
    // The one-off Aozora grading run (AozoraIndexGrader, scripts/aozora-index/README.md): hours
    // long, so no timeout, more heap, visible output, and never taken as up to date.
    if (System.getenv("HONMANI_GRADER_OUT") != null) {
        timeout.set(Duration.ofHours(12))
        maxHeapSize = "3g"
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildTtu, BuildTtuTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
}

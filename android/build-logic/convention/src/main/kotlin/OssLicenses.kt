import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.w3c.dom.Element

/**
 * 오픈소스 고지 목록 자동 생성(법적 검토 T-11·L-01).
 *
 * release 런타임 클래스패스의 모든 외부 라이브러리에 대해 POM(없으면 부모 POM)의 `<licenses>`를 읽어
 * `assets/open_source_licenses.json`으로 만든다. 앱의 [설정 → 오픈소스 라이선스] 화면이 이 파일을 읽는다.
 * 라이브러리를 더하거나 빼면 빌드할 때마다 목록이 저절로 바뀐다(손으로 관리하지 않음).
 */
abstract class GenerateOssLicensesTask : DefaultTask() {
    /** "group:artifact:version" 목록 */
    @get:Input
    abstract val coordinates: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val pomFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val poms = pomFiles.files.associateBy { pomKey(it) }
        val entries = coordinates.get().sorted().map { coordinate ->
            val (group, artifact, version) = coordinate.split(':')
            val pom = poms["$group:$artifact:$version"]
            val info = pom?.let { readPom(it) }
            val licenses = info?.let { licensesWithParents(it, poms) }.orEmpty()
            buildString {
                append("{\"group\":").append(quote(group))
                append(",\"artifact\":").append(quote(artifact))
                append(",\"version\":").append(quote(version))
                append(",\"name\":").append(quote(info?.name?.takeIf { it.isNotBlank() } ?: artifact))
                append(",\"url\":").append(quote(info?.url.orEmpty()))
                append(",\"licenses\":[")
                append(licenses.joinToString(",") { (name, url) -> "{\"name\":${quote(name)},\"url\":${quote(url)}}" })
                append("]}")
            }
        }
        val out = outputDir.get().asFile.resolve("open_source_licenses.json")
        out.parentFile.mkdirs()
        out.writeText(entries.joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]\n"))
    }

    private data class PomInfo(val name: String?, val url: String?, val licenses: List<Pair<String, String>>, val parent: String?)

    private fun licensesWithParents(info: PomInfo, poms: Map<String, File>): List<Pair<String, String>> {
        var current: PomInfo? = info
        repeat(MAX_PARENTS) {
            val c = current ?: return emptyList()
            if (c.licenses.isNotEmpty()) return c.licenses
            current = c.parent?.let { poms[it] }?.let { readPom(it) }
        }
        return emptyList()
    }

    private fun readPom(file: File): PomInfo? = runCatching {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement
        fun child(parent: Element, tag: String): String? = (0 until parent.childNodes.length)
            .map { parent.childNodes.item(it) }
            .firstOrNull { it is Element && it.tagName == tag }?.textContent?.trim()
        val licenses = root.getElementsByTagName("license").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }.map {
                (child(it, "name") ?: child(it, "url").orEmpty()) to
                    child(it, "url").orEmpty()
            }
        }
        val parent = root.getElementsByTagName("parent").item(0) as? Element
        PomInfo(
            name = child(root, "name"),
            url = child(root, "url"),
            licenses = licenses,
            parent = parent?.let { "${child(it, "groupId")}:${child(it, "artifactId")}:${child(it, "version")}" }
        )
    }.getOrNull()

    /** Gradle 캐시 경로 …/files-2.1/<group>/<artifact>/<version>/<hash>/<artifact>-<version>.pom → "group:artifact:version" */
    private fun pomKey(file: File): String {
        val version = file.parentFile.parentFile
        val artifact = version.parentFile
        val group = artifact.parentFile
        return "${group.name}:${artifact.name}:${version.name}"
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n', '\r', '\t' -> append(' ')
                else -> append(ch)
            }
        }
        append('"')
    }

    private companion object {
        const val MAX_PARENTS = 4
    }
}

/** app 모듈에서 부른다: 생성 작업을 등록하고 모든 변형의 assets에 붙인다. */
fun Project.registerOssLicenses() {
    // AGP가 변형별 구성을 나중에 만들므로 늦게 찾는다.
    val runtime = provider { configurations.getByName("releaseRuntimeClasspath") }
    val coordinates = runtime.map { conf ->
        conf.incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
    }
    // POM과 부모 POM(라이선스를 부모에 적는 라이브러리가 있다)을 몇 단계까지 내려받는다.
    val pomFiles = files(
        provider {
            val all = mutableSetOf<File>()
            var wanted = coordinates.get().toSet()
            val seen = mutableSetOf<String>()
            repeat(PARENT_DEPTH) {
                val next = wanted - seen
                if (next.isEmpty()) return@repeat
                seen += next
                val conf = configurations.detachedConfiguration(*next.map { dependencies.create("$it@pom") }.toTypedArray())
                conf.isTransitive = false
                val resolved = conf.incoming.artifactView { lenient(true) }.files.files
                all += resolved
                wanted = resolved.mapNotNull { parentOf(it) }.toSet()
            }
            all
        }
    )
    val task = tasks.register<GenerateOssLicensesTask>("generateOssLicenses") {
        this.coordinates.set(coordinates)
        this.pomFiles.from(pomFiles)
        outputDir.set(layout.buildDirectory.dir("generated/ossLicenses"))
    }
    extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(task, GenerateOssLicensesTask::outputDir)
    }
}

private const val PARENT_DEPTH = 4

private fun parentOf(pom: File): String? = runCatching {
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
    val parent = doc.documentElement.getElementsByTagName("parent").item(0) as? Element ?: return null
    fun text(tag: String) = parent.getElementsByTagName(tag).item(0)?.textContent?.trim()
    "${text("groupId")}:${text("artifactId")}:${text("version")}"
}.getOrNull()

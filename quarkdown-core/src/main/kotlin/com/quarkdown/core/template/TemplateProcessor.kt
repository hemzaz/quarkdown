package com.quarkdown.core.template

import com.quarkdown.core.util.normalizeLineSeparators
import gg.jte.CodeResolver
import gg.jte.ContentType
import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import kotlin.io.path.createTempDirectory

/**
 * A builder-like processor for a template engine backed by JTE (Java Template Engine) with `.jte` templates.
 *
 * Two backing modes are supported:
 *
 * - **Precompiled** (production, via [fromResourceName]) — the `.jte` template is precompiled into a `.class`
 *   file at build time by the `gg.jte.gradle` plugin and loaded by name through
 *   [TemplateEngine.createPrecompiled]. This path has no dependency on `javax.tools.JavaCompiler` at runtime
 *   and works on minimal jlink JREs that omit `jdk.compiler`.
 *
 * - **Dynamic** (test-only, via the [text] constructor) — the template source is provided as a raw string
 *   and JTE compiles it at render time using `ToolProvider.getSystemJavaCompiler()`. This path requires a
 *   full JDK on the host and must not be used from production code paths.
 *
 * Three main features:
 *
 * - **Values**: replace a placeholder in the template with a value.
 *   In JTE templates, values are referenced via `${NAME}`.
 *
 * - **Conditionals**: show or hide fragments of the template code.
 *   In JTE templates, conditionals are expressed via `@if(NAME)...@endif`.
 *   An inverted (_not_) conditional is expressed via `@if(!NAME)...@endif`.
 *
 * - **Iterables**: repeat the content in their fragment as many times as the iterable's size,
 *   while replacing the placeholder with the current item during each iteration.
 *   In JTE templates, iterables are expressed via `@for(item in NAME)${item}@endfor`.
 */
class TemplateProcessor private constructor(
    private val mode: Mode,
    private val values: MutableMap<String, Any?> = mutableMapOf(),
    private val conditionals: MutableMap<String, Boolean> = mutableMapOf(),
    private val iterables: MutableMap<String, Iterable<Any>> = mutableMapOf(),
) {
    /**
     * Backing source of the template.
     */
    private sealed interface Mode {
        /** The template text is supplied as a raw string; JTE compiles it at render time. Test-only. */
        data class Dynamic(val text: String) : Mode

        /**
         * The template was precompiled at build time. JTE loads the generated `.class` by [name]
         * through the runtime classloader without invoking the JDK compiler API.
         * [name] is the path relative to the `src/main/jte` (or `src/test/jte`) source directory,
         * e.g. `"live-preview/wrapper.html.jte"`.
         */
        data class Precompiled(val name: String) : Mode
    }

    /**
     * Creates a processor that compiles the given JTE source [text] at render time.
     *
     * The dynamic path requires the JDK compiler API (`jdk.compiler` JVM module). Production code paths
     * should use [fromResourceName] instead so that the bundled jlink JRE — which excludes `jdk.compiler`
     * — can render templates without crashing.
     *
     * @param text raw JTE source code of the template
     */
    constructor(text: String) : this(Mode.Dynamic(text))

    /**
     * Adds a reference to a placeholder in the template code.
     * @param placeholder placeholder to replace
     * @param value value to replace in change of [placeholder]
     * @return this for concatenation
     */
    fun value(
        placeholder: String,
        value: Any?,
    ) = apply { values[placeholder] = value }

    /**
     * Adds a conditional variable that shows or removes fragments of the template code.
     * @param conditional conditional name
     * @param value whether the fragment should be shown (`true`) or hidden (`false`)
     * @return this for concatenation
     */
    fun conditional(
        conditional: String,
        value: Boolean,
    ) = apply {
        conditionals[conditional] = value
    }

    /**
     * Adds both a [value] to replace the placeholder (or `null` if absent),
     * and registers it so that the template can check for `null` presence.
     * @param placeholder both placeholder to replace and name of the conditional
     * @param value value to replace in change of the placeholder, or `null` if absent
     * @return this for concatenation
     * @see value
     */
    fun optionalValue(
        placeholder: String,
        value: Any?,
    ) = value(placeholder, value)

    /**
     * Adds an iterable to replace a placeholder in the template code.
     * @param placeholder placeholder to replace
     * @param iterable iterable to replace in change of [placeholder]
     * @return this for concatenation
     */
    fun iterable(
        placeholder: String,
        iterable: Iterable<Any>,
    ) = apply { iterables[placeholder] = iterable }

    /**
     * Creates a copy of this template processor with the same injected properties, but a different
     * dynamic-text template. Only valid on processors created from the [text] constructor; throws
     * on processors created via [fromResourceName] (use [copyAsResource] instead for those).
     *
     * @param text new text of the template
     * @return a new [TemplateProcessor] with the same injections, and the new text
     */
    fun copy(
        text: String =
            (mode as? Mode.Dynamic)?.text
                ?: error("copy(text=...) is only valid on dynamic-text TemplateProcessor; use copyAsResource(name) instead."),
    ): TemplateProcessor =
        TemplateProcessor(
            Mode.Dynamic(text),
            values.toMutableMap(),
            conditionals.toMutableMap(),
            iterables.toMutableMap(),
        )

    /**
     * Creates a copy of this template processor that renders a different *precompiled* template,
     * inheriting all currently-injected values, conditionals, and iterables. Use this when one
     * template's output needs to be fed into another with shared parameters.
     *
     * @param name the resource path of the precompiled template (with or without leading `/`),
     *             e.g. `"/creator/initialcontent.qd.jte"`.
     * @param referenceClass classloader anchor; defaults to this class's classloader, which is
     *             sufficient when the precompiled template lives in the same application classpath.
     * @return a new [TemplateProcessor] with inherited injections that renders [name] when [process]
     *             is called.
     */
    fun copyAsResource(
        name: String,
        @Suppress("UNUSED_PARAMETER") referenceClass: Class<*> = TemplateProcessor::class.java,
    ): TemplateProcessor =
        TemplateProcessor(
            Mode.Precompiled(name.removePrefix("/")),
            values.toMutableMap(),
            conditionals.toMutableMap(),
            iterables.toMutableMap(),
        )

    /**
     * Builds a unified parameter map from the registered values, conditionals, and iterables,
     * suitable for passing to the JTE template engine.
     *
     * When a key exists in multiple maps, values and iterables take precedence over conditionals,
     * since templates can check values for `null` and iterables for emptiness directly,
     * making the standalone boolean redundant.
     */
    private fun buildParams(): Map<String, Any?> =
        buildMap {
            putAll(conditionals)
            putAll(this@TemplateProcessor.values)
            putAll(iterables)
        }

    /**
     * @return the original template, with all placeholders and conditionals processed into the final output
     */
    fun process(): CharSequence {
        val params = buildParams()
        val output = StringOutput()
        when (val m = mode) {
            is Mode.Dynamic -> dynamicEngine(m.text).render(DYNAMIC_TEMPLATE_NAME, params, output)
            is Mode.Precompiled -> precompiledEngine.render(m.name, params, output)
        }
        return output.toString().trimEnd()
    }

    companion object {
        private const val DYNAMIC_TEMPLATE_NAME = "template.jte"

        /**
         * Lazily-initialized precompiled [TemplateEngine] shared by all callers in this classloader.
         * The engine is immutable and thread-safe once configured, so a single instance is fine to
         * share across threads. It loads templates by name from the runtime classpath via
         * `Thread.currentThread().getContextClassLoader()`, so any module's precompiled `.jte`
         * classes (placed in `gg.jte.generated.precompiled.*` by the `gg.jte.gradle` plugin) are
         * discoverable as long as they are on the application classpath.
         */
        private val precompiledEngine: TemplateEngine by lazy {
            TemplateEngine.createPrecompiled(ContentType.Plain).apply {
                setTrimControlStructures(true)
            }
        }

        /**
         * Loads a precompiled `.jte` template from the application classpath.
         *
         * The template must have been precompiled at build time by the `gg.jte.gradle` plugin from
         * a file under `src/main/jte/` (or `src/test/jte/`). The [name] is the file path relative
         * to that source directory; a leading `/` is accepted for backwards compatibility with the
         * `Class.getResourceAsStream`-style path that older callers used.
         *
         * @param name resource path of the precompiled template, e.g. `"/live-preview/wrapper.html.jte"`
         * @param referenceClass classloader anchor; kept for source-level backwards compatibility,
         *             unused by the precompiled engine which looks up classes through the runtime
         *             classloader.
         * @return a new [TemplateProcessor] backed by the precompiled template
         */
        fun fromResourceName(
            name: String,
            @Suppress("UNUSED_PARAMETER") referenceClass: Class<*> = TemplateProcessor::class.java,
        ): TemplateProcessor =
            TemplateProcessor(
                Mode.Precompiled(name.removePrefix("/")),
            )

        private fun dynamicEngine(text: String): TemplateEngine {
            val normalizedText = text.normalizeLineSeparators().toString()
            val codeResolver =
                object : CodeResolver {
                    override fun resolve(name: String): String = normalizedText

                    override fun getLastModified(name: String): Long = 0L

                    override fun exists(name: String): Boolean = name == DYNAMIC_TEMPLATE_NAME
                }
            return TemplateEngine
                .create(codeResolver, createTempDirectory("jte"), ContentType.Plain)
                .apply { setTrimControlStructures(true) }
        }
    }
}

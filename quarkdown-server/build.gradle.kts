plugins {
    kotlin("jvm")
    id("gg.jte.gradle") version "3.2.3"
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(project(":quarkdown-core"))
    implementation(project(":quarkdown-interaction"))

    val ktorVersion = "3.4.0"

    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    // Runtime loader for JTE templates precompiled by the gg.jte.gradle plugin below.
    // Required as a direct dep so the generated `Jte*Generated.java` sources can resolve
    // `gg.jte.TemplateOutput` / `gg.jte.html.HtmlInterceptor` at compile time. Unlike the full
    // `gg.jte:jte`, `jte-runtime` does not transitively pull in any compiler API.
    implementation("gg.jte:jte-runtime:3.2.3")
}

// Precompile `.jte` templates into the JAR. Loaded at runtime via
// `TemplateEngine.createPrecompiled(ClassLoader, ContentType.Plain)` — no `javax.tools.JavaCompiler`
// needed, fixing the NPE that happened on the bundled JRE when `/live/...` was first hit.
jte {
    generate()
    contentType.set(gg.jte.ContentType.Plain)
    sourceDirectory.set(file("src/main/jte").toPath())
    targetDirectory.set(layout.buildDirectory.dir("generated-sources/jte").get().asFile.toPath())
}

sourceSets.main {
    java.srcDir(layout.buildDirectory.dir("generated-sources/jte"))
}

tasks.named("compileKotlin") {
    dependsOn("generateJte")
}

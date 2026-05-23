plugins {
    kotlin("jvm")
    application
    id("gg.jte.gradle") version "3.2.3"
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.apache.pdfbox:pdfbox:3.0.6")
    implementation(project(":quarkdown-core"))
    implementation(project(":quarkdown-html"))
    implementation(project(":quarkdown-plaintext"))
    implementation(project(":quarkdown-server"))
    implementation(project(":quarkdown-interaction"))
    implementation(project(":quarkdown-stdlib"))
    implementation(project(":quarkdown-lsp"))
    implementation(project(":quarkdown-install-layout-navigator"))
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.methvin:directory-watcher:0.19.1")

    // Runtime loader for JTE templates precompiled by the gg.jte.gradle plugin below.
    // Required as a direct dep so the generated `Jte*Generated.java` sources can resolve
    // `gg.jte.TemplateOutput` / `gg.jte.html.HtmlInterceptor` at compile time.
    implementation("gg.jte:jte-runtime:3.2.3")
}

application {
    mainClass.set("com.quarkdown.cli.QuarkdownCliKt")
}

// Writes the project version to a file in the resources directory, so it can be accessed at runtime.
val writeVersionFile by tasks.registering {
    val version = project.parent?.version ?: "unknown"
    val versionFile = "version.txt"
    val outputFile = layout.projectDirectory.file("src/main/resources/$versionFile").asFile

    doLast {
        outputFile.writeText(version.toString())
    }
}

tasks.processResources {
    dependsOn(writeVersionFile)
    dependsOn(":assembleDevLib")
}

// Precompile `.jte` templates (project creator scaffolds) into the JAR, so `quarkdown create`
// works on the bundled JRE without `jdk.compiler`.
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

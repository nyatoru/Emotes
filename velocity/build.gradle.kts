import me.modmuss50.mpp.ReleaseType

plugins {
    java
    `maven-publish`
    id("com.gradleup.shadow")
    id("me.modmuss50.mod-publish-plugin")
}

base.archivesName = "${project["archives_base_name"]}-${name}-for-MC${project["minecraft_version"]}"
version = mod_version

val compileApi = configurations.register("compileApi").get()
configurations.api.configure { extendsFrom(compileApi) }

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0")

    compileApi(project(":emotesAssets"))
    compileApi(project(":emotesAPI"))
    compileApi(project(":emotesServer")) {
        exclude(group = "org.jetbrains", module = "annotations")
        exclude(module = "gson")
        exclude(module = "slf4j-api")
        exclude(module = "fastutil")
        exclude(module = "guava")
        exclude(module = "netty-buffer")
        exclude(module = "jspecify")
        exclude(module = "error_prone_annotations")
    }

    compileApi("org.redlance.common-utils:reflect:${project["commonutils_version"]}") {
        isTransitive = false
    }
}

tasks.processResources {
    inputs.property("version", version)
    inputs.property("description", project["mod_description"])

    filesMatching("velocity-plugin.json") {
        expand("version" to version, "description" to project["mod_description"])
    }
}

tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.WARN
    configurations = listOf(compileApi)
    archiveClassifier.set("")
    mergeServiceFiles()

    // Keep velocity's own services separate but merge others
    // No relocation needed for now
}

tasks.jar {
    archiveClassifier.set("dev")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

java {
    withSourcesJar()
}

shadow {
    addShadowVariantIntoJavaComponent = false
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            artifactId = "emotesVelocity"
            from(components["java"])
            withCustomPom("emotesVelocity", "Minecraft Emotecraft Velocity plugin")
        }
    }

    repositories {
        if (shouldPublishMaven) {
            kosmxRepo(project)
        } else {
            mavenLocal()
        }
    }
}

publishMods {
    modLoaders.add("velocity")
    file.set(tasks.shadowJar.get().archiveFile)
    type = ReleaseType.of(releaseType)
    changelog = changes
    dryRun = gradle.startParameter.isDryRun

    github {
        accessToken = providers.environmentVariable("GH_TOKEN")
        parent(rootProject.tasks.named("publishGithub"))
    }

    modrinth {
        announcementTitle = "Modrinth (Velocity)"
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        projectId = providers.gradleProperty("modrinth_id")
        minecraftVersions.addAll(release_minecraft_versions)
        displayName = "Emotecraft $mod_version for Velocity"
        version = "$mod_version-velocity"
        environment = SERVER_ONLY
    }
}

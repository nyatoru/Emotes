pluginManagement {
    repositories {
        maven("https://repo.redlance.org/public")
        maven("https://maven.wagyourtail.xyz/releases")
        gradlePluginPortal()
    }
}

rootProject.name = "emotecraft"

include("emotesAPI")
include("emotesServer")
include("emotesAssets")
include("emotesMc")

// Minecraft mod
include("minecraft")

// Paper plugin
include("paper")

// Velocity proxy
include("velocity")

// Geyser ext
include("geyser")

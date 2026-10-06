plugins {
	id("net.fabricmc.fabric-loom") version "1.15.5"
	id("maven-publish")
	id("com.github.breadmoirai.github-release") version "2.2.12"
	id("me.modmuss50.mod-publish-plugin") version "2.2.1"
	id("com.modrinth.minotaur") version "2.+"
	id("elect86.gik") version "0.0.4"
}

val modVersion: String by project
val mavenGroup: String by project
group = mavenGroup

val minecraftVersion: String by project
val clothConfigVersion: String by project
val modMenuVersion: String by project

version = "$modVersion+mc$minecraftVersion"

sourceSets {
	val main = main.get()
	create("sodium06") {
		compileClasspath += main.compileClasspath
		compileClasspath += main.output
		main.runtimeClasspath += output
		tasks.jar { from(output) }
	}
}

dependencies {
	val loaderVersion: String by project
	val fabricApiVersion: String by project
	val configurateVersion: String by project
	val geantyrefVersion: String by project
	val hoconVersion: String by project
	val sodium06Version: String by project
	val starlightVersion: String by project
	val confabricateVersion: String by project
	minecraft("com.mojang:minecraft:${minecraftVersion}")
	implementation("net.fabricmc:fabric-loader:${loaderVersion}")

	implementation(include(fabricApi.module("fabric-api-base", fabricApiVersion))!!)
	implementation(include(fabricApi.module("fabric-command-api-v2", fabricApiVersion))!!)

	// we don't need the full thing but our deps pull in an outdated one
	implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

	implementation(include("org.spongepowered:configurate-core:$configurateVersion")!!)
	implementation(include("org.spongepowered:configurate-hocon:$configurateVersion")!!)
	include("io.leangen.geantyref:geantyref:$geantyrefVersion")
	include("com.typesafe:config:$hoconVersion")

	"sodium06CompileOnly"("maven.modrinth:sodium:$sodium06Version")
	compileOnly("maven.modrinth:starlight:$starlightVersion")
	compileOnly("ca.stellardrift:confabricate:$confabricateVersion")
	implementation("me.shedaniel.cloth:cloth-config-fabric:$clothConfigVersion")
	implementation("com.terraformersmc:modmenu:$modMenuVersion")
}

tasks.processResources {
	val expansions = mutableMapOf(
		"version" to project.version,
		"clothConfigVersion" to clothConfigVersion,
		"modMenuVersion" to modMenuVersion
	)
	inputs.property("expansions", expansions)
	filesMatching("fabric.mod.json") {
		expand(expansions)
	}
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
	sourceCompatibility = "25"
	targetCompatibility = "25"
}

tasks.withType<AbstractArchiveTask> {
	val archivesBaseName: String by project
	archiveBaseName.set(archivesBaseName)
}

tasks.jar {
	from("LICENSE.md")
}

publishing {
	publications {
		create("mavenJava", MavenPublication::class.java) {
			from(components["java"])
		}
	}
}

repositories {
	maven("https://maven.shedaniel.me") {
		content {
			includeGroup("me.shedaniel.cloth")
		}
	}
	maven("https://maven.terraformersmc.com/releases") {
		content {
			includeGroup("com.terraformersmc")
		}
	}
	maven("https://api.modrinth.com/maven") {
		content {
			includeGroup("maven.modrinth")
		}
	}
}

fun readChangelog(): String {
	val lines = project.file("CHANGELOG.md").readText().lineSequence().iterator()
	if (lines.next() != "### $modVersion") {
		throw GradleException("CHANGELOG.md did not start with expected version!")
	}
	return lines.asSequence().takeWhile { it.isNotBlank() }.joinToString("\n")
}

githubRelease {
	token { project.property("github.token") as String }
	owner(project.property("github.owner") as String)
	repo(project.property("github.repo") as String)
	targetCommitish { gik.head!!.id }
	releaseName("Version $modVersion for Minecraft $minecraftVersion")
	releaseAssets(tasks.jar)
	body(readChangelog())
}

publishMods {
	file.set(tasks.jar.flatMap { it.archiveFile })
	displayName.set("Bobby $modVersion for Minecraft $minecraftVersion")
	changelog.set(readChangelog())
	type.set(STABLE)
	modLoaders.add("fabric")
	curseforge {
		projectId.set(providers.gradleProperty("curseforge.id"))
		accessToken.set(providers.gradleProperty("curseforge.token"))
		minecraftVersions.add(minecraftVersion)
		javaVersions.add(JavaVersion.VERSION_25)
		client.set(true)
		embeds("confabricate")
		optional("cloth-config", "modmenu", "sodium")
	}
}

tasks.modrinth {
	dependsOn(tasks.jar)
}

modrinth {
	token.set(project.findProperty("modrinth.token") as String? ?: "DUMMY")
	projectId.set(project.property("modrinth.id") as String)
	uploadFile.set(tasks.jar.get())
	changelog.set(readChangelog())
	dependencies {
		optional.project("9s6osm5g") // Cloth Config
		optional.project("mOgUt4GM") // Mod Menu
		optional.project("AANobbMI") // Sodium
	}
}

val publishAll by tasks.registering {
	dependsOn(tasks.publishMods)
	dependsOn(tasks.githubRelease)
	dependsOn(tasks.modrinth)
}

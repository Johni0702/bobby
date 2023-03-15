plugins {
	id("fabric-loom") version "1.1-SNAPSHOT"
	id("maven-publish")
	id("com.github.breadmoirai.github-release") version "2.2.12"
	id("com.matthewprenger.cursegradle") version "1.4.0"
	id("com.modrinth.minotaur") version "2.+"
	id("elect86.gik") version "0.0.4"
}

val modVersion: String by project
val mavenGroup: String by project
version = modVersion
group = mavenGroup

val minecraftVersion: String by project
val clothConfigVersion: String by project
val modMenuVersion: String by project

dependencies {
	val yarnMappings: String by project
	val loaderVersion: String by project
	val fabricApiVersion: String by project
	val configurateVersion: String by project
	val geantyrefVersion: String by project
	val hoconVersion: String by project
	val sodiumVersion: String by project
	val starlightVersion: String by project
	val confabricateVersion: String by project
	minecraft("com.mojang:minecraft:${minecraftVersion}")
	mappings("net.fabricmc:yarn:${yarnMappings}:v2")
	modImplementation("net.fabricmc:fabric-loader:${loaderVersion}")

	modImplementation(include(fabricApi.module("fabric-api-base", fabricApiVersion))!!)
	modImplementation(include(fabricApi.module("fabric-command-api-v2", fabricApiVersion))!!)

	// we don't need the full thing but our deps pull in an outdated one
	modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

	implementation(include("org.spongepowered:configurate-core:$configurateVersion")!!)
	implementation(include("org.spongepowered:configurate-hocon:$configurateVersion")!!)
	include("io.leangen.geantyref:geantyref:$geantyrefVersion")
	include("com.typesafe:config:$hoconVersion")

	modCompileOnly("maven.modrinth:sodium:$sodiumVersion")
	modCompileOnly("maven.modrinth:starlight:$starlightVersion")
	modCompileOnly("ca.stellardrift:confabricate:$confabricateVersion")
	modImplementation("me.shedaniel.cloth:cloth-config-fabric:$clothConfigVersion")
	modImplementation("com.terraformersmc:modmenu:$modMenuVersion")
}

tasks.processResources {
	inputs.property("version", modVersion)
	inputs.property("clothConfigVersion", clothConfigVersion)
	inputs.property("modMenuVersion", modMenuVersion)

	filesMatching("fabric.mod.json") {
		expand(mutableMapOf(
				"version" to modVersion,
				"clothConfigVersion" to clothConfigVersion,
				"modMenuVersion" to modMenuVersion
		))
	}
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
	sourceCompatibility = "17"
	targetCompatibility = "17"
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
	if (lines.next() != "### ${project.version}") {
		throw GradleException("CHANGELOG.md did not start with expected version!")
	}
	return lines.asSequence().takeWhile { it.isNotBlank() }.joinToString("\n")
}

githubRelease {
	token { project.property("github.token") as String }
	owner(project.property("github.owner") as String)
	repo(project.property("github.repo") as String)
	targetCommitish { gik.head!!.id }
	releaseName("Version ${project.version} for Minecraft $minecraftVersion")
	releaseAssets(tasks.remapJar)
	body(readChangelog())
}

curseforge {
	// Would prefer to use lazy `project.property` but https://github.com/matthewprenger/CurseGradle/issues/32
	apiKey = project.findProperty("curseforge.token") as String? ?: "DUMMY"
	project(closureOf<com.matthewprenger.cursegradle.CurseProject> {
		id = project.property("curseforge.id") as String
		changelog = readChangelog()
		releaseType = "release"
		mainArtifact(tasks.remapJar.flatMap { it.archiveFile }, closureOf<com.matthewprenger.cursegradle.CurseArtifact> {
			relations(closureOf<com.matthewprenger.cursegradle.CurseRelation> {
				embeddedLibrary("confabricate")
				optionalDependency("cloth-config")
				optionalDependency("modmenu")
				optionalDependency("sodium")
			})
		})
		addGameVersion("Fabric")
		addGameVersion(minecraftVersion)
		addGameVersion("Java 17")
	})
	options(closureOf<com.matthewprenger.cursegradle.Options> {
		javaVersionAutoDetect = false
		javaIntegration = false
		forgeGradleIntegration = false
	})
}
tasks.withType<com.matthewprenger.cursegradle.CurseUploadTask> {
	dependsOn(tasks.remapJar)
}

tasks.modrinth {
	dependsOn(tasks.remapJar)
}

modrinth {
	token.set(project.findProperty("modrinth.token") as String? ?: "DUMMY")
	projectId.set(project.property("modrinth.id") as String)
	uploadFile.set(tasks.remapJar.get())
	changelog.set(readChangelog())
	dependencies {
		optional.project("9s6osm5g") // Cloth Config
		optional.project("mOgUt4GM") // Mod Menu
		optional.project("AANobbMI") // Sodium
	}
}

val publishAll by tasks.registering {
	dependsOn(tasks.curseforge)
	dependsOn(tasks.githubRelease)
	dependsOn(tasks.modrinth)
}

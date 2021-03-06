plugins {
	id("fabric-loom") version "0.8-SNAPSHOT"
	id("maven-publish")
	id("com.github.breadmoirai.github-release") version "2.2.12"
	id("com.matthewprenger.cursegradle") version "1.4.0"
	id("com.modrinth.minotaur") version "1.1.0"
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
	val sodiumVersion: String by project
	val confabricateVersion: String by project
	minecraft("com.mojang:minecraft:${minecraftVersion}")
	mappings("net.fabricmc:yarn:${yarnMappings}:v2")
	modImplementation("net.fabricmc:fabric-loader:${loaderVersion}")
	modCompileOnly("com.github.jellysquid3:sodium-fabric:$sodiumVersion")
	modImplementation(include("ca.stellardrift:confabricate:$confabricateVersion")!!)
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
	sourceCompatibility = "1.8"
	targetCompatibility = "1.8"
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
			artifact(tasks.remapJar) {
				builtBy(tasks.remapJar)
			}
		}
	}
}

repositories {
	maven("https://jitpack.io") {
		content {
			includeGroup("com.github.jellysquid3")
		}
	}
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
	releaseName("Version ${project.version}")
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
		addGameVersion("Java 16")
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

val publishModrinth by tasks.registering(com.modrinth.minotaur.TaskModrinthUpload::class) {
	dependsOn(tasks.remapJar)
	token = project.property("modrinth.token") as String
	projectId = project.property("modrinth.id") as String
	versionNumber = "${project.version}"
	uploadFile = tasks.remapJar.flatMap { it.archiveFile }
	changelog = readChangelog()
	releaseType = "release"
	addLoader("fabric")
	addGameVersion(minecraftVersion)
}

val publishAll by tasks.registering {
	dependsOn(tasks.curseforge)
	dependsOn(tasks.githubRelease)
	dependsOn(publishModrinth)
}

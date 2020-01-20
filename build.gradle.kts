import com.jfrog.bintray.gradle.BintrayExtension
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import kotlin.reflect.KProperty

operator fun <T : Task> T.invoke(block: Action<T>) = block.execute(this)
operator fun Map<String, Any?>.getValue(thisRef: Any?, property: KProperty<*>) = get(property.name)?.toString()
fun BintrayExtension.pkg(block: BintrayExtension.PackageConfig.() -> Unit) = pkg.block()
fun BintrayExtension.PackageConfig.version(block: BintrayExtension.VersionConfig.() -> Unit) = version.block()
fun BintrayExtension.VersionConfig.gpg(block: BintrayExtension.GpgConfig.() -> Unit) = gpg.block()

val bintrayUser by project.properties
val bintrayToken by project.properties
val bintrayRepo = "maven"

val githubUser by project.properties
val githubToken by project.properties

plugins {
  kotlin("jvm") version "1.3.61" apply false
  id("org.jetbrains.dokka") version "0.9.16"
  id("com.jfrog.bintray") version "1.8.4"
  `maven-publish`
  signing
}

group = "io.opencubes"
version = "1.0.0-beta.1"

allprojects {
  apply(plugin = "org.jetbrains.kotlin.jvm")

  repositories {
    mavenCentral()
  }

  val implementation by configurations
  dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
  }

  tasks {
    "compileKotlin"(KotlinCompile::class) {
      kotlinOptions.jvmTarget = "1.8"
    }
    "compileTestKotlin"(KotlinCompile::class) {
      kotlinOptions.jvmTarget = "1.8"
    }
  }
}

val classes by tasks
val javadoc: Javadoc by tasks
val dokka: DokkaTask by tasks

val sourcesJar by tasks.creating(Jar::class) {
  dependsOn(classes)
  group = JavaBasePlugin.BUILD_TASK_NAME
  archiveClassifier.set("sources")
  from(project.the<SourceSetContainer>()["main"].allSource)
}

val javadocJar by tasks.creating(Jar::class) {
  dependsOn(dokka)
  group = JavaBasePlugin.DOCUMENTATION_GROUP
  archiveClassifier.set("javadoc")
  from(javadoc.destinationDir)
}

dokka {
  outputFormat = "html"
  outputDirectory = javadoc.destinationDir.toString()
}

publishing {
  publications {
    create<MavenPublication>(project.name) {
      from(components["java"])
      artifact(sourcesJar)
      artifact(javadocJar)
      pom {
        name.set(project.name)
        description.set("A super charged database connection with a model/active record")
        url.set("https://github.com/$githubUser/${project.name}#readme")
        licenses {
          license {
            name.set("MIT License")
            url.set("https://github.com/$githubUser/${project.name}/license")
            distribution.set("repo")
          }
        }
        developers {
          developer {
            id.set("ocpu")
            name.set("Martin Hövre")
            email.set("martin.hovre@opencubes.io")
          }
        }
        scm {
          connection.set("scm:git:git://github.com/$githubUser/${project.name}.git")
          developerConnection.set("scm:git:ssh://github.com:$githubUser/${project.name}.git")
          url.set("https://github.com/$githubUser/${project.name}/tree/kotlin-master")
        }
      }
    }
  }
  repositories {
    maven("https://maven.pkg.github.com/$githubUser/${project.name}") {
      name = "GitHub"
      credentials {
        username = githubUser ?: ""
        password = githubToken ?: ""
      }
    }
    maven("https://api.bintray.com/maven/$bintrayUser/$bintrayRepo/${project.name}") {
      name = "Bintray"
      credentials {
        username = bintrayUser ?: ""
        password = bintrayToken ?: ""
      }
    }
  }
}

signing {
  useGpgCmd()
  sign(publishing.publications[project.name])
}

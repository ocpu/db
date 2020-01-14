import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

dependencies {
  val implementation by configurations
  val compile by configurations

  implementation("mysql:mysql-connector-java:8.0.11")
  implementation("org.xerial:sqlite-jdbc:3.25.2")
  implementation("com.microsoft.sqlserver:mssql-jdbc:7.2.2.jre8")

  compile(rootProject)
}

sourceSets["main"].java.srcDirs.clear()
sourceSets["main"].withConvention(KotlinSourceSet::class) {
  kotlin.srcDir("src")
}

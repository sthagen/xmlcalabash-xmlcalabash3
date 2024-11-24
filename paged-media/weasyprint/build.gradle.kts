import com.xmlcalabash.build.XmlCalabashBuildExtension

plugins {
  id("buildlogic.kotlin-library-conventions")
  id("com.xmlcalabash.build.xmlcalabash-build")
}

dependencies {
  implementation(project(":xmlcalabash"))
}

val xmlbuild = the<XmlCalabashBuildExtension>()

tasks.withType<Test> {
  this.testLogging {
    this.showStandardStreams = true
  }
}

tasks.jar {
  archiveFileName.set(xmlbuild.jarArchiveFilename())
}


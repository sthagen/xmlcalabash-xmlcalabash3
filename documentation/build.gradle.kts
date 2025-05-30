import com.xmlcalabash.build.XmlCalabashBuildExtension
import com.xmlcalabash.build.ExternalDependencies

import java.net.URI
import java.net.URL
import java.net.HttpURLConnection
import java.io.File
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.PrintStream
import java.io.InputStreamReader
import java.io.ByteArrayOutputStream
import com.nwalsh.gradle.saxon.SaxonXsltTask
import com.nwalsh.gradle.relaxng.validate.RelaxNGValidateTask
import com.nwalsh.gradle.relaxng.translate.RelaxNGTranslateTask

buildscript {
  repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://maven.saxonica.com/maven") }
  }

  configurations.all {
    resolutionStrategy.eachDependency {
      if (requested.group == "net.sf.saxon" && requested.name == "Saxon-HE") {
        useVersion(project.properties["saxonVersion"].toString())
      }
    }
  }

  dependencies {
    classpath("net.sf.saxon:Saxon-HE:${project.properties["saxonVersion"]}")
    classpath("org.docbook:schemas-docbook:${project.properties["docbookVersion"]}")
    classpath("org.docbook:docbook-xslTNG:${project.properties["xslTNGversion"]}")
  }
}

plugins {
  id("buildlogic.kotlin-library-conventions")
  id("com.xmlcalabash.build.xmlcalabash-build")
  id("com.nwalsh.gradle.saxon.saxon-gradle") version "0.10.4"
  id("com.nwalsh.gradle.relaxng.validate") version "0.10.3"
  id("com.nwalsh.gradle.relaxng.translate") version "0.10.5"
}

val saxonVersion = project.properties["saxonVersion"].toString()

val refVersion = (project.findProperty("refVersion")
                      ?: project.findProperty("xmlcalabashVersion")).toString()
val guideVersion = (project.findProperty("guideVersion")
                        ?: project.findProperty("xmlcalabashVersion")).toString()

val xmlbuild = the<XmlCalabashBuildExtension>()

configurations.all {
  resolutionStrategy.eachDependency {
    if (requested.group == "net.sf.saxon" && requested.name == "Saxon-HE") {
      useVersion(saxonVersion)
    }
  }
}

val documentation by configurations.creating
val deltaxml by configurations.creating
val transform by configurations.creating {
  extendsFrom(configurations["documentation"])
}

dependencies {
  documentation ("net.sf.saxon:Saxon-HE:${saxonVersion}")
  documentation ("org.docbook:schemas-docbook:5.2")
  documentation ("org.docbook:docbook-xslTNG:${project.properties["xslTNGversion"]}")

  documentation("org.apache.xmlgraphics:fop:2.9")
  documentation("org.apache.avalon.framework:avalon-framework-api:4.3.1")
  documentation("org.apache.avalon.framework:avalon-framework-impl:4.3.1")
  documentation("javax.media:jai-core:1.1.3")
  documentation("com.sun.media:jai-codec:1.1.3")

  documentation(project(":app", "runtimeElements"))
  documentation(project(":xmlcalabash", "runtimeElements"))

  deltaxml(fileTree("dir" to layout.projectDirectory.dir("lib"),
                    "include" to "*.jar"))
}

var haveDeltaXml = false
fileTree("dir" to layout.projectDirectory.dir("lib"),
         "include" to "*.jar").filter { it.isFile() }.files.forEach { fn ->
  if (fn.toString().indexOf("/deltaxml") > 0) {
    haveDeltaXml = true
  }
}

val makeExamples = tasks.register("makeExamples") {
  // Just somewhere to hang dependencies
}

val docbookRNG = tasks.register<RelaxNGTranslateTask>("translateDocBook") {
  inputs.file(layout.projectDirectory.file("src/rng/docbook.rnc"))
  outputs.file(layout.buildDirectory.file("rng/docbook.rng"))

  input(layout.projectDirectory.file("src/rng/docbook.rnc"))
  output(layout.buildDirectory.file("rng/docbook.rng").get())
}

val xmlCalabashVersion = tasks.register<JavaExec>("xmlCalabashVersion") {
  outputs.file(layout.buildDirectory.file("version.json"))

  val stdout = ByteArrayOutputStream()
  standardOutput = stdout
  val stderr = ByteArrayOutputStream()
  errorOutput = stderr
  classpath = configurations.named("documentation").get()
  mainClass = "com.xmlcalabash.app.Main"
  args("version", "--debug")

  doLast {
    val text = stdout.toString("UTF-8")
    val output = layout.buildDirectory.file("version.json").get().asFile
    val stream = PrintStream(output)
    stream.println("{")
    for (line in text.split("\n")) {
      if ("=" in line) {
        val (pname, pvalue) = line.split("=")
        stream.println("\"${pname}\": \"${pvalue}\",")
      }
    }
    stream.println("\"refVersion\": \"${refVersion}\",")
    stream.println("\"guideVersion\": \"${guideVersion}\",")
    stream.println("\"docbookVersion\": \"${project.properties["docbookVersion"]}\",")
    stream.println("\"xslTNGversion\": \"${project.properties["xslTNGversion"]}\"")
    stream.println("}")
    stream.close()
  }
}

val xmlCalabashBuildInfo = tasks.register("xmlCalabashBuildInfo") {
  outputs.file(layout.buildDirectory.file("build-info.xml"))

  doLast {
    PrintStream(layout.buildDirectory.file("build-info.xml").get().asFile).use { stream ->
      stream.println("<build-info>");
      stream.println("<product-name>${xmlbuild.productName.get()}</product-name>")
      stream.println("<version>${xmlbuild.version.get()}</version>")
      stream.println("<build-date>${xmlbuild.buildDate.get()}</build-date>")
      stream.println("<build-id>${xmlbuild.buildId()}</build-id>")
      stream.println("<vendor-name>${xmlbuild.vendorName.get()}</vendor-name>")
      stream.println("<vendor-uri>${xmlbuild.vendorUri.get()}</vendor-uri>")
      stream.println("<saxon-version>${saxonVersion}</saxon-version>")
      stream.println("<ref-version>${refVersion}</ref-version>")
      stream.println("<guide-version>${guideVersion}</guide-version>")
      stream.println("<docbook-version>${project.properties["docbookVersion"]}</docbook-version>")
      stream.println("<xslTNG-version>${project.properties["xslTNGversion"]}</xslTNG-version>")
      stream.println("<dependencies>")

      for ((step, deps) in ExternalDependencies.steps) {
        stream.println("  <${step}>")

        // These are special
        if (step == "xmlcalabash") {
          stream.println("    <depends-on version=\"1.3.1\">name.dmaus.schxslt:schxslt2</depends-on>")
          stream.println("    <depends-on version=\"${saxonVersion}\">net.sf.saxon:Saxon-HE</depends-on>")
        }

        for (dep in deps) {
          val colon = dep.lastIndexOf(":")
          val pkg = dep.substring(0, colon)
          val version = dep.substring(colon+1)
          stream.println("    <depends-on version=\"${version}\">${pkg}</depends-on>")
        }
        stream.println("  </${step}>")
      }
      stream.println("</dependencies>")
      stream.println("</build-info>");
    }
  }
}

// ============================================================

val rngArchiveManifestSchema = tasks.register<RelaxNGTranslateTask>("rngArchiveManifestSchema") {
  inputs.file(
      layout.projectDirectory.file("../xmlcalabash/src/main/resources/com/xmlcalabash/archive-manifest.rnc"))
  outputs.file(layout.buildDirectory.file("archive-manifest.rng"))

  input(
      layout.projectDirectory.file("../xmlcalabash/src/main/resources/com/xmlcalabash/archive-manifest.rnc"))
  output(layout.buildDirectory.file("archive-manifest.rng").get().asFile)
  inputType("rnc")
  outputType("rng")
}              

val rngPipelineMessagesSchema = tasks.register<RelaxNGTranslateTask>("rngPipelineMessagesSchema") {
  inputs.file(
      layout.projectDirectory.file("../xmlcalabash/src/main/resources/com/xmlcalabash/pipeline-messages.rnc"))
  outputs.file(layout.buildDirectory.file("pipeline-messages.rng"))

  input(
      layout.projectDirectory.file("../xmlcalabash/src/main/resources/com/xmlcalabash/pipeline-messages.rnc"))
  output(layout.buildDirectory.file("pipeline-messages.rng").get().asFile)
  inputType("rnc")
  outputType("rng")
}              

val xincludeReference = tasks.register<SaxonXsltTask>("xincludeReference") {
  dependsOn("makeExamples")
  dependsOn(rngArchiveManifestSchema)
  dependsOn(rngPipelineMessagesSchema)

  inputs.file(rngArchiveManifestSchema.get().outputs.getFiles().getSingleFile())
  inputs.dir(layout.buildDirectory.dir("examples"))
  inputs.dir(layout.projectDirectory.dir("src/reference"))
  inputs.dir(layout.projectDirectory.dir("src/examples"))
  inputs.dir(layout.projectDirectory.dir("../xmlcalabash/src/main/resources/com/xmlcalabash"))
  input(layout.projectDirectory.file("src/reference/reference.xml"))
  stylesheet(layout.projectDirectory.file("src/xsl/xinclude.xsl"))
  output(layout.buildDirectory.file("refbuild/reference.xml").get())
  args(listOf("-init:org.docbook.xsltng.extensions.Register"))
  parameters (
      mapOf(
          "docbook-transclusion" to "true"
      )
  )
}

val processReference = tasks.register<JavaExec>("processReference") {
  dependsOn("translateDocBook")
  dependsOn("xincludeReference")

  inputs.file(xincludeReference.get().outputs.getFiles().getSingleFile())
  inputs.file(layout.projectDirectory.file("src/xpl/process.xpl"))
  outputs.file(layout.buildDirectory.file("refbuild/processed.xml"))

  classpath = configurations.named("documentation").get()
  mainClass = "com.xmlcalabash.app.Main"
  args(layout.projectDirectory.file("src/xpl/process.xpl"),
       "-i:source=${xincludeReference.get().outputs.getFiles().getSingleFile()}",
       "-o:result=${layout.buildDirectory.file("refbuild/processed.xml").get()}")
}

val validateReference = tasks.register<RelaxNGValidateTask>("validateReference") {
  dependsOn("translateDocBook")
  dependsOn("processReference")

  inputs.file(docbookRNG.get().outputs.getFiles().getSingleFile())
  inputs.file(processReference.get().outputs.getFiles().getSingleFile())
  outputs.file(layout.buildDirectory.file("refbuild/validated.xml"))

  input(processReference.get().outputs.getFiles().getSingleFile())
  schema(docbookRNG.get().outputs.getFiles().getSingleFile())
  output(layout.buildDirectory.file("refbuild/validated.xml").get())
}

val reference = tasks.register<SaxonXsltTask>("reference") {
  dependsOn("copyReferenceResources")
  dependsOn("validateReference")
  dependsOn("xmlCalabashVersion")
  dependsOn("xmlCalabashBuildInfo")

  inputs.file(layout.buildDirectory.file("version.json"))
  inputs.dir(layout.projectDirectory.dir("src/xsl"))
  inputs.file(layout.buildDirectory.file("archive-manifest.rng"))
  outputs.files(fileTree("dir" to layout.buildDirectory.dir("reference/current"),
                         "include" to "*.html"))

  input(validateReference.get().outputs.getFiles().getSingleFile())
  stylesheet(layout.projectDirectory.file("src/xsl/reference.xsl"))
  output(layout.buildDirectory.file("reference/current/index.html").get())
  args(listOf("-init:org.docbook.xsltng.extensions.Register"))
  parameters (
      mapOf(
          "mediaobject-input-base-uri" to "file:${layout.buildDirectory.get()}/reference/current/",
          "chunk-output-base-uri" to "${layout.buildDirectory.get()}/reference/current/",
          "dep_fop" to project.findProperty("fop").toString()
      )
  )

  doLast {
    val stream = PrintStream(layout.buildDirectory.file("reference/details.json").get().asFile)
    stream.println("{\"version\": \"${refVersion}\", \"pubdate\": \"${xmlbuild.buildTime.get()}\"}")
    stream.close()
  }
}

tasks.register("copyReferenceJarResources") {
  outputs.dir(project.layout.buildDirectory.dir("reference/current"))

  val dbjar = configurations.named("transform").get().getFiles()
      .filter { jar -> jar.toString().contains("docbook-xslTNG") }
      .elementAtOrNull(0)

  doLast {
    if (dbjar == null) {
      throw GradleException("Failed to locate DocBook xslTNG jar file")
    }

    copy {
      from(zipTree(dbjar.toString()))
      into(layout.buildDirectory.dir("reference/current"))
      include("org/docbook/xsltng/resources/**")
      eachFile {
        relativePath = RelativePath(true, *relativePath.segments.drop(4).toTypedArray())
      }
    }
  }

  doLast {
    delete(project.layout.buildDirectory.dir("reference/org"))
  }
}

tasks.register<Copy>("copyReferenceStaticResources") {
  into(layout.buildDirectory.dir("reference/current"))
  from(layout.projectDirectory.dir("src/resources")) {
    exclude("img/*.psd")
  }
}

tasks.register("copyReferenceResources") {
  dependsOn("copyReferenceJarResources", "copyReferenceStaticResources")
}

// ============================================================

val rngConfigSchema = tasks.register<RelaxNGTranslateTask>("rngConfigSchema") {
  inputs.file(
      layout.projectDirectory.file("../xmlcalabash/src/main/resources/com/xmlcalabash/xml-calabash.rnc"))
  outputs.file(layout.buildDirectory.file("xml-calabash.rng"))

  input(
      layout.projectDirectory.file("../xmlcalabash/src/main/resources/com/xmlcalabash/xml-calabash.rnc"))
  output(layout.buildDirectory.file("xml-calabash.rng").get().asFile)
  inputType("rnc")
  outputType("rng")
}              

val rngTraceSchema = tasks.register<RelaxNGTranslateTask>("rngTraceSchema") {
  inputs.file(
      layout.projectDirectory.file("../xmlcalabash/src/main/resources/com/xmlcalabash/trace.rnc"))
  outputs.file(layout.buildDirectory.file("trace.rng"))

  input(
      layout.projectDirectory.file("../xmlcalabash/src/main/resources/com/xmlcalabash/trace.rnc"))
  output(layout.buildDirectory.file("trace.rng").get().asFile)
  inputType("rnc")
  outputType("rng")
}              

val rngDescriptionSchema = tasks.register<RelaxNGTranslateTask>("rngDescriptionSchema") {
  inputs.file(
      layout.projectDirectory.file("../xmlcalabash/src/main/resources/com/xmlcalabash/description.rnc"))
  outputs.file(layout.buildDirectory.file("description.rng"))

  input(
      layout.projectDirectory.file("../xmlcalabash/src/main/resources/com/xmlcalabash/description.rnc"))
  output(layout.buildDirectory.file("description.rng").get().asFile)
  inputType("rnc")
  outputType("rng")
}              

val xincludeUserguide = tasks.register<SaxonXsltTask>("xincludeUserguide") {
  dependsOn(rngConfigSchema)
  dependsOn(rngTraceSchema)
  dependsOn(rngDescriptionSchema)

  inputs.file(rngConfigSchema.get().outputs.getFiles().getSingleFile())
  inputs.dir(layout.projectDirectory.dir("src/userguide"))

  input(layout.projectDirectory.file("src/userguide/userguide.xml"))
  stylesheet(layout.projectDirectory.file("src/xsl/xinclude.xsl"))
  output(layout.buildDirectory.file("ugbuild/userguide.xml").get())
  args(listOf("-init:org.docbook.xsltng.extensions.Register"))
  parameters (
      mapOf(
          "docbook-transclusion" to "true"
      )
  )
}

val validateUserguide = tasks.register<RelaxNGValidateTask>("validateUserguide") {
  dependsOn("translateDocBook")
  dependsOn("xincludeUserguide")

  inputs.file(docbookRNG.get().outputs.getFiles().getSingleFile())
  inputs.file(xincludeUserguide.get().outputs.getFiles().getSingleFile())
  outputs.file(layout.buildDirectory.file("ugbuild/validated.xml"))

  input(xincludeUserguide.get().outputs.getFiles().getSingleFile())
  schema(docbookRNG.get().outputs.getFiles().getSingleFile())
  output(layout.buildDirectory.file("ugbuild/validated.xml").get())
}

val userguide = tasks.register<SaxonXsltTask>("userguide") {
  dependsOn("copyUserguideResources")
  dependsOn("validateUserguide")
  dependsOn("xmlCalabashVersion")
  dependsOn("xmlCalabashBuildInfo")
  finalizedBy(tasks.named("changelog"))

  inputs.file(layout.buildDirectory.file("version.json"))
  inputs.dir(layout.projectDirectory.dir("src/xsl"))
  outputs.files(fileTree("dir" to layout.buildDirectory.dir("userguide/current"),
                         "include" to "*.html"))

  input(validateUserguide.get().outputs.getFiles().getSingleFile())
  stylesheet(layout.projectDirectory.file("src/xsl/userguide.xsl"))
  output(layout.buildDirectory.file("userguide/current/index.html").get())
  args(listOf("-init:org.docbook.xsltng.extensions.Register"))
  parameters (
      mapOf(
          "mediaobject-input-base-uri" to "file:${layout.buildDirectory.get()}/userguide/current/",
          "chunk-output-base-uri" to "${layout.buildDirectory.get()}/userguide/current/"
      )
  )

  doLast {
    val stream = PrintStream(layout.buildDirectory.file("userguide/details.json").get().asFile)
    stream.println("{\"version\": \"${guideVersion}\", \"pubdate\": \"${xmlbuild.buildTime.get()}\"}")
    stream.close()
  }
}

tasks.register<SaxonXsltTask>("changelog_html") {
  dependsOn("validateUserguide")
  dependsOn("xmlCalabashVersion")
  dependsOn("xmlCalabashBuildInfo")

  inputs.file(layout.buildDirectory.file("version.json"))
  inputs.file(layout.projectDirectory.file("tools/changelog.xsl"))
  outputs.file(layout.buildDirectory.file("changes.html"))

  input(validateUserguide.get().outputs.getFiles().getSingleFile())
  stylesheet(layout.projectDirectory.file("tools/changelog.xsl"))
  output(layout.buildDirectory.file("changes.html").get())
  parameters (
      mapOf("version" to "r" + guideVersion.replace(".", ""))
  )
}

tasks.register<SaxonXsltTask>("changelog") {
  dependsOn("changelog_html")
  inputs.file(layout.projectDirectory.file("tools/html2txt.xsl"))

  doFirst {
    Html5Parser.parse(layout.buildDirectory.file("changes.html").get().asFile,
                      layout.buildDirectory.file("changes.xhtml").get().asFile)
  }
  input(layout.buildDirectory.file("changes.xhtml").get())
  output(layout.buildDirectory.file("changes.txt").get())
  stylesheet(layout.projectDirectory.dir("tools/html2txt.xsl"))
}

tasks.register("release") {
  dependsOn(userguide)
  dependsOn(reference)
  // nop, just make sure the guides get built
}

tasks.register("copyUserguideJarResources") {
  outputs.dir(project.layout.buildDirectory.dir("userguide/current"))

  val dbjar = configurations.named("transform").get().getFiles()
      .filter { jar -> jar.toString().contains("docbook-xslTNG") }
      .elementAtOrNull(0)

  doLast {
    if (dbjar == null) {
      throw GradleException("Failed to locate DocBook xslTNG jar file")
    }

    copy {
      from(zipTree(dbjar.toString()))
      into(layout.buildDirectory.dir("userguide/current"))
      include("org/docbook/xsltng/resources/**")
      eachFile {
        relativePath = RelativePath(true, *relativePath.segments.drop(4).toTypedArray())
      }
    }
  }

  doLast {
    delete(project.layout.buildDirectory.dir("userguide/current/org"))
  }
}

tasks.register<Copy>("copyUserguideStaticResources") {
  into(layout.buildDirectory.dir("userguide/current"))
  from(layout.projectDirectory.dir("src/resources")) {
    exclude("img/*.psd")
  }
}

tasks.register("copyUserguideResources") {
  dependsOn("copyUserguideJarResources", "copyUserguideStaticResources")
}

// ============================================================

val download = tasks.register("downloadSpecifications")

for ((hpath, bpath) in mapOf(
         "master/head/steps" to "steps.xml",
         "master/head/run" to "run.xml",
         "master/head/file" to "file.xml",
         "master/head/os" to "os.xml",
         "master/head/mail" to "mail.xml",
         "master/head/paged-media" to "paged-media.xml",
         "master/head/rdf" to "rdf.xml",
         "master/head/text" to "text.xml",
         "master/head/validation" to "validation.xml",
         "master/head/ixml" to "ixml.xml")) {
  val dtask = tasks.register("download_${hpath.split("/").last()}") {
    outputs.file(layout.buildDirectory.file("specs/${bpath}"))

    val inputURI = "https://spec.xproc.org/${hpath}/specification.xml"
    val outputFile = layout.buildDirectory.file("specs/${bpath}").get().toString()

    doFirst {
      //println("Downloading ${inputURI}")
      mkdir(layout.buildDirectory.file("specs"))
    }

    doLast {
      val specurl = URI(inputURI).toURL()
      with (specurl.openConnection() as HttpURLConnection) {
        BufferedReader(InputStreamReader(inputStream)).use {
          val response = PrintStream(File(outputFile))
          var inputLine = it.readLine()
          while (inputLine != null) {
            response.println(inputLine)
            inputLine = it.readLine()
          }
          it.close()
        }
      }
    }
  }

  download {
    dependsOn(dtask)
  }
}

// No longer used after the extracted parts were merged into the sources
//tasks.register<SaxonXsltTask>("extractSteps") {
//  dependsOn("downloadSpecifications")
//  inputs.dir(layout.buildDirectory.dir("specs"))
//  inputs.file(layout.projectDirectory.file("tools/extract-steps.xsl"))
//
//  stylesheet(layout.projectDirectory.file("tools/extract-steps.xsl"))
//  output(layout.buildDirectory.file("steps.xml").get())
//  args(listOf("-it"))
//}

val exampleConfig = mapOf<String,Map<String,String>>(
    "add-attribute-001.xpl" to mapOf("input" to "default-input.xml", "diff" to "default-input.xml"),
    "add-xml-base-001.xpl" to mapOf(),
    "delete-001.xpl" to mapOf("input" to "default-input.xml", "diff" to "default-input.xml"),
    "delete-002.xpl" to mapOf("input" to "default-input.xml", "diff" to "default-input.xml"),
    "messages.xpl" to mapOf("input" to "default-input.xml"),
    "rdfa.xpl" to mapOf("input" to "../html/rdfa.html"),
    "sparql.xpl" to mapOf("input" to "../rdf/address-book.rdf")
)

for ((example,options) in exampleConfig) {
  val txpl = tasks.register<JavaExec>("example_${example}") {
    classpath = configurations.named("documentation").get()
    mainClass = "com.xmlcalabash.app.Main"

    val inputFile = layout.projectDirectory.dir("src/examples/xpl/${example}")
    val outputFile = layout.buildDirectory.file("examples/results/${example.replace(".xpl", ".xml")}")

    inputs.file(inputFile)
    inputs.dir(layout.projectDirectory.dir("src/examples/xml"))
    outputs.file(outputFile)

    if ("input" in options) {
      args("--config:${layout.projectDirectory.file("tools/xmlcalabash3.xml")}",
           "-i:source=${layout.projectDirectory.file("src/examples/xml/${options["input"]!!}")}",
           "-o:result=${outputFile.get().asFile}",
           "${inputFile}")
    } else {
      args("--config:${layout.projectDirectory.file("tools/xmlcalabash3.xml")}",
           "-o:result=${outputFile.get().asFile}",
           "${inputFile}")
    }
  }
  makeExamples { dependsOn(txpl) }

  if ("diff" in options) {
    val fileA = layout.projectDirectory.dir("src/examples/xml/${options["diff"]}")
    val fileB = layout.buildDirectory.file("examples/results/${example.replace(".xpl", ".xml")}")

    val tdx = if (haveDeltaXml) {
      tasks.register<JavaExec>("deltaxml_${example}") {
        classpath = configurations.named("deltaxml").get()
        mainClass = "com.deltaxml.cmdline.PipelinedTextUI"
        dependsOn("example_${example}")
        if ("depends" in options) {
          dependsOn("example_${options["depends"]}")
        }

        val outputFile = layout.buildDirectory.file("examples/deltaxml/${example.replace(".xpl", ".xml")}")

        inputs.file(fileA)
        inputs.file(fileB)
        outputs.file(outputFile)

        doFirst {
          mkdir(layout.buildDirectory.dir("examples/deltaxml"))
          /*
          println("compare ${fileA}")
          println("     to ${fileB.get().asFile}")
          println(" output ${outputFile.get().asFile}")
          println("====")
          */
        }
        
        args("compare", "doc-delta",
             "${fileA}",
             "${fileB.get().asFile}",
             "${outputFile.get().asFile}")
      }
    } else {
      tasks.register("deltaxml_${example}") {
        dependsOn("example_${example}")
        if ("depends" in options) {
          dependsOn("example_${options["depends"]}")
        }

        val outputFile = layout.buildDirectory.file("examples/deltaxml/${example.replace(".xpl", ".xml")}")

        inputs.file(fileA)
        inputs.file(fileB)
        outputs.file(outputFile)

        doLast {
          val stream = PrintStream(outputFile.get().asFile)
          stream.println("No diff is possible; DeltaXML is unavailable")
          stream.close()
        }
      }
    }
    makeExamples { dependsOn(tdx) }

    val txsl = if (haveDeltaXml) {
      tasks.register<JavaExec>("diff_${example}") {
        classpath = configurations.named("documentation").get()
        mainClass = "net.sf.saxon.Transform"
        dependsOn(tdx)

        val inputFile = tdx.get().outputs.getFiles().getSingleFile()
        val outputFile = layout.buildDirectory.file("examples/diff/${example.replace(".xpl", ".xml")}")

        inputs.files(tdx)
        inputs.file(layout.projectDirectory.file("tools/dx-output.xsl"))
        outputs.file(outputFile)

        args("-s:${inputFile}",
             "-xsl:${layout.projectDirectory.file("tools/dx-output.xsl")}",
             "-o:${outputFile.get().asFile}")
      }
    } else {
      tasks.register("diff_${example}") {
        dependsOn(tdx)

        val outputFile = layout.buildDirectory.file("examples/diff/${example.replace(".xpl", ".xml")}")

        inputs.files(tdx)
        inputs.file(layout.projectDirectory.file("tools/dx-output.xsl"))
        outputs.file(outputFile)

        doLast {
          val stream = PrintStream(outputFile.get().asFile)
          stream.println("<para role='nodiff' xmlns='http://docbook.org/ns/docbook'>The")
          stream.println("change markup version is unavailable. DeltaXML is required")
          stream.println("to generate change markup.")
          stream.println("</para>")
          stream.close()
        }
      }
    }
    makeExamples { dependsOn(txsl) }
  }
}

tasks.register<JavaExec>("dxx") {
  classpath = configurations.named("deltaxml").get()
  mainClass = "com.deltaxml.cmdline.PipelinedTextUI"
  args("compare", "doc-delta",
       "src/examples/xml/default-input.xml",
       "build/examples/results/add-attribute-001.xml",
       "/tmp/out.xml")
}

// ============================================================

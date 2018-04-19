import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.regex.Pattern
import scala.xml.XML

import sbt._
import scala.sys.process._

val Organization = "figtools"
val Name = "figtools"
val Version = "0.1.0"
val ScalaVersion = "2.12.5"
val DebugPort = 5005

lazy val updatePrependScript = TaskKey[String]("updatePrependScript")
lazy val figtools = (project in file(".")).
  settings(
    name := Name,
    organization := Organization,
    version := Version,
    scalaVersion := ScalaVersion,
    scalaSource in Compile := baseDirectory.value / "src",
    scalaSource in Test := baseDirectory.value / "test/src",
    javaSource in Compile := baseDirectory.value / "src",
    javaSource in Test := baseDirectory.value / "test/src",
    resourceDirectory in Compile := baseDirectory.value / "resources",
    resourceDirectory in Test := baseDirectory.value / "test/resources",
    mainClass in Compile := Some("figtools.FigTools"),
    resolvers += "imagej" at "http://maven.imagej.net/content/repositories/thirdparty/",
    resolvers += "imagej public" at "http://maven.imagej.net/content/groups/public/",
    resolvers += "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
    libraryDependencies ++= Seq(
      "com.github.scopt" %% "scopt" % "3.6.0",
      "net.imagej" % "ij" % "1.50i",
      "net.sourceforge.tess4j" % "tess4j" % "3.4.0",
      "edu.stanford.nlp" % "stanford-corenlp" % "3.8.0",
      "edu.stanford.nlp" % "stanford-corenlp" % "3.8.0" classifier "models-english",
      "org.json4s" %% "json4s-jackson" % "3.5.3",
      "com.github.pathikrit" %% "better-files" % "3.0.0",
      "org.tensorflow" % "tensorflow" % "1.2.1",
      "com.beachape" %% "enumeratum" % "1.5.12",
      "com.lihaoyi" %% "fastparse" % "1.0.0",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
      "com.lihaoyi" %% "pprint" % "0.5.3",
      "com.conversantmedia" % "rtree" % "1.0.4"
    ),
    artifactPath in (Compile, packageBin) := {
      baseDirectory.value / "bin" / name.value
    },
    updatePrependScript := {
      val ivyReportFile = (ivyReport in Compile).value
      val xml = XML.loadFile(ivyReportFile)
      val (deps, depsScript) = (for {
        artifact <- xml \\ "ivy-report" \\ "dependencies" \ "module" \ "revision" \ "artifacts" \ "artifact"
        location = (artifact \ "@location").text.
          replaceFirst(s"""^${Pattern.quote(System.getProperty("user.home"))}""","\\$HOME")
        originLocation = (artifact \ "origin-location" \ "@location").text
      } yield {(location,s"""test ! -e "$location" && mkdir -p "${new File(location).getParent}" && (set -x; curl -Sso "$location" '$originLocation')""")}).unzip
      val prependShellScript =
        s"""#!/usr/bin/env bash
${depsScript.mkString("\n")}
TF_CPP_MIN_LOG_LEVEL=3 exec java $${DEBUG+-agentlib:jdwp=transport=dt_socket,server=y,address=$DebugPort,suspend=n} -noverify -XX:+UseG1GC $$JAVA_OPTS -cp "$$0:${deps.mkString(":")}" "${(mainClass in Compile).value.get}" "$$@"
"""
      val prependScript = (baseDirectory.value / "target" / s"${(mainClass in Compile).value.get}.prependShellScript.sh").toString
      Files.write(Paths.get(prependScript), prependShellScript.getBytes(StandardCharsets.UTF_8))
      prependScript
    },
    packageBin in Compile := {
      val original = (packageBin in Compile).value
      //val prependScript = updatePrependScript.value
      val prependScript = (baseDirectory.value / "target" / s"${(mainClass in Compile).value.get}.prependShellScript.sh").toString
      Seq("bash","-c",s"""cat '$prependScript' <(sed -ne '/^PK/,$$p' '$original') >"$original.$$$$" && mv "$original.$$$$" '$original' && chmod +x '$original'""").!
      original
    }
  )

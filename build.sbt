import com.typesafe.sbt.gzip.Import.gzip
import com.typesafe.sbt.web.SbtWeb
import play.PlayImport._
import play.PlayScala

name := """shebang"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala,SbtWeb)

scalaVersion := "2.11.4"

libraryDependencies ++= Seq(
  filters,
  cache,
  ws
)

//add web jars here
libraryDependencies ++= Seq(
  "org.webjars" % "bootstrap" % "3.3.0" exclude("org.webjars", "jquery"),
  "org.webjars" % "requirejs" % "2.1.11-1",
  "org.webjars" % "angularjs" % "1.3.2",
  "org.webjars" % "jquery" % "2.1.1",
  "org.webjars" % "lodash" % "2.4.1-6",
  "org.webjars" % "holderjs" % "2.4.0"
)

PlayKeys.playDefaultPort := 9000

//accept gzip with html, css, js
includeFilter in gzip := "*.html" || "*.css" || "*.js"

scalacOptions in ThisBuild ++= Seq(
  "-target:jvm-1.7",  // todo upgrade to 1.8
  "-encoding", "UTF-8",
  "-deprecation", // warning and location for usages of deprecated APIs
  "-feature", // warning and location for usages of features that should be imported explicitly
  //    "-unchecked", // additional warnings where generated code depends on assumptions
  //    "-Xlint", // recommended additional warnings
  //    "-Ywarn-adapted-args", // Warn if an argument list is modified to match the receiver
  //    "-Ywarn-value-discard", // Warn when non-Unit expression results are unused
  //    "-Ywarn-inaccessible",
  //    "-Ywarn-dead-code",
  "-language:postfixOps"
)

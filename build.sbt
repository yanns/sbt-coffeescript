sbtPlugin := true

organization := "com.typesafe"

name := "sbt-coffeescript-plugin"

version := "0.1-SNAPSHOT"

scalaVersion := "2.10.3"

libraryDependencies ++= Seq(
  "org.webjars" % "coffee-script" % "1.6.3",
  "commons-io" % "commons-io" % "2.4"
  //"com.typesafe" %% "js-engine-sbt" % "1.0.0-SNAPSHOT" extra("sbt" -> sbtVersion.value)
  //"com.typesafe.akka" %% "akka-actor" % "2.2.1",
  //"io.spray" %% "spray-json" % "1.2.5",
  //"org.webjars" % "jslint" % "c657984cd7",
  //"org.webjars" % "webjars-locator" % "0.5",
  //"org.specs2" %% "specs2" % "2.3.4" % "test"
  //"junit" % "junit" % "4.11" % "test",
  //"com.typesafe.akka" %% "akka-testkit" % "2.2.1" % "test"
)

addSbtPlugin("com.typesafe" % "sbt-js-engine" % "1.0.0-SNAPSHOT")
//addSbtPlugin("com.typesafe" %% "js-engine-sbt" % "1.0.0-SNAPSHOT")

//addSbtPlugin("com.typesafe" %% "sbt-web" % "1.0.0-SNAPSHOT")

scriptedSettings

scriptedLaunchOpts <+= version apply { v => s"-Dproject.version=$v" }

fork in run := true

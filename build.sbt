sbtPlugin := true

organization := "com.typesafe"

name := "sbt-coffeescript-plugin"

version := "0.1-SNAPSHOT"

scalaVersion := "2.10.3"

scalacOptions += "-feature"

libraryDependencies ++= Seq(
  "org.webjars" % "coffee-script" % "1.6.3",
  "commons-io" % "commons-io" % "2.4"
)

addSbtPlugin("com.typesafe" % "sbt-js-engine" % "1.0.0-SNAPSHOT")

scriptedSettings

scriptedLaunchOpts <+= version apply { v => s"-Dproject.version=$v" }

fork in run := true

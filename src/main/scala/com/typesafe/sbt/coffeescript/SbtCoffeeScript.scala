package com.typesafe.sbt.coffeescript

import com.typesafe.sbt.jse.SbtJsTask
import sbt._
import com.typesafe.sbt.web.SbtWeb
import spray.json.{JsBoolean, JsObject}
import sbt.Keys._

object Import {

  object CoffeescriptKeys {
    val coffeescript = TaskKey[Seq[File]]("coffeescript", "Invoke the CoffeeScript compiler.")

    val bare = SettingKey[Boolean]("coffeescript-bare", "Compiles JavaScript that isn't wrapped in a function.")
    val sourceMap = SettingKey[Boolean]("coffeescript-source-map", "Outputs a v3 sourcemap.")
  }

}

object SbtCoffeeScript extends AutoPlugin {

  override def requires = SbtJsTask

  override def trigger = AllRequirements

  val autoImport = Import

  import SbtWeb.autoImport.WebKeys._
  import SbtJsTask.autoImport.JsTaskKeys._
  import autoImport.CoffeescriptKeys._

  val coffeeScriptUnscopedSettings = Seq(

    jsOptions := JsObject(
      "bare" -> JsBoolean(bare.value),
      "sourceMap" -> JsBoolean(sourceMap.value)
    ).toString()
  )

  override def projectSettings = Seq(
    bare := false,
    sourceMap := true

  ) ++ inTask(coffeescript)(
    SbtJsTask.jsTaskSpecificUnscopedSettings ++
      inConfig(Assets)(coffeeScriptUnscopedSettings) ++
      inConfig(TestAssets)(coffeeScriptUnscopedSettings) ++
      Seq(
        moduleName := "coffeescript",
        shellFile := "coffee.js",
        fileFilter := GlobFilter("*.coffee") | GlobFilter("*.litcoffee"),

        taskMessage in Assets := "CoffeeScript compiling",
        taskMessage in TestAssets := "CoffeeScript test compiling"
      )
  ) ++ SbtJsTask.addJsSourceFileTasks(coffeescript) ++ Seq(
    coffeescript in Assets := (coffeescript in Assets).dependsOn(webModules in Assets).value,
    coffeescript in TestAssets := (coffeescript in TestAssets).dependsOn(webModules in TestAssets).value
  )

}

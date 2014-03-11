package com.typesafe.sbt.coffeescript

import sbt._
import sbt.Keys._
import spray.json._
import com.typesafe.sbt.jse.SbtJsTaskPlugin
import com.typesafe.sbt.web.SbtWebPlugin

object SbtCoffeeScriptPlugin extends SbtJsTaskPlugin {

  object CoffeescriptKeys {
    val coffeescript = TaskKey[Seq[File]]("coffeescript", "Invoke the CoffeeScript compiler.")

    val bare = SettingKey[Boolean]("coffeescript-bare", "Compiles JavaScript that isn't wrapped in a function.")
    val sourceMap = SettingKey[Boolean]("coffeescript-source-map", "Outputs a v3 sourcemap.")
  }

  import SbtWebPlugin.WebKeys._
  import SbtJsTaskPlugin.JsTaskKeys._
  import CoffeescriptKeys._

  val coffeeScriptUnscopedSettings = Seq(

    jsOptions := JsObject(
      "bare" -> JsBoolean(bare.value),
      "sourceMap" -> JsBoolean(sourceMap.value)
    ).toString()
  )

  val coffeeScriptSettings = Seq(
    bare := false,
    sourceMap := true

  ) ++ inTask(coffeescript)(
    jsTaskSpecificUnscopedSettings ++
      inConfig(Assets)(coffeeScriptUnscopedSettings) ++
      inConfig(TestAssets)(coffeeScriptUnscopedSettings) ++
      Seq(
        moduleName := "coffeescript",
        shellFile := "coffee.js",
        fileFilter := GlobFilter("*.coffee") | GlobFilter("*.litcoffee"),

        taskMessage in Assets := "CoffeeScript compiling",
        taskMessage in TestAssets := "CoffeeScript test compiling"
      )
  ) ++ addJsSourceFileTasks(coffeescript) ++ Seq(
    coffeescript in Assets := (coffeescript in Assets).dependsOn(webModules in Assets).value,
    coffeescript in TestAssets := (coffeescript in TestAssets).dependsOn(webModules in TestAssets).value
  )

}
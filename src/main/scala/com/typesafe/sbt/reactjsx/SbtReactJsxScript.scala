package com.typesafe.sbt.reactjsx

import com.typesafe.sbt.jse.SbtJsTask
import sbt._
import com.typesafe.sbt.web.SbtWeb
import spray.json.{JsBoolean, JsObject}
import sbt.Keys._

object Import {

  object ReactJsxKeys {
    val reactjsx = TaskKey[Seq[File]]("reactjsx", "Invoke the JSX transormer.")

    val sourceMap = SettingKey[Boolean]("reactjsx-source-map", "Outputs a v3 sourcemap.")
  }

}

object SbtReactJsxScript extends AutoPlugin {

  override def requires = SbtJsTask

  override def trigger = AllRequirements

  val autoImport = Import

  import SbtWeb.autoImport._
  import WebKeys._
  import SbtJsTask.autoImport.JsTaskKeys._
  import autoImport.ReactJsxKeys._

  val reactJsxUnscopedSettings = Seq(

    jsOptions := JsObject(
      "sourceMap" -> JsBoolean(sourceMap.value)
    ).toString()
  )

  override def projectSettings = Seq(
    sourceMap := true

  ) ++ inTask(reactjsx)(
    SbtJsTask.jsTaskSpecificUnscopedSettings ++
      inConfig(Assets)(reactJsxUnscopedSettings) ++
      inConfig(TestAssets)(reactJsxUnscopedSettings) ++
      Seq(
        moduleName := "reactjsx",
        shellFile := "jsx.js",
        fileFilter := GlobFilter("*.jsx"),

        taskMessage in Assets := "React JSX transformation",
        taskMessage in TestAssets := "React JSX test transformation"
      )
  ) ++ SbtJsTask.addJsSourceFileTasks(reactjsx) ++ Seq(
    reactjsx in Assets := (reactjsx in Assets).dependsOn(webModules in Assets).value,
    reactjsx in TestAssets := (reactjsx in TestAssets).dependsOn(webModules in TestAssets).value
  )

}

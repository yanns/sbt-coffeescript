/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package com.typesafe.sbt.coffeescript

import akka.actor.{ ActorRefFactory, ActorSystem }
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.jse.{Rhino, CommonNode, Node, Engine}
import com.typesafe.jse.Engine.JsExecutionResult
import com.typesafe.js.sbt.WebPlugin.WebKeys
import com.typesafe.jse.sbt.JsEnginePlugin.JsEngineKeys
import com.typesafe.jse.sbt.JsEnginePlugin
import java.io.File
import org.apache.commons.io.{ FileUtils, IOUtils }
import sbt._
import sbt.Keys._
import scala.collection.immutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import spray.json._
import xsbti.{ CompileFailed, Maybe, Position, Problem, Severity }

final case class CoffeeScriptPluginException(message: String) extends Exception(message)
class CoffeeScriptCompileFailed(override val problems: Array[Problem])
  extends CompileFailed
  with FeedbackProvidedException {
  override val arguments: Array[String] = Array.empty
}


object CoffeeScriptPlugin extends Plugin {

  import CoffeeScriptEngine._

  private def cs(setting: String) = s"coffee-script-$setting"

  object CoffeeScriptKeys {
    val compile = TaskKey[Unit]("coffee-script", "Compile CoffeeScript sources into JavaScript.")
    val sourceFilter = SettingKey[FileFilter](cs("filter"), "A filter matching CoffeeScript and literate CoffeeScript sources.")
    val outputDirectory = SettingKey[File](cs("output-directory"), "The output directory for compiled JavaScript files and source maps.")
    val literateFilter = SettingKey[NameFilter](cs("literate-filter"), "A filter to identify literate CoffeeScript files.")
    val bare = SettingKey[Boolean](cs("bare"), "Compiles JavaScript that isn't wrapped in a function.")
    val sourceMaps = SettingKey[Boolean](cs("source-maps"), "Generate source map files.")
    val compileArgs = TaskKey[Seq[CompileArgs]](cs("compile-args"), "CompileArgs instructions for the CoffeeScript compiler.")
  }

  // FIXME: Load from disk
  private val singletonWorkCache = new WorkCache[CompileArgs]()

  /**
   * Use this to import CoffeeScript settings into a specific scope,
   * e.g. `Project.inConfig(WebKeys.Assets)(scopedSettings)`. These settings intentionally
   * have no dependency on sbt-web settings or directories, making it possible to use these
   * settings for non-web CoffeeScript compilation.
   */
  def scopedSettings: Seq[Setting[_]] = Seq(
    includeFilter in CoffeeScriptKeys.compile := GlobFilter("*.coffee") | GlobFilter("*.litcoffee"),
    excludeFilter in CoffeeScriptKeys.compile := NothingFilter,
    sourceDirectories in CoffeeScriptKeys.compile := sourceDirectories.value,
    sources in CoffeeScriptKeys.compile := {
      val dirs = (sourceDirectories in CoffeeScriptKeys.compile).value
      val include = (includeFilter in CoffeeScriptKeys.compile).value
      val exclude = (excludeFilter in CoffeeScriptKeys.compile).value
      (dirs ** (include -- exclude)).get
    },
    CoffeeScriptKeys.sourceMaps := true,
    CoffeeScriptKeys.bare := false,
    CoffeeScriptKeys.literateFilter := GlobFilter("*.litcoffee"),
    CoffeeScriptKeys.compileArgs := {
      val literateFilter = CoffeeScriptKeys.literateFilter.value
      val sourceMaps = CoffeeScriptKeys.sourceMaps.value

      // http://www.scala-sbt.org/release/docs/Detailed-Topics/Mapping-Files.html
      val inputSources = (sources in CoffeeScriptKeys.compile).value.get
      val inputDirectories = (sourceDirectories in CoffeeScriptKeys.compile).value.get
      val outputDirectory = CoffeeScriptKeys.outputDirectory.value
      for {
        (csFile, rebasedFile) <- inputSources x rebase(inputDirectories, outputDirectory)
      } yield {
        val parent = rebasedFile.getParent
        val name = rebasedFile.getName
        val baseName = {
          val dotIndex = name.lastIndexOf('.')
          if (dotIndex == -1) name else name.substring(0, dotIndex)
        }
        val jsFileName = baseName + ".js"
        val jsFile = new File(parent, jsFileName)
        val mapFileName = jsFileName + ".map"
        val mapFile = new File(parent, mapFileName)

        val sourceMapOpts = if (sourceMaps) {
          Some(SourceMapOptions(
            sourceMapOutputFile = mapFile,
            sourceMapRef = mapFileName,
            javaScriptFileName = jsFileName,
            coffeeScriptRootRef = "",
            coffeeScriptPathRefs = List(name)
          ))
        } else None
        CompileArgs(
          coffeeScriptInputFile = csFile,
          javaScriptOutputFile = jsFile,
          sourceMapOpts = sourceMapOpts,
          bare = CoffeeScriptKeys.bare.value,
          literate = literateFilter.accept(name)
        )
      }
    },
    CoffeeScriptKeys.compile := {

      val neededCompiles = WorkRunner.neededWork(singletonWorkCache, CoffeeScriptKeys.compileArgs.value.to[Vector])
      val sourceCount = neededCompiles.length

      if (sourceCount > 0) {
        val log = streams.value.log
        val sourceString = if (sourceCount == 1) "source" else "sources"
        log.info(s"Compiling ${sourceCount} CoffeeScript ${sourceString}...")

        val webReporter = WebKeys.reporter.value
        webReporter.reset()

        WorkRunner.runAndCache(singletonWorkCache, neededCompiles) { compilation =>

          // TODO: Think about lifecycle (start/stop) of ActorSystem
          implicit val jseSystem = JsEnginePlugin.jseSystem
          implicit val jseTimeout = JsEnginePlugin.jseTimeout

          compileFile(compilation) match {
            case CompileSuccess =>
              val inOutSet = Set(compilation.coffeeScriptInputFile, compilation.javaScriptOutputFile)
              val sourceMapSet = compilation.sourceMapOpts.map(_.sourceMapOutputFile).to[Set]
              Some(inOutSet ++ sourceMapSet)
            case err: CodeError =>
              val pos = new Position {
                def line: Maybe[Integer] = Maybe.just(err.lineNumber)
                def offset: Maybe[Integer] = Maybe.just(err.lineOffset)
                def lineContent: String = err.lineContent
                def pointer: Maybe[Integer] = offset
                def pointerSpace: Maybe[String] = Maybe.just(
                  lineContent.take(pointer.get).map {
                    case '\t' => '\t'
                    case x => ' '
                  })
                def sourceFile: Maybe[File] = Maybe.just(compilation.coffeeScriptInputFile)
                def sourcePath: Maybe[String] = Maybe.just(compilation.coffeeScriptInputFile.getPath)
              }
              webReporter.log(pos, err.message, Severity.Error)
              None
            case err: GenericError =>
              throw CoffeeScriptPluginException(err.message)
          }
        }

        webReporter.printSummary()
        if (webReporter.hasErrors) {
          throw new CoffeeScriptCompileFailed(Array(/* FIXME: Add problems */))
        }
      }
    },
    compile := {
      val compileAnalysis = compile.value
      val unused = CoffeeScriptKeys.compile.value
      compileAnalysis
    }
  )

    // TODO: Put in sbt-web
  object TodoWeb {
    def webSettings: Seq[Setting[_]] = Seq[Setting[_]](
      compile in Compile := (compile in Compile).value ++ (compile in WebKeys.Assets).value,
      compile in Test := (compile in Test).value ++ (compile in WebKeys.TestAssets).value
    ) ++ Project.inConfig(WebKeys.Assets)(scopedSettings) ++ Project.inConfig(WebKeys.TestAssets)(scopedSettings)

    def scopedSettings: Seq[Setting[_]] = Seq(
      compile := inc.Analysis.Empty,
      sourceDirectories := unmanagedSourceDirectories.value
    )
  }

  def coffeeScriptSettings: Seq[Setting[_]] =
    TodoWeb.webSettings ++
    Seq[Setting[_]](
      CoffeeScriptKeys.compile in Compile := (CoffeeScriptKeys.compile in WebKeys.Assets).value,
      CoffeeScriptKeys.compile in Test := (CoffeeScriptKeys.compile in WebKeys.TestAssets).value,
      CoffeeScriptKeys.outputDirectory in WebKeys.Assets := (resourceManaged in WebKeys.Assets).value,
      CoffeeScriptKeys.outputDirectory in WebKeys.TestAssets := (resourceManaged in WebKeys.TestAssets).value,
      includeFilter in (WebKeys.TestAssets, CoffeeScriptKeys.compile) := GlobFilter("*Test.coffee") | GlobFilter("*Test.litcoffee"),
      excludeFilter in (WebKeys.Assets, CoffeeScriptKeys.compile) := (includeFilter in (WebKeys.TestAssets, CoffeeScriptKeys.compile)).value
    ) ++
    Project.inConfig(WebKeys.Assets)(scopedSettings) ++
    Project.inConfig(WebKeys.TestAssets)(scopedSettings)

}
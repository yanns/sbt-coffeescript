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
import xsbti.{ Maybe, Position, Severity }

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
  private val singletonRawCache = new WorkCache[CompileArgs]()

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
        (inFile, outFile) <- inputSources x rebase(inputDirectories, outputDirectory)
      } yield {
        val parent = outFile.getParent
        val name = outFile.getName
        val baseName = {
          val dotIndex = name.lastIndexOf('.')
          if (dotIndex == -1) name else name.substring(0, dotIndex)
        }
        CompileArgs(
          input = inFile,
          output = new File(parent, baseName + ".js"),
          sourceMap = Some(new File(parent, baseName + ".map")),
          bare = CoffeeScriptKeys.bare.value,
          literate = literateFilter.accept(name)
        )
      }
    },
    CoffeeScriptKeys.compile := {

      val flatWorkCache = {
        val rawCache = singletonRawCache
        val workDef = new FlatWorkDef[CompileArgs] {
          private val requestedWork = CoffeeScriptKeys.compileArgs.value.to[Vector]
          def allPossibleWork = requestedWork
          def fileDepsForWork(c: CompileArgs): Set[File] = {
            requestedWork.find(_ == c).map((c: CompileArgs) => Set(c.input, c.output)).get
          }
        }
        new FlatWorkCache(rawCache, workDef)
      }

      val compileArgsToDo = flatWorkCache.workToDo
      val sourceCount = compileArgsToDo.length
      if (sourceCount > 0) {

        val log = streams.value.log
        val sourceString = if (sourceCount == 1) "source" else "sources"
        log.info(s"Compiling ${sourceCount} CoffeeScript ${sourceString}...")

        val webReporter = WebKeys.reporter.value
        webReporter.reset()

        // TODO: Think about lifecycle (start/stop) of ActorSystem
        implicit val jseSystem = JsEnginePlugin.jseSystem
        implicit val jseTimeout = JsEnginePlugin.jseTimeout

        for (compilation <- compileArgsToDo) {

          compileFile(compilation) match {
            case CompileSuccess =>
              flatWorkCache.recordWorkDone(compilation)
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
                def sourceFile: Maybe[File] = Maybe.just(compilation.input)
                def sourcePath: Maybe[String] = Maybe.just(compilation.input.getPath)
              }
              webReporter.log(pos, err.message, Severity.Error)
            case err: GenericError =>
              throw new RuntimeException(err.message) // FIXME: Better exception type
          }
        }

        webReporter.printSummary()
        if (webReporter.hasErrors) {
          throw new RuntimeException("CoffeeScript failure") // TODO: Proper exception
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
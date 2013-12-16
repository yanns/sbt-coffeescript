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

object CoffeeScriptEngine {

  final case class Compilation(
    input: File,
    output: File,
    bare: Boolean
  )

  sealed trait CompileResult
  final case object CompileSuccess extends CompileResult
  final case class GenericError(message: String) extends CompileResult
  final case class CodeError(
    message: String,
    lineContent: String,
    lineNumber: Int,
    lineOffset: Int
  ) extends CompileResult

  // TODO: Share a single Engine instance between compilations
  def compileFile(opts: Compilation)(implicit actorRefFactory: ActorRefFactory, timeout: Timeout): Future[CompileResult] = {
    val engine = actorRefFactory.actorOf(Node.props()) // FIXME: There was a name clash with "engine"

    def generateDriverFile(): File = {
      val file = File.createTempFile("sbt-coffeescript-driver", ".js") // TODO: Use SBT temp directory?
      file.deleteOnExit()

      val fileStream = FileUtils.openOutputStream(file)
      try {

        def writeResource(resName: String) {
          val cl = this.getClass.getClassLoader // TODO: Make ClassLoader switchable
          val resStream = cl.getResourceAsStream(resName)
          try {
            IOUtils.copy(resStream, fileStream)
          } finally {
            resStream.close()
          }
        }
        writeResource("com/typesafe/sbt/coffeescript/driver.js")

      } finally {
        fileStream.close()
      }

      file
    }
    val f = generateDriverFile()

    import DefaultJsonProtocol._

    val arg = JsObject(
      "input" -> JsString(opts.input.getPath),
      "output" -> JsString(opts.output.getPath),
      "bare" -> JsBoolean(opts.bare)
    ).compactPrint

    def decodeJsonResult(result: JsObject): CompileResult = {
      result.fields("result").asInstanceOf[JsString].value match {
        case "CompileSuccess" =>
          CompileSuccess
        case "CodeError" =>
          val message = result.fields("message").asInstanceOf[JsString].value
          val lineCode = result.fields("lineContent").asInstanceOf[JsString].value
          val lineNumber = result.fields("lineNumber").asInstanceOf[JsNumber].value.intValue
          val lineOffset = result.fields("lineOffset").asInstanceOf[JsNumber].value.intValue
          CodeError(message, lineCode, lineNumber, lineOffset)
        case "GenericError" =>
          GenericError(result.fields("message").asInstanceOf[JsString].value)
        case _ =>
          throw new RuntimeException(s"Unknown JSON result running CoffeeScript driver: $result") // FIXME: Better Exception type
      }
    }

    import actorRefFactory.dispatcher
    (engine ? Engine.ExecuteJs(f, immutable.Seq(arg))).mapTo[JsExecutionResult].map {
      case JsExecutionResult(0, stdoutBytes, _) =>
        val jsonResult = (new String(stdoutBytes.toArray, "utf-8")).asJson.asInstanceOf[JsObject]
        decodeJsonResult(jsonResult)
      case result =>
        val exitValue = result.exitValue
        val stdout = new String(result.output.toArray, "utf-8")
        val stderr = new String(result.error.toArray, "utf-8")
        throw new RuntimeException(s"Unexpected result running CoffeeScript driver: exit value: $exitValue, stdout: $stdout, stderr: $stderr")
    }

  }

  def main(args: Array[String]) {
    implicit val system = ActorSystem("jse-system")
    implicit val timeout = Timeout(5.seconds)
    try {
      val resultFuture = compileFile(Compilation(
        input = new File("/p/play/js/sbt-coffeescript/src/main/resources/com/typesafe/sbt/coffeescript/test.coffee"),
        output = new File("/p/play/js/sbt-coffeescript/target/test.js"),
        bare = false
      ))
      val result = Await.result(resultFuture, 5.seconds)
      println(result)
    } finally {
      println("Running shutdown")
      system.shutdown()
      println("Waiting for termination")
      system.awaitTermination()
      println("Terminated")
    }
  }
}

object CoffeeScriptPlugin extends Plugin {

  import CoffeeScriptEngine._

  private def cs(setting: String) = s"coffee-script-$setting"

  object CoffeeScriptKeys {
    val compile = TaskKey[Unit]("coffee-script", "Compile CoffeeScript sources into JavaScript.")
    val sourceFilter = SettingKey[FileFilter](cs("filter"), "A filter matching CoffeeScript sources.")
    val outputDirectory = SettingKey[File](cs("output-directory"), "The directory to output compiled JavaScript files.")

    // http://coffeescript.org/#usage
    val compilations = TaskKey[Seq[Compilation]](cs("compilations"), "Compilation instructions for the CoffeeScript compiler.")
    //val join = SettingKey[File](cs("join"), "If specified, joins.")
    //val map = SettingKey[Boolean](cs("map"), "Generate source maps")
    val bare = SettingKey[Boolean](cs("bare"), "Compiles JavaScript that isn't wrapped in a function")
    //val literate = SettingKey[Boolean](cs("literate"), "If true, force the code to be parsed as Literate CoffeeScript. Not needed if files have a .litcoffee extension.")
    //val tokens = 
  }

  // FIXME: Load from disk
  private val singletonRawCache = new WorkCache[Compilation]()

  def scopedSettings: Seq[Setting[_]] = Seq(
    CoffeeScriptKeys.bare := false,
    includeFilter in CoffeeScriptKeys.compile := GlobFilter("*.coffee") | GlobFilter("*.litcoffee"),
    excludeFilter in CoffeeScriptKeys.compile := NothingFilter,
    sourceDirectories in CoffeeScriptKeys.compile := sourceDirectories.value,
    sources in CoffeeScriptKeys.compile := {
      val dirs = (sourceDirectories in CoffeeScriptKeys.compile).value
      val include = (includeFilter in CoffeeScriptKeys.compile).value
      val exclude = (excludeFilter in CoffeeScriptKeys.compile).value
      (dirs ** (include -- exclude)).get
    },
    CoffeeScriptKeys.compilations := {
      // http://www.scala-sbt.org/release/docs/Detailed-Topics/Mapping-Files.html
      val inputSources = (sources in CoffeeScriptKeys.compile).value.get
      val inputDirectories = (sourceDirectories in CoffeeScriptKeys.compile).value.get
      val outputDirectory = CoffeeScriptKeys.outputDirectory.value
      for {
        (inFile, outFile) <- inputSources x rebase(inputDirectories, outputDirectory)
      } yield {
        //println(inFile, outFile)
        val parent = outFile.getParent
        val name = outFile.getName
        val dedotted = {
          val dotIndex = name.lastIndexOf('.')
          if (dotIndex == -1) name else name.substring(0, dotIndex)
        }
        Compilation(
          input = inFile,
          output = new File(parent, dedotted + ".js"),
          bare = CoffeeScriptKeys.bare.value
        )
      }
    },
    CoffeeScriptKeys.compile := {

      val flatWorkCache = {
        val rawCache = singletonRawCache
        val workDef = new FlatWorkDef[Compilation] {
          private val requestedWork = CoffeeScriptKeys.compilations.value.to[Vector]
          def allPossibleWork = requestedWork
          def fileDepsForWork(c: Compilation): Set[File] = {
            requestedWork.find(_ == c).map((c: Compilation) => Set(c.input, c.output)).get
          }
        }
        new FlatWorkCache(rawCache, workDef)
      }

      // TODO: Think about lifecycle (start/stop) of ActorSystem
      implicit val jseSystem = JsEnginePlugin.jseSystem
      implicit val jseTimeout = JsEnginePlugin.jseTimeout

      val compilationsToDo = flatWorkCache.workToDo
      val sourceCount = compilationsToDo.length
      if (sourceCount > 0) {

        val log = streams.value.log
        val sourceString = if (sourceCount == 1) "source" else "sources"
        log.info(s"Compiling ${sourceCount} CoffeeScript ${sourceString}...")

        val webReporter = WebKeys.reporter.value
        webReporter.reset()

        for (compilation <- compilationsToDo) {

          val resultFuture = compileFile(compilation)

          Await.result(resultFuture, jseTimeout.duration) match {
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
      // sourceManaged := resourceManaged.value,
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
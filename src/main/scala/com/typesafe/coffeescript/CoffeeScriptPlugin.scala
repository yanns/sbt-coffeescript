/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package com.typesafe.sbt.coffeescript

import com.typesafe.js.sbt.WebPlugin.WebKeys
import com.typesafe.jse.Engine.JsExecutionResult
import com.typesafe.jse.sbt.JsEnginePlugin.JsEngineKeys
import com.typesafe.jse.sbt.JsEnginePlugin
import sbt._
import sbt.Keys._
import scala.concurrent.Await
import scala.util.{ Failure, Success, Try }
import spray.json._
import xsbti.{ Maybe, Position, Severity }

object CoffeeScriptEngine {
  import akka.actor.{ ActorRefFactory, ActorSystem }
  import akka.pattern.ask
  import akka.util.Timeout
  import com.typesafe.jse.Engine.JsExecutionResult
  import com.typesafe.jse.{Rhino, CommonNode, Node, Engine}
  import java.io.File
  import scala.collection.immutable
  import scala.concurrent.{ Await, Future }
  import scala.concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global

  sealed trait CompileResult
  case object CompileSuccess extends CompileResult
  case class GenericError(message: String) extends CompileResult
  case class CodeError(
    message: String,
    lineContent: String,
    lineNumber: Int,
    lineOffset: Int
  ) extends CompileResult

  def compile(input: File, output: File)(implicit actorRefFactory: ActorRefFactory, timeout: Timeout): Future[CompileResult] = {
    val engine = actorRefFactory.actorOf(Node.props()) // FIXME: There was a name clash with "engine"

    def generateDriverFile(): File = {
      import org.apache.commons.io._
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
      "input" -> JsString(input.getPath),
      "output" -> JsString(output.getPath)
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
      val resultFuture = compile(
        input = new File("/p/play/js/sbt-coffeescript/src/main/resources/com/typesafe/sbt/coffeescript/test.coffee"),
        output = new File("/p/play/js/sbt-coffeescript/target/test.js"))
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

  private def cs(setting: String) = s"coffee-script-$setting"

  object CoffeeScriptKeys {
    val compile = TaskKey[Unit](cs("compile"), "Compile CoffeeScript sources into JavaScript.")
    val sourceFilter = SettingKey[FileFilter](cs("filter"), "A filter matching CoffeeScript sources.")

    // http://coffeescript.org/#usage
    val mappings = SettingKey[Seq[(File,File)]](cs("mappings"), "Mappings from CoffeeScript source files to compiled JavaScript files.")
    //val join = SettingKey[File](cs("join"), "If specified, joins.")
    //val map = SettingKey[Boolean](cs("map"), "Generate source maps")
    //val bare = SettingKey[Boolean](cs("bare"), "Compiles JavaScript that isn't wrapped in a function")
    //val literate = SettingKey[Boolean](cs("literate"), "If true, force the code to be parsed as Literate CoffeeScript. Not needed if files have a .litcoffee extension.")
    //val tokens = 
  }

  private def scopedSettings(webConfig: Configuration, nonWebConfig: Configuration): Seq[Setting[_]] = Seq(
    (CoffeeScriptKeys.mappings in webConfig) := {
      // http://www.scala-sbt.org/release/docs/Detailed-Topics/Mapping-Files.html
      val sourceDir = (sourceDirectory in webConfig).value
      val sources = (sourceDir ** (CoffeeScriptKeys.sourceFilter in webConfig).value).get
      val outputDir = (resourceManaged in webConfig).value
      sources x rebase(sourceDir, outputDir) map {
        case (inFile, outFile) =>
          println(inFile, outFile)
          val parent = outFile.getParent
          val name = outFile.getName
          val dedotted = {
            val dotIndex = name.lastIndexOf('.')
            if (dotIndex == -1) name else name.substring(0, dotIndex)
          }
          (inFile, new File(parent, dedotted + ".js"))
      }
    },
    (CoffeeScriptKeys.compile in webConfig) := {

      // TODO: Think about lifecycle (start/stop) of ActorSystem
      implicit val jseSystem = JsEnginePlugin.jseSystem
      implicit val jseTimeout = JsEnginePlugin.jseTimeout

      val mappings = (CoffeeScriptKeys.mappings in webConfig).value

      val log = streams.value.log
      val sourceCount = mappings.length
      if (sourceCount > 0) {
        val sourceString = if (sourceCount == 1) "source" else "sources"
        log.info(s"Compiling ${sourceCount} CoffeeScript ${sourceString}...")
        val webReporter = WebKeys.reporter.value
        webReporter.reset()
        // FIXME: Proper scoping of mappings

        for ((input, output) <- mappings) {

          val resultFuture = CoffeeScriptEngine.compile(input, output)

          import CoffeeScriptEngine._

          Await.result(resultFuture, jseTimeout.duration) match {
            case CompileSuccess =>
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
                def sourceFile: Maybe[File] = Maybe.just(input)
                def sourcePath: Maybe[String] = Maybe.just(input.getPath)
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
    copyResources in webConfig <<= (copyResources in webConfig).dependsOn(CoffeeScriptKeys.compile in webConfig),
    // FIXME: Add dependency through an intermediate task in sbt-web?
    compile in nonWebConfig <<= (compile in nonWebConfig).dependsOn(CoffeeScriptKeys.compile in webConfig)

  )

  def coffeeScriptSettings: Seq[Setting[_]] = Seq(
    CoffeeScriptKeys.sourceFilter := GlobFilter("*.coffee") | GlobFilter("*.litcoffee")
  ) ++ scopedSettings(WebKeys.Assets, Compile) ++ scopedSettings(WebKeys.TestAssets, Test)

}
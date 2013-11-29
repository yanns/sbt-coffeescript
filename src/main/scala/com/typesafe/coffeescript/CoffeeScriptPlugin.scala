/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package com.typesafe.sbt.coffeescript

import com.typesafe.js.sbt.WebPlugin.WebKeys
import com.typesafe.jse.sbt.JsEnginePlugin.JsEngineKeys
import com.typesafe.jse.sbt.JsEnginePlugin
import sbt._
import sbt.Keys._
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

  // import akka.Actor

  // class CoffeeScriptActor extends Actor {

  // }

  sealed trait CompileResult
  case object CompileSuccess extends CompileResult
  case class GenericError(message: String) extends CompileResult
  case class CodeError(
    message: String,
    lineContent: String,
    lineNumber: Int,
    lineOffset: Int
  ) extends CompileResult
  // TODO: Other types of error, e.g. missing file

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

    (engine ? Engine.ExecuteJs(f, immutable.Seq(arg))).mapTo[JsExecutionResult].map {
      case JsExecutionResult(0, _, _) =>
        CompileSuccess
      case JsExecutionResult(1, _, stderrBytes) =>
        GenericError(new String(stderrBytes.toArray, "utf-8"))
      case JsExecutionResult(2, _, stderrBytes) =>
        val errObj = (new String(stderrBytes.toArray, "utf-8")).asJson.asInstanceOf[JsObject]
        val message = errObj.fields("message").asInstanceOf[JsString].value
        val lineCode = errObj.fields("lineContent").asInstanceOf[JsString].value
        val lineNumber = errObj.fields("lineNumber").asInstanceOf[JsNumber].value.intValue
        val lineOffset = errObj.fields("lineOffset").asInstanceOf[JsNumber].value.intValue
        CodeError(message, lineCode, lineNumber, lineOffset)
      case unknown => throw new RuntimeException(s"Unknown JsExecutionResult: $unknown") // TODO: Exception type
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
      //println(result.exitCode)
      //println(s"out: "+ new String(result.output.toArray, "utf-8"))
      //println(s"err: "+ new String(result.error.toArray, "utf-8"))
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

  private def prefixed(setting: String) = s"coffeescript-$setting"
  //val engineType = JsEngineKeys.engineType
  //val parallelism = JsEngineKeys.parallelism
  //val sources = SettingKey[Seq[File]](prefixed("sources"), "The CoffeeScript source files to compile.")
  //val output = SettingKey[File](prefixed("output"), "The directory to write compiled JavaScript files into.")

  object CoffeeScriptKeys {
    val coffeeScript = TaskKey[Unit]("coffeescript", "Compile CoffeeScript sources into JavaScript.")
    val coffeeScriptFilter = SettingKey[FileFilter](prefixed("filter"), "A filter matching CoffeeScript sources.")
    //val coffeeScriptSources = 
    // http://coffeescript.org/#usage
    val mappings = SettingKey[Seq[(File,File)]](prefixed("mappings"), "Mappings from CoffeeScript source files to compiled JavaScript files.")
    //val join = SettingKey[File](prefixed("join"), "If specified, joins.")
    //val map = SettingKey[Boolean](prefixed("map"), "Generate source maps")
    //val bare = SettingKey[Boolean](prefixed("bare"), "Compiles JavaScript that isn't wrapped in a function")
    //val literate = SettingKey[Boolean](prefixed("literate"), "If true, force the code to be parsed as Literate CoffeeScript. Not needed if files have a .litcoffee extension.")
    //val tokens = 
  }

  import CoffeeScriptKeys._

  // val coffeeScriptSettings = Seq(

  // )

  def coffeeScriptSettings: Seq[Setting[_]] = Seq(
    coffeeScriptFilter := GlobFilter("*.coffee") | GlobFilter("*.litcoffee"),
    //engineType := EngineType.JsEngineKeys.Node,
    // sources := {
    //   val baseDir = (sourceDirectory in Assets).value / "coffeescript"
    //   (baseDir / "**.coffee") ++ (baseDir / "**.litcoffee")
    // },
    // outputDirectory := (sourceManaged in Assets).value / "javascript"
    (mappings in WebKeys.Assets) := {
      // http://www.scala-sbt.org/release/docs/Detailed-Topics/Mapping-Files.html
      val sourceDir = (sourceDirectory in WebKeys.Assets).value
      val sources = (sourceDir ** coffeeScriptFilter.value).get
      val outputDir = (resourceManaged in WebKeys.Assets).value
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
    coffeeScript := {
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
      val webReporter = WebKeys.reporter.value
      webReporter.reset()
      for ((input, output) <- (mappings in WebKeys.Assets).value) { // FIXME: Proper scoping
        implicit val jseSystem = JsEnginePlugin.jseSystem
        implicit val jseTimeout = JsEnginePlugin.jseTimeout
        //implicit val system = ActorSystem("jse-system")
        //implicit val timeout = Timeout(5.seconds)
        try {
          //println(s"Compiling $input to $output.")
          val resultFuture = CoffeeScriptEngine.compile(input, output)
          import CoffeeScriptEngine._

          Try(Await.result(resultFuture, 5.seconds)) match {
            case Success(CompileSuccess) =>
            case Success(err: CodeError) =>
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
            case Success(err: GenericError) =>
              throw new RuntimeException(err.message) // FIXME: Better exception type
          }
          //println(result.exitValue)
          //println(s"out: "+ new String(result.output.toArray, "utf-8"))
          //println(s"err: "+ new String(result.error.toArray, "utf-8"))
        } finally {
          //println("Running shutdown")
          //system.shutdown()
          //println("Waiting for termination")
          //system.awaitTermination()
          //println("Terminated")
        }
      }

      webReporter.printSummary()
      if (webReporter.hasErrors) {
        throw new RuntimeException("CoffeeScript failure") // TODO: Proper exception
      }
    }
  )


}
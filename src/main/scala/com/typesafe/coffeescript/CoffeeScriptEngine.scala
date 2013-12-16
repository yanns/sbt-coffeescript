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

  final case class CompileArgs(
    input: File,
    output: File,
    bare: Boolean,
    literate: Boolean
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
  def compileFile(opts: CompileArgs)(implicit actorRefFactory: ActorRefFactory, timeout: Timeout): CompileResult = {
    Await.result(compileFileFuture(opts), timeout.duration)
  }

  def compileFileFuture(opts: CompileArgs)(implicit actorRefFactory: ActorRefFactory, timeout: Timeout): Future[CompileResult] = {
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
      "bare" -> JsBoolean(opts.bare),
      "literate" -> JsBoolean(opts.bare)
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
      val result = compileFile(CompileArgs(
        input = new File("/p/play/js/sbt-coffeescript/src/main/resources/com/typesafe/sbt/coffeescript/test.coffee"),
        output = new File("/p/play/js/sbt-coffeescript/target/test.js"),
        bare = false,
        literate = false
      ))
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

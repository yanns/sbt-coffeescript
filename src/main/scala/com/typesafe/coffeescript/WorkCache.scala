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

class WorkCache[P](/* cacheId: String, paramSerializer: Serializer[P]*/) {
  import WorkCache._
  private var content: Map[P, Record[P]] = Map.empty
  def allParams: Set[P] = content.keySet
  def getRecord(params: P): Option[Record[P]] = {
    content.get(params)
  }
  def putRecord(params: P, record: Record[P]) = {
    content = content + ((params, record))
  }
  def removeRecord(params: P) = {
    content = content - params
  }
}

object WorkCache {
  final case class FileContent(file: File, sha1IfExists: Option[String])

  def fileContent(file: File): FileContent = {
    val sha1IfExists = if (file.exists) {
      val bytes = Hash(file)
      val string = new String(bytes, "US-ASCII")
      Some(string)
    } else None
    FileContent(file, sha1IfExists)
  }

  final case class Record[P](fileDeps: immutable.Set[FileContent])
}

object WorkRunner {
  def neededWork[P](cache: WorkCache[P], possibleWork: immutable.Seq[P]) = {
    for (p <- cache.allParams) {
      if (!possibleWork.contains(p)) {
        //println(s"Clearing entry: $p")
        cache.removeRecord(p)
      }
    }
    // TODO: Save cache here?

    // Filter allPossibleWork into only work that we need to do
    possibleWork.filter { p =>
      cache.getRecord(p) match {
        case None =>
          // Not in cache, this is new work that we need to do
          //println(s"Not in cache: $p")
          true
        case Some(record) =>
          // Check that cached effects are up to date
          record.fileDeps.foldLeft(false) {
            case (true, _) => true // We've already found a changed file, no need to check other files
            case (false, recordedContent) => {
              val currentContent = WorkCache.fileContent(recordedContent.file)
              val fileChanged = currentContent != recordedContent
              fileChanged
            }
          }
      }
    }
  }
  def runAndCache[P](cache: WorkCache[P], workList: immutable.Seq[P])(doWork: P => Option[Set[File]]) = {
    for (p <- workList) {
      for (fileDeps <- doWork(p)) {
        val fileContents = fileDeps.map(WorkCache.fileContent)
        val record = WorkCache.Record[P](fileContents)
        cache.putRecord(p, record)
      }
    }
    // TODO: Save cache here.
  }
}

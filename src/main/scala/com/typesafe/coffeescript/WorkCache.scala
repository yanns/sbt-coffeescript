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

  final case class Record[P](
    workDeps: immutable.Set[P],
    fileDeps: immutable.Set[FileContent]
  )
}

trait FlatWorkDef[P] {
  def allPossibleWork: immutable.Seq[P]
  def fileDepsForWork(params: P): Set[File]
}

class FlatWorkCache[P](rawCache: WorkCache[P], workDef: FlatWorkDef[P]) {
  def workToDo: immutable.Seq[P] = {
    val possibleWork = workDef.allPossibleWork
    //println(s"possibleWork: $possibleWork")
    //println(s"rawCache.allParams: ${rawCache.allParams}")

    // Remove cache entries which we no longer need
    for (p <- rawCache.allParams) {
      if (!possibleWork.contains(p)) {
        //println(s"Clearing entry: $p")
        rawCache.removeRecord(p)
      }
    }

    // Filter allPossibleWork into only work that we need to do
    possibleWork.filter { p =>
      rawCache.getRecord(p) match {
        case None =>
          // Not in cache, this is new work that we need to do
          //println(s"Not in cache: $p")
          true
        case Some(record) =>
          // Check that cached effects are up to date
          assert(record.workDeps.isEmpty)
          record.fileDeps.foldLeft(false) {
            case (true, _) => true // We've already found a changed file, no need to check other files
            case (false, recordedContent) => {
              
              val currentContent = WorkCache.fileContent(recordedContent.file)
              val fileChanged = currentContent != recordedContent
              //println(s"File changed for $p: ${recordedContent.file}: $fileChanged")
              fileChanged
            }
          }
      }
    }
  }

  def recordWorkDone(params: P) = {
    val fileDeps = workDef.fileDepsForWork(params).map(WorkCache.fileContent)
    val record = WorkCache.Record[P](Set.empty, fileDeps)
    rawCache.putRecord(params, record)
  }
}

// trait TreeWorkDef[P] {
//   def rootWork: Seq[P]
//   def doWork(params: P, deps: Dependencies[P]): Unit
// }

// class TreeWorkCache[P](workDef: SimpleWorkDef[P], cache: WorkCache[P]) {
//   def workToDo: Seq[P]
// }


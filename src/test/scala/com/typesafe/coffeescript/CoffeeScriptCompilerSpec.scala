package com.typesafe.coffeescript;

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.Specification
import org.webjars.WebJarExtractor
import akka.util.Timeout
import scala.concurrent.duration._
import org.specs2.time.NoTimeConversions
import java.io.File
import scala.concurrent.Await
import _root_.sbt.IO
import scala.collection.immutable
import akka.actor.ActorSystem
import spray.json._
//import com.typesafe.jse.Trireme

@RunWith(classOf[JUnitRunner])
class CoffeeScriptCompilerSpec extends Specification with NoTimeConversions {

  implicit val duration = 15.seconds
  implicit val timeout = Timeout(duration)

  sequential

  private def compile(args: CompileArgs): CompileResult = {
    implicit val actorSystem = ActorSystem()
    try {
      CoffeeScriptCompiler.compileFile(args)
    } finally {
      actorSystem.shutdown()
    }
  }

  "the CoffeeScript compiler" should {
    "compile a trivial file" in IO.withTemporaryDirectory { tmpDir =>
      val args = CompileArgs(
        coffeeScriptInputFile = new File("/p/play/js/sbt-coffeescript-plugin/sbt-coffeescript-plugin-tester/src/main/assets/x.coffee"),
        javaScriptOutputFile = new File(tmpDir, "y.js"),
        sourceMapOpts = None,
        bare = false,
        literate = false
      )
      compile(args) must_== (CompileSuccess)
      IO.read(args.javaScriptOutputFile) must_== (
      """|(function() {
         |  var number, opposite;
         |
         |  number = 42;
         |
         |  opposite = true;
         |
         |}).call(this);
         |""".stripMargin('|'))
    }

    "compile a bare file" in IO.withTemporaryDirectory { tmpDir =>
      //IO.write(file, content, charset, append)
      val args = CompileArgs(
        coffeeScriptInputFile = new File("/p/play/js/sbt-coffeescript-plugin/sbt-coffeescript-plugin-tester/src/main/assets/x.coffee"),
        javaScriptOutputFile = new File(tmpDir, "y.js"),
        sourceMapOpts = None,
        bare = true,
        literate = false
      )
      compile(args) must_== (CompileSuccess)
      IO.read(args.javaScriptOutputFile) must_== (
      """|var number, opposite;
         |
         |number = 42;
         |
         |opposite = true;
         |""".stripMargin('|'))
    }

    "compile a literate file" in IO.withTemporaryDirectory { tmpDir =>
    val args = CompileArgs(
        coffeeScriptInputFile = new File("/p/play/js/sbt-coffeescript-plugin/sbt-coffeescript-plugin-tester/src/main/assets/literate.litcoffee"),
        javaScriptOutputFile = new File(tmpDir, "y.js"),
        sourceMapOpts = None,
        bare = false,
        literate = true
        )
        compile(args) must_== (CompileSuccess)
        IO.read(args.javaScriptOutputFile) must_== (
        """|(function() {
           |  var x, y;
           |
           |  x = 1;
           |
           |  y = 2;
           |
           |}).call(this);
           |""".stripMargin('|'))
    }
  }

}
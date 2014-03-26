import com.typesafe.sbt.web.SbtWeb
import com.typesafe.sbt.coffeescript.SbtCoffeeScript._

lazy val root = project.in(file(".")).addPlugins(SbtWeb)

//JsEngineKeys.engineType := JsEngineKeys.EngineType.Node

CoffeescriptKeys.sourceMap := true
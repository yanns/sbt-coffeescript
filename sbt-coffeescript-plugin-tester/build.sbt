import com.typesafe.sbt.web.SbtWebPlugin
import com.typesafe.sbt.coffeescript.SbtCoffeeScriptPlugin._

lazy val root = project.in(file(".")).addPlugins(SbtWebPlugin)

//JsEngineKeys.engineType := JsEngineKeys.EngineType.Node

CoffeescriptKeys.sourceMap := true
sbt-coffeescript-plugin
=======================

[![Build Status](https://api.travis-ci.org/sbt/sbt-coffeescript-plugin.png?branch=master)](https://travis-ci.org/sbt/sbt-coffeescript-plugin)

An SBT plugin to compile [CoffeeScript](http://coffeescript.org/) sources to JavaScript.

To use this plugin use the addSbtPlugin command within your project's `plugins.sbt` file:

    addSbtPlugin("com.typesafe.sbt" % "sbt-coffeescript-plugin" % "1.0.0-SNAPSHOT")

And add the following settings to your `build.sbt` file.

    import com.typesafe.sbt.web.SbtWebPlugin
    import com.typesafe.sbt.jse.SbtJsTaskPlugin
    import com.typesafe.sbt.coffeescript.SbtCoffeeScriptPlugin

    SbtWebPlugin.webSettings

    SbtJsTaskPlugin.jsEngineAndTaskSettings

    SbtCoffeeScriptPlugin.coffeeScriptSettings

Once configured, any `*.coffee` or `*.litcoffee` files placed in `src/main/assets` will be compiled to JavaScript code in `target/public`.

Supported settings:

* `sourceMaps` When set, generates sourceMap files. Defaults to `true`.

  `CoffeeScriptKeys.sourceMaps := true`

* `bare` When set, generates JavaScript without the [top-level function safety wrapper](http://coffeescript.org/#lexical-scope). Defaults to `false`.

  `CoffeeScriptKeys.bare := false`

The plugin is built on top of [JavaScript Engine](https://github.com/typesafehub/js-engine) which supports different JavaScript runtimes.

&copy; Typesafe Inc., 2013-2014
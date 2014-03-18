sbt-coffeescript-plugin
=======================

[![Build Status](https://api.travis-ci.org/sbt/sbt-coffeescript-plugin.png?branch=master)](https://travis-ci.org/sbt/sbt-coffeescript-plugin)

An SBT plugin to compile [CoffeeScript](http://coffeescript.org/) sources to JavaScript.

To use this plugin use the addSbtPlugin command within your project's `plugins.sbt` file:

    resolvers ++= Seq(
        Resolver.url("sbt snapshot plugins", url("http://repo.scala-sbt.org/scalasbt/sbt-plugin-snapshots"))(Resolver.ivyStylePatterns),
        Resolver.sonatypeRepo("snapshots"),
        "Typesafe Snapshots Repository" at "http://repo.typesafe.com/typesafe/snapshots/"
        )

    addSbtPlugin("com.typesafe.sbt" % "sbt-coffeescript-plugin" % "1.0.0-SNAPSHOT")

Once configured, any `*.coffee` or `*.litcoffee` files placed in `src/main/assets` will be compiled to JavaScript code in `target/web/public`.

Supported settings:

* `sourceMaps` When set, generates sourceMap files. Defaults to `true`.

  `CoffeeScriptKeys.sourceMaps := true`

* `bare` When set, generates JavaScript without the [top-level function safety wrapper](http://coffeescript.org/#lexical-scope). Defaults to `false`.

  `CoffeeScriptKeys.bare := false`

The plugin is built on top of [JavaScript Engine](https://github.com/typesafehub/js-engine) which supports different JavaScript runtimes.

&copy; Typesafe Inc., 2013-2014
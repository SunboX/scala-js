package org.scalajs.sbtplugin

import scala.annotation.tailrec

import java.util.IllegalFormatException
import java.util.concurrent.atomic.AtomicReference

import sbt._
import Keys._
import complete.Parser
import complete.DefaultParsers._

import sbtcrossproject.CrossPlugin.autoImport._

import Loggers._
import SBTCompat._
import SBTCompat.formatImplicits._
import SBTCompat.formatImplicits.seqFormat

import org.scalajs.core.tools.io.{IO => _, _}
import org.scalajs.core.tools.linker._
import org.scalajs.core.tools.linker.standard._

import org.scalajs.jsenv._
import org.scalajs.jsenv.nodejs.NodeJSEnv

import org.scalajs.core.ir
import org.scalajs.core.ir.Utils.escapeJS
import org.scalajs.core.ir.ScalaJSVersions
import org.scalajs.core.ir.Printers.IRTreePrinter

import org.scalajs.testadapter.{FrameworkDetector, HTMLRunnerBuilder}

import scala.util.Try
import scala.collection.mutable

import java.io.FileNotFoundException
import java.nio.charset.Charset

/** Implementation details of `ScalaJSPlugin`. */
private[sbtplugin] object ScalaJSPluginInternal {

  import ScalaJSPlugin.autoImport.{ModuleKind => _, _}
  import ScalaJSPlugin.{logIRCacheStats, stageKeys}

  /** The global Scala.js IR cache */
  val globalIRCache: IRFileCache = new IRFileCache()

  private val allocatedIRCaches =
    new AtomicReference[List[globalIRCache.Cache]](Nil)

  /** Allocates a new IR cache linked to the [[globalIRCache]].
   *
   *  The allocated IR cache will automatically be freed when the build is
   *  unloaded.
   */
  private def newIRCache: globalIRCache.Cache = {
    val cache = globalIRCache.newCache

    @tailrec
    def registerLoop(): Unit = {
      val prevValue = allocatedIRCaches.get()
      if (!allocatedIRCaches.compareAndSet(prevValue, cache :: prevValue))
        registerLoop()
    }
    registerLoop()

    cache
  }

  private[sbtplugin] def freeAllIRCaches(): Unit = {
    val allCaches = allocatedIRCaches.getAndSet(Nil)
    for (cache <- allCaches)
      cache.free()
  }

  /* #2798 -- On Java 9+, the parallel collections on 2.10 die with a
   * `NumberFormatException` and prevent the linker from working.
   *
   * By default, we therefore pre-emptively disable the parallel optimizer in
   * case the parallel collections cannot deal with the current version of
   * Java.
   *
   * TODO This will automatically "fix itself" once we upgrade to sbt 1.x,
   * which uses Scala 2.12. We should get rid of that workaround at that point
   * for tidiness, though.
   */
  private val DefaultParallelLinker: Boolean = {
    try {
      scala.util.Properties.isJavaAtLeast("1.8")
      true
    } catch {
      case _: NumberFormatException => false
    }
  }

  /** Patches the IncOptions so that .sjsir files are pruned as needed. */
  def scalaJSPatchIncOptions(incOptions: IncOptions): IncOptions =
    SBTCompat.scalaJSPatchIncOptions(incOptions)

  /** Settings for the production key (e.g. fastOptJS) of a given stage */
  private def scalaJSStageSettings(stage: Stage,
      key: TaskKey[Attributed[File]]): Seq[Setting[_]] = Seq(

      scalaJSLinker in key := {
        val config = (scalaJSLinkerConfig in key).value

        if (config.moduleKind != scalaJSLinkerConfig.value.moduleKind) {
          val projectID = thisProject.value.id
          val configName = configuration.value.name
          val keyName = key.key.label
          sLog.value.warn(
              s"The module kind in `scalaJSLinkerConfig in ($projectID, " +
              s"$configName, $keyName)` is different than the one `in " +
              s"`($projectID, $configName)`. " +
              "Some things will go wrong.")
        }

        new ClearableLinker(() => StandardLinker(config), config.batchMode)
      },

      // Have `clean` reset the state of the incremental linker
      clean in (This, Zero, This) := {
        val _ = (clean in (This, Zero, This)).value
        (scalaJSLinker in key).value.clear()
        ()
      },

      usesScalaJSLinkerTag in key := {
        val projectPart = thisProject.value.id
        val configPart = configuration.value.name

        val stagePart = stage match {
          case Stage.FastOpt => "fastopt"
          case Stage.FullOpt => "fullopt"
        }

        Tags.Tag(s"uses-scalajs-linker-$projectPart-$configPart-$stagePart")
      },

      // Prevent this linker from being used concurrently
      concurrentRestrictions in Global +=
        Tags.limit((usesScalaJSLinkerTag in key).value, 1),

      key := Def.taskDyn {
        /* It is very important that we evaluate all of those `.value`s from
         * here, and not from within the `Def.task { ... }`, otherwise the
         * relevant dependencies will not show up in `inspect tree`. We use a
         * `Def.taskDyn` only to be able to tag the inner task with a tag that
         * is setting-dependent. But otherwise, the task does not have actually
         * dynamic dependencies, so `inspect tree` is happy with it.
         */
        val s = streams.value
        val irInfo = (scalaJSIR in key).value
        val moduleInitializers = scalaJSModuleInitializers.value
        val output = (artifactPath in key).value
        val linker = (scalaJSLinker in key).value
        val usesLinkerTag = (usesScalaJSLinkerTag in key).value

        Def.task {
          val log = s.log
          val realFiles = irInfo.get(scalaJSSourceFiles).get
          val ir = irInfo.data

          FileFunction.cached(s.cacheDirectory, FilesInfo.lastModified,
              FilesInfo.exists) { _ => // We don't need the files

            val stageName = stage match {
              case Stage.FastOpt => "Fast"
              case Stage.FullOpt => "Full"
            }

            log.info(s"$stageName optimizing $output")

            IO.createDirectory(output.getParentFile)

            linker.link(ir, moduleInitializers,
                AtomicWritableFileVirtualJSFile(output),
                sbtLogger2ToolsLogger(log))

            logIRCacheStats(log)

            Set(output)
          } (realFiles.toSet)

          val sourceMapFile = FileVirtualJSFile(output).sourceMapFile
          Attributed.blank(output).put(scalaJSSourceMap, sourceMapFile)
        }.tag(usesLinkerTag)
      }.value,

      scalaJSLinkedFile in key := new FileVirtualJSFile(key.value.data)
  )

  private def dispatchSettingKeySettings[T](key: SettingKey[T]) = Seq(
      key := Def.settingDyn {
        val stageKey = stageKeys(scalaJSStage.value)
        Def.setting { (key in stageKey).value }
      }.value
  )

  private def dispatchTaskKeySettings[T](key: TaskKey[T]) = Seq(
      key := Def.settingDyn {
        val stageKey = stageKeys(scalaJSStage.value)
        Def.task { (key in stageKey).value }
      }.value
  )

  private def scalajspSettings: Seq[Setting[_]] = {
    def sjsirFileOnClasspathParser(
        relPaths: Seq[String]): Parser[String] = {
      OptSpace ~> StringBasic
        .examples(ScalajspUtils.relPathsExamples(relPaths))
    }

    def scalajspParser(state: State, relPaths: Seq[String]): Parser[String] =
      sjsirFileOnClasspathParser(relPaths)

    val parser = loadForParser(sjsirFilesOnClasspath) { (state, relPaths) =>
      scalajspParser(state, relPaths.getOrElse(Nil))
    }

    Seq(
        sjsirFilesOnClasspath := Def.task {
          scalaJSIR.value.data.map(_.relativePath).toSeq
        }.storeAs(sjsirFilesOnClasspath).triggeredBy(scalaJSIR).value,

        scalajsp := {
          val relPath = parser.parsed

          val vfile = scalaJSIR.value.data
              .find(_.relativePath == relPath)
              .getOrElse(throw new FileNotFoundException(relPath))

          val stdout = new java.io.PrintWriter(System.out)
          new IRTreePrinter(stdout).print(vfile.tree)
          stdout.flush()

          logIRCacheStats(streams.value.log)
        }
    )
  }

  /** Collect certain file types from a classpath.
   *
   *  @param cp Classpath to collect from
   *  @param filter Filter for (real) files of interest (not in jars)
   *  @param collectJar Collect elements from a jar (called for all jars)
   *  @param collectFile Collect a single file. Params are the file and the
   *      relative path of the file (to its classpath entry root).
   *  @return Collected elements attributed with physical files they originated
   *      from (key: scalaJSSourceFiles).
   */
  private def collectFromClasspath[T](cp: Def.Classpath, filter: FileFilter,
      collectJar: VirtualJarFile => Seq[T],
      collectFile: (File, String) => T): Attributed[Seq[T]] = {

    val realFiles = Seq.newBuilder[File]
    val results = Seq.newBuilder[T]

    for (cpEntry <- Attributed.data(cp) if cpEntry.exists) {
      if (cpEntry.isFile && cpEntry.getName.endsWith(".jar")) {
        realFiles += cpEntry
        val vf = new FileVirtualBinaryFile(cpEntry) with VirtualJarFile
        results ++= collectJar(vf)
      } else if (cpEntry.isDirectory) {
        for {
          (file, relPath0) <- Path.selectSubpaths(cpEntry, filter)
        } {
          val relPath = relPath0.replace(java.io.File.separatorChar, '/')
          realFiles += file
          results += collectFile(file, relPath)
        }
      } else {
        throw new IllegalArgumentException(
            "Illegal classpath entry: " + cpEntry.getPath)
      }
    }

    Attributed.blank(results.result()).put(scalaJSSourceFiles, realFiles.result())
  }

  val scalaJSConfigSettings: Seq[Setting[_]] = Seq(
      incOptions ~= scalaJSPatchIncOptions
  ) ++ (
      scalajspSettings ++
      stageKeys.flatMap((scalaJSStageSettings _).tupled) ++
      dispatchTaskKeySettings(scalaJSLinkedFile) ++
      dispatchSettingKeySettings(scalaJSLinker) ++
      dispatchSettingKeySettings(usesScalaJSLinkerTag)
  ) ++ (
      Seq(fastOptJS, fullOptJS).map { key =>
        moduleName in key := {
          val configSuffix = configuration.value match {
            case Compile => ""
            case config  => "-" + config.name
          }
          moduleName.value + configSuffix
        }
      }
  ) ++ Seq(
      // Note: this cache is not cleared by the sbt's clean task.
      scalaJSIRCache := newIRCache,

      scalaJSIR := {
        val rawIR = collectFromClasspath(fullClasspath.value, "*.sjsir",
            collectJar = Seq(_),
            collectFile = FileVirtualScalaJSIRFile.relative)

        val cache = scalaJSIRCache.value
        rawIR.map(cache.cached)
      },

      artifactPath in fastOptJS :=
        ((crossTarget in fastOptJS).value /
            ((moduleName in fastOptJS).value + "-fastopt.js")),

      artifactPath in fullOptJS :=
        ((crossTarget in fullOptJS).value /
            ((moduleName in fullOptJS).value + "-opt.js")),

      scalaJSLinkerConfig in fullOptJS ~= { prevConfig =>
        prevConfig
          .withSemantics(_.optimized)
          .withClosureCompiler(prevConfig.outputMode == OutputMode.ECMAScript51Isolated)
      },

      console := console.dependsOn(Def.task {
        streams.value.log.warn("Scala REPL doesn't work with Scala.js. You " +
            "are running a JVM REPL. JavaScript things won't work.")
      }).value,

      /* Do not inherit jsExecutionFiles from the parent configuration.
       * Instead, always derive them straight from the Zero configuration
       * scope.
       */
      jsExecutionFiles := (jsExecutionFiles in (This, Zero, This)).value,

      scalaJSJavaSystemProperties ++= {
        val javaSysPropsPattern = "-D([^=]*)=(.*)".r
        javaOptions.value.map {
          case javaSysPropsPattern(propName, propValue) => (propName, propValue)
          case opt =>
            throw new MessageOnlyException(
                "Scala.js javaOptions can only be \"-D<key>=<value>\"," +
                " but received: " + opt)
        }.toMap
      },

      // Optionally add a JS file defining Java system properties
      jsExecutionFiles ++= {
        val javaSystemProperties = scalaJSJavaSystemProperties.value
        if (javaSystemProperties.isEmpty) {
          Nil
        } else {
          val formattedProps = javaSystemProperties.map {
            case (propName, propValue) =>
              "\"" + escapeJS(propName) + "\": \"" + escapeJS(propValue) + "\""
          }
          val code = {
            "var __ScalaJSEnv = (typeof __ScalaJSEnv === \"object\" && __ScalaJSEnv) ? __ScalaJSEnv : {};\n" +
            "__ScalaJSEnv.javaSystemProperties = {" + formattedProps.mkString(", ") + "};\n"
          }
          Seq(new MemVirtualJSFile("setJavaSystemProperties.js").withContent(code))
        }
      },

      // Crucially, add the Scala.js linked file to the JS files
      jsExecutionFiles += scalaJSLinkedFile.value,

      scalaJSMainModuleInitializer := {
        mainClass.value.map { mainCl =>
          ModuleInitializer.mainMethodWithArgs(mainCl, "main")
        }
      },

      /* Do not inherit scalaJSModuleInitializers from the parent configuration.
       * Instead, always derive them straight from the Zero configuration
       * scope.
       */
      scalaJSModuleInitializers :=
        (scalaJSModuleInitializers in (This, Zero, This)).value,

      scalaJSModuleInitializers ++= {
        if (scalaJSUseMainModuleInitializer.value) {
          Seq(scalaJSMainModuleInitializer.value.getOrElse {
            throw new MessageOnlyException(
                "No main module initializer was specified (possibly because " +
                "no or multiple main classes were found), but " +
                "scalaJSUseMainModuleInitializer was set to true. " +
                "You can explicitly specify it either with " +
                "`mainClass := Some(...)` or with " +
                "`scalaJSMainModuleInitializer := Some(...)`")
          })
        } else {
          Seq.empty
        }
      },

      run := {
        if (!scalaJSUseMainModuleInitializer.value) {
          throw new MessageOnlyException("`run` is only supported with " +
              "scalaJSUseMainModuleInitializer := true")
        }

        val log = streams.value.log
        val env = jsEnv.value
        val files = jsExecutionFiles.value

        log.info("Running " + mainClass.value.getOrElse("<unknown class>"))
        log.debug(s"with JSEnv ${env.name}")

        env.jsRunner(files).run(sbtLogger2ToolsLogger(log), ConsoleJSConsole)
      },

      runMain := {
        throw new MessageOnlyException("`runMain` is not supported in Scala.js")
      }
  )

  val scalaJSCompileSettings: Seq[Setting[_]] = (
      scalaJSConfigSettings
  )

  private val scalaJSTestFrameworkSettings = Seq(
      loadedTestFrameworks := {
        if (fork.value) {
          throw new MessageOnlyException(
              "`test` tasks in a Scala.js project require " +
              "`fork in Test := false`.")
        }

        val env = jsEnv.value match {
          case env: ComJSEnv => env

          case env =>
            throw new MessageOnlyException(
                s"You need a ComJSEnv to test (found ${env.name})")
        }

        val files = jsExecutionFiles.value

        val moduleKind = scalaJSLinkerConfig.value.moduleKind
        val moduleIdentifier = moduleKind match {
          case ModuleKind.NoModule       => None
          case ModuleKind.CommonJSModule => Some(scalaJSLinkedFile.value.path)
        }

        val frameworksAndTheirImplNames =
          testFrameworks.value.map(f => f -> f.implClassNames.toList)

        val logger = sbtLogger2ToolsLogger(streams.value.log)

        FrameworkDetector.detectFrameworks(env, files, moduleKind,
            moduleIdentifier, frameworksAndTheirImplNames, logger)
      },

      // Override default to avoid triggering a test:fastOptJS in a test:compile
      // without loosing autocompletion.
      definedTestNames := {
        definedTests.map(_.map(_.name).distinct)
          .storeAs(definedTestNames).triggeredBy(loadedTestFrameworks).value
      }
  )

  private val scalaJSTestBuildSettings = (
      scalaJSConfigSettings
  )

  private val scalaJSTestHtmlSettings = Seq(
      artifactPath in testHtml := {
        val stageSuffix = scalaJSStage.value match {
          case Stage.FastOpt => "fastopt"
          case Stage.FullOpt => "opt"
        }
        val config = configuration.value.name
        ((crossTarget in testHtml).value /
            ((moduleName in testHtml).value + s"-$stageSuffix-$config.html"))
      },

      testHtml := {
        val log = streams.value.log
        val output = (artifactPath in testHtml).value
        val title = name.value + " - tests"
        val jsFiles = (jsExecutionFiles in testHtml).value

        val frameworks = (loadedTestFrameworks in testHtml).value.toList
        val frameworkImplClassNames =
          frameworks.map(_._1.implClassNames.toList)

        val taskDefs = for (td <- (definedTests in testHtml).value) yield {
          new sbt.testing.TaskDef(td.name, td.fingerprint,
              td.explicitlySpecified, td.selectors)
        }

        HTMLRunnerBuilder.writeToFile(output, title, jsFiles,
            frameworkImplClassNames, taskDefs.toList)

        log.info(s"Wrote HTML test runner. Point your browser to ${output.toURI}")

        Attributed.blank(output)
      }
  )

  val scalaJSTestSettings: Seq[Setting[_]] = (
      scalaJSTestBuildSettings ++
      scalaJSTestFrameworkSettings ++
      scalaJSTestHtmlSettings
  ) ++ Seq(
      /* Always default to false for scalaJSUseMainModuleInitializer in testing
       * configurations, even if it is true in the Global configuration scope.
       */
      scalaJSUseMainModuleInitializer := false
  )

  private val scalaJSProjectBaseSettings = Seq(
      crossPlatform := JSPlatform,

      scalaJSLinkerConfig := {
        StandardLinker.Config()
          .withParallel(DefaultParallelLinker)
      },

      scalaJSModuleInitializers := Seq(),
      scalaJSUseMainModuleInitializer := false,

      jsEnv := new NodeJSEnv(),

      jsExecutionFiles := Nil,

      scalaJSJavaSystemProperties := Map.empty,

      // you will need the Scala.js compiler plugin
      autoCompilerPlugins := true,
      addCompilerPlugin(
          "org.scala-js" % "scalajs-compiler" % scalaJSVersion cross CrossVersion.full),

      libraryDependencies ++= Seq(
          // and of course the Scala.js library
          "org.scala-js" %% "scalajs-library" % scalaJSVersion,
          // also bump the version of the test-interface
          "org.scala-js" %% "scalajs-test-interface" % scalaJSVersion % "test"
      ),

      // and you will want to be cross-compiled on the Scala.js binary version
      crossVersion := ScalaJSCrossVersion.binary
  )

  val scalaJSProjectSettings: Seq[Setting[_]] = (
      scalaJSProjectBaseSettings ++
      inConfig(Compile)(scalaJSCompileSettings) ++
      inConfig(Test)(scalaJSTestSettings)
  )
}

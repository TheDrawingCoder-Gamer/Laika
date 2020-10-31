import sbt.Keys.artifactPath

lazy val basicSettings = Seq(
  version               := "0.17.1-SNAPSHOT",
  homepage              := Some(new URL("http://planet42.github.io/Laika/")),
  organization          := "org.planet42",
  organizationHomepage  := Some(new URL("http://planet42.org")),
  description           := "Text Markup Transformer for sbt and Scala applications",
  startYear             := Some(2012),
  licenses              := Seq("Apache 2.0" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt")),
  scalaVersion          := "2.12.12",
  scalacOptions         := (Opts.compile.encoding("UTF-8") :+ 
                           Opts.compile.deprecation :+ 
                           Opts.compile.unchecked :+ 
                           "-feature" :+ 
                           "-language:implicitConversions" :+ 
                           "-language:postfixOps" :+ 
                           "-language:higherKinds")  ++ 
                             (if (priorTo2_13(scalaVersion.value)) Seq("-Ypartial-unification") else Nil)
                           
)

def priorTo2_13(version: String): Boolean =
  CrossVersion.partialVersion(version) match {
    case Some((2, minor)) if minor < 13 => true
    case _                              => false
  }

lazy val moduleSettings = basicSettings ++ Seq(
  crossScalaVersions := Seq("2.12.12", "2.13.3")
)

lazy val publishSettings = Seq(
  publishMavenStyle       := true,
  publishArtifact in Test := false,
  pomIncludeRepository    := { _ => false },
  publishTo := {
    if (version.value.trim.endsWith("SNAPSHOT")) None
    else Some("releases" at "https://oss.sonatype.org/service/local/staging/deploy/maven2")
  },
  pomExtra := (
    <scm>
      <url>https://github.com/planet42/Laika.git</url>
      <connection>scm:git:https://github.com/planet42/Laika.git</connection>
    </scm>
    <developers>
      <developer>
        <id>jenshalm</id>
        <name>Jens Halm</name>
        <url>http://planet42.org</url>
      </developer>
    </developers>)
)

lazy val noPublishSettings = Seq(
  publish := (()),
  publishLocal := (()),
  publishTo := None
)

val scalatest  = "org.scalatest"          %% "scalatest"   % "3.2.2" % "test"
val jTidy      = "net.sf.jtidy"           %  "jtidy"       % "r938"  % "test"

val catsEffect = "org.typelevel"          %% "cats-effect" % "2.2.0"

val fop        = "org.apache.xmlgraphics" %  "fop"         % "2.3"
val http4s     = Seq(
                   "org.http4s"           %% "http4s-dsl"          % "0.21.4",
                   "org.http4s"           %% "http4s-blaze-server" % "0.21.4"
                 )

lazy val root = project.in(file("."))
  .aggregate(core.js, core.jvm, pdf, io, plugin)
  .settings(basicSettings)
  .settings(noPublishSettings)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    crossScalaVersions := Nil,
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inAnyProject -- inProjects(plugin, core.js, demo.jvm, demo.js)
  )

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(moduleSettings)
  .settings(publishSettings)
  .settings(
    name := "laika-core",
    libraryDependencies ++= Seq(
      "org.scalatest" %%% "scalatest" % "3.2.2" % "test",
      "org.typelevel" %%% "cats-core" % "2.2.0"
    )
  )
  .jvmSettings(
    libraryDependencies += jTidy
  )

lazy val io = project.in(file("io"))
  .dependsOn(core.jvm % "compile->compile;test->test")
  .settings(moduleSettings)
  .settings(publishSettings)
  .settings(
    name := "laika-io",
    libraryDependencies ++= Seq(scalatest, catsEffect)
  )
  
lazy val pdf = project.in(file("pdf"))
  .dependsOn(core.jvm % "compile->compile;test->test", io % "compile->compile;test->test")
  .settings(moduleSettings)
  .settings(publishSettings)
  .settings(
    name := "laika-pdf",
    libraryDependencies ++= Seq(fop, scalatest)
  )
  
lazy val plugin = project.in(file("sbt"))
  .dependsOn(core.jvm, io, pdf)
  .enablePlugins(SbtPlugin)
  .settings(basicSettings)
  .settings(
    name := "laika-sbt",
    sbtPlugin := true,
    crossScalaVersions := Seq("2.12.12"),
    publishMavenStyle := false,
    bintrayRepository := "sbt-plugins",
    bintrayOrganization := None,
    scriptedLaunchOpts ++= Seq("-Xmx1024M", "-Dplugin.version=" + version.value),
    scriptedBufferLog := false
  )

lazy val demo = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("demo"))
  .dependsOn(core)
  .enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)
  .settings(basicSettings)
  .settings(
    name := "laika-demo",
    version := "0.16.0.0"
  )
  .jvmSettings(
    libraryDependencies ++= http4s,
    javaOptions in Universal ++= Seq(
      "-J-Xms512M",
      "-J-Xmx896M"
    ),
    buildOptions in docker := BuildOptions (
      cache = false,
      removeIntermediateContainers = BuildOptions.Remove.Always,
      pullBaseImage = BuildOptions.Pull.Always
    ),
    dockerfile in docker := {
      val appDir: File = stage.value
      val targetDir = "/app"

      new Dockerfile {
        from("openjdk:8")
        expose(8080)
        env("VERSION", version.value)
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir)
      }
    },
    imageNames in docker := Seq(ImageName(
      namespace = None,
      repository = name.value,
      tag = Some(version.value)
    ))
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    artifactPath in (Compile, fastOptJS) :=
      (ThisBuild / baseDirectory).value / "demo" / "client" / "src" / "transformer" / "transformer.mjs", 
    artifactPath in (Compile, fullOptJS) :=
      (ThisBuild / baseDirectory).value / "demo" / "client" / "src" / "transformer" / "transformer-opt.mjs"
  )

  
val scala2 = "2.13.18"
val scala3 = "3.9.0"

lazy val commonSettings = Seq(
  organization := "io.github.leoprover",
  homepage := Some(url("https://github.com/leoprover/ciao")),
  scmInfo := Some(ScmInfo(
    browseUrl = url("https://github.com/leoprover/ciao"),
    connection = "scm:git:git@github.com:leoprover/ciao.git"
  )),
  developers := List(
    Developer(
      "lex-lex",
      "Alexander Steen",
      "alx.steen@gmail.com",
      url("https://www.alexandersteen.de/")
    ),
    Developer(
      "melanie-taprogge",
      "Melanie Taprogge",
      "melanie.taprogge@stud.uni-greifswald.de",
      url("https://melanie-taprogge.github.io/")
    )
  ),

  crossScalaVersions := Seq(scala3) ++ Seq(scala2),
  scalaVersion := crossScalaVersions.value.head,
  scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
    ),
  licenses += "BSD-3-Clause" -> url("https://opensource.org/licenses/BSD-3-Clause"),

  libraryDependencies += "io.github.leoprover" %% "scala-tptp-parser" % "1.7.5",
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.20" % "test"
)


lazy val root = (project in file("."))
  .disablePlugins(sbtassembly.AssemblyPlugin)
  .settings(
    commonSettings,
    name := "ciao",
    description := "A tool for augmenting TPTP problems with instances of the comprehension axiom.",
    publish / skip := true // don't publish the root project
  ).aggregate(ciaolib, ciaoApp)


lazy val ciaolib = project
  .in(file("ciao-lib"))
	.settings(
	  commonSettings,
    name := "ciao-lib",
    description := "A tool for augmenting TPTP problems with instances of the comprehension axiom.",
    libraryDependencies += "io.github.leoprover" %% "tptp-utils-runtime" % "1.5.0",
    assembly / assemblyOption ~= {
      _.withIncludeScala(false)
    },
    assembly/test := {},
    assembly/assemblyJarName := s"${name.value}.jar"
	)

lazy val ciaoApp = project
  .in(file("ciao"))
	.settings(
	  commonSettings,
    name := "ciao-app",
    Compile/mainClass := Some("leo.modules.Ciao"),
    assembly/mainClass := Some("leo.modules.Ciao"),
    assembly/test := {},
    assembly/assemblyJarName := s"${name.value}.jar",
    nativeImageOptions += s"-H:ReflectionConfigurationFiles=${target.value / ".." / "contrib" / "native-image-configs" / "reflect-config.json"}",
    nativeImageOptions += s"-H:ConfigurationFileDirectories=${target.value / ".." / "contrib" / "native-image-configs" }",
    nativeImageOptions +="-H:+JNI",
    publish / skip := true
	).dependsOn(ciaolib)
	.enablePlugins(NativeImagePlugin)

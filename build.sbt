lazy val commonSettings = Seq(
  name := "ciao",
  organization := "org.leo",
  version := "1.0.0",
  scalaVersion := "2.13.18",
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
    description := "A tool for augmenting TPTP problems with instances of comprehension.",
    publish / skip := true // don't publish the root project
  ).aggregate(ciaolib, ciaoApp)


lazy val ciaolib = project
  .in(file("ciao-lib"))
	.settings(
	  commonSettings,
    name := "ciao-lib",
    libraryDependencies += "io.github.leoprover" %% "tptp-utils-runtime" % "1.5.0",
    assembly / assemblyOption ~= {
      _.withIncludeScala(false)
    },
    assembly/test := {},
    assembly/assemblyJarName := s"${name.value}-${version.value}.jar"
	)

lazy val ciaoApp = project
  .in(file("ciao"))
	.settings(
	  commonSettings,
    name := "ciao-app",
    Compile/mainClass := Some("leo.modules.Ciao"),
    assembly/mainClass := Some("leo.modules.Ciao"),
    assembly/test := {},
    assembly/assemblyJarName := s"${name.value}-${version.value}.jar",
    nativeImageOptions += s"-H:ReflectionConfigurationFiles=${target.value / ".." / "contrib" / "native-image-configs" / "reflect-config.json"}",
    nativeImageOptions += s"-H:ConfigurationFileDirectories=${target.value / ".." / "contrib" / "native-image-configs" }",
    nativeImageOptions +="-H:+JNI",
    publish / skip := true
	).dependsOn(ciaolib)
	.enablePlugins(NativeImagePlugin)

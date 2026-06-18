lazy val commonSettings = Seq(
  name := "ciao",
  organization := "org.leo",
  version := "1.0.0",
  scalaVersion := "2.13.16",
  scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
    ),
  licenses += "BSD-3-Clause" -> url("https://opensource.org/licenses/BSD-3-Clause"),

  libraryDependencies += "io.github.leoprover" %% "scala-tptp-parser" % "1.7.3",
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % "test"
)

lazy val ciaolib = project
    .in(file("ciao-lib"))
	.settings(
	  commonSettings,
    name := "ciao-lib",
    assembly / assemblyOption ~= {
      _.withIncludeScala(false)
    },
    assembly/test := {},
    assembly/assemblyJarName := s"${name.value}-${version.value}.jar"
	)

lazy val ciao = project
    .in(file("ciao"))
	.settings(
	  commonSettings,
    name := "ciao",
    Compile/mainClass := Some("leo.modules.Ciao"),
    assembly/mainClass := Some("leo.modules.Ciao"),
    assembly/test := {},
    assembly/assemblyJarName := s"${name.value}-${version.value}.jar",
    nativeImageOptions += s"-H:ReflectionConfigurationFiles=${target.value / ".." / "contrib" / "native-image-configs" / "reflect-config.json"}",
    nativeImageOptions += s"-H:ConfigurationFileDirectories=${target.value / ".." / "contrib" / "native-image-configs" }",
    nativeImageOptions +="-H:+JNI"
	).dependsOn(ciaolib)
	.enablePlugins(NativeImagePlugin)

import Frontend._

name := "TheHive"

lazy val thehiveBackend = (project in file("thehive-backend"))
  .settings(publish := {})

lazy val thehiveMetrics = (project in file("thehive-metrics"))
  .dependsOn(thehiveBackend)
  .settings(publish := {})

lazy val thehiveMisp = (project in file("thehive-misp"))
  .dependsOn(thehiveBackend)
  .settings(publish := {})

lazy val thehive = (project in file("."))
  .enablePlugins(PlayScala)
  .dependsOn(thehiveBackend, thehiveMetrics, thehiveMisp)
  .aggregate(thehiveBackend, thehiveMetrics, thehiveMisp)
  .settings(aggregate in Docker := false)
  .settings(Frontend.settings: _*)

// Front-end //
run := {
  (run in Compile).evaluated
  frontendCompile.evaluated
}
mappings in packageBin in Assets ++= frontendFiles.value

// Install files //

mappings in Universal ++= {
  val dir = baseDirectory.value / "install"
  (dir.***) pair relativeTo(dir.getParentFile)
}

// Analyzers //

mappings in Universal ++= {
  val dir = baseDirectory.value / "analyzers"
  (dir.***) pair relativeTo(dir.getParentFile)
}

// BINTRAY //
publish := BinTray.publish(
	(packageBin in Universal).value,
	bintrayEnsureCredentials.value,
	bintrayOrganization.value,
	bintrayRepository.value,
	bintrayPackage.value,
	version.value,
	sLog.value)

bintrayOrganization := Some("cert-bdf")

bintrayRepository := "thehive"

// DOCKER //

dockerBaseImage := "elasticsearch:2.3"

dockerExposedVolumes += "/data"

dockerRepository := Some("certbdf")

dockerUpdateLatest := true

mappings in Universal += file("docker/entrypoint") -> "bin/entrypoint"

import com.typesafe.sbt.packager.docker.{ ExecCmd, Cmd }

dockerCommands := dockerCommands.value.map {
  case ExecCmd("ENTRYPOINT", _*) => ExecCmd("ENTRYPOINT", "bin/entrypoint")
  case cmd                       => cmd
}

dockerCommands := (dockerCommands.value.head +:
  ExecCmd("RUN", "bash", "-c",
    "apt-get update && " +
    "apt-get install -y --no-install-recommends python python-pip && " +
    "pip install OleFile && " +
    "rm -rf /var/lib/apt/lists/*") +:
  Cmd("EXPOSE", "9000") +:
  dockerCommands.value.tail)

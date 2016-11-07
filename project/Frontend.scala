import sbt._, Keys._

object Frontend {

  val frontendCompile = inputKey[Unit]("Compile front-end in dev")
  val frontendFiles = taskKey[Seq[(File, String)]]("Front-end files")

  lazy val settings = Seq(
    frontendCompile := {
      val s = streams.value
      s.log.info("Preparing front-end for dev (grunt wiredep)")
      Process("grunt" :: "wiredep" :: Nil, baseDirectory.value / "ui") ! s.log
    },
    frontendFiles := {
      val s = streams.value
      s.log.info("Preparing front-end for prod ...")
      s.log.info("npm install")
      Process("npm" :: "install" :: Nil, baseDirectory.value / "ui") ! s.log
      s.log.info("bower install")
      Process("bower" :: "install" :: Nil, baseDirectory.value / "ui") ! s.log
      s.log.info("grunt build")
      Process("grunt" :: "build" :: Nil, baseDirectory.value / "ui") ! s.log
      val dir = baseDirectory.value / "ui" / "dist"
      (dir.***) pair rebase(dir, "ui")
    })
}
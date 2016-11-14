import Dependencies._

libraryDependencies ++= Seq(
  Library.Play.cache,
  Library.Play.ws,
  Library.scalaGuice,
  Library.elastic4play,
  Library.zip4j,
  Library.shapeless,
  "org.reflections" % "reflections" % "0.9.10"
)

enablePlugins(PlayScala)

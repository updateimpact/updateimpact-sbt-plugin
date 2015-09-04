val commonSettings = Seq(
  organization  := "com.softwaremill.example",
  version := "0.1-SNAPSHOT",
  scalaVersion := "2.11.6"
)

lazy val rootProject = (project in file("."))
  .settings(commonSettings: _*)
  .settings(
    name := "sbt-example",
    libraryDependencies ++= Seq(
      "junit" % "junit" % "3.8.1" % "test"
    )).aggregate(module1, module2)

lazy val module1: Project = (project in file("module1"))
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies ++= Seq(
      "log4j" % "log4j" % "1.2.14",
      "dom4j" % "dom4j" % "1.6.1",
      "org.apache.httpcomponents" % "httpclient" % "4.0.1",
      // evicts a dep in httpclient
      "commons-logging" % "commons-logging" % "1.2"
    ))

lazy val module2: Project = (project in file("module2"))
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies ++= Seq(
      "javax.servlet" % "servlet-api" % "2.4" % "provided",
      "com.softwaremill.macwire" %% "runtime" % "1.0.5"
    )).dependsOn(module1)

updateImpactApiKey in ThisBuild := "x"

TaskKey[Unit]("check") := {
  val expected = scala.io.Source.fromFile("expected.txt").getLines().mkString("\n")

  val report = updateImpactDependencyReport.value
  val str = com.updateimpact.testing.ReportToString.toString(report)

  if (expected != str) {
    println("EXPECTED: ")
    println(expected)

    println("GOT: ")
    println(str)

    sys.error("Invalid report generated")
  }
}
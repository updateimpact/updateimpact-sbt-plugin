organization := "com.updateimpact"

name := "updateimpact-sbt-plugin"

version := "2.1.0"

sbtPlugin := true

libraryDependencies += "com.updateimpact" % "updateimpact-plugin-common" % "1.3.2"

// Sonatype OSS deployment
publishTo <<= version { (v: String) =>
  val nexus = "https://oss.sonatype.org/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

publishMavenStyle := true

pomIncludeRepository := { _ => false }

pomExtra := (
  <scm>
    <url>git@gihub.com/updateimpact/updateimpact-sbt-plugin.git</url>
    <connection>scm:git:git@github.com/updateimpact/updateimpact-sbt-plugin.git</connection>
  </scm>
    <developers>
      <developer>
        <id>adamw</id>
        <name>Adam Warski</name>
        <url>http://www.warski.org</url>
      </developer>
    </developers>
  )

licenses := ("Apache2", new java.net.URL("http://www.apache.org/licenses/LICENSE-2.0.txt")) :: Nil

homepage := Some(new java.net.URL("http://updateimpact.com"))

enablePlugins(BuildInfoPlugin)

buildInfoPackage := "com.updateimpact"

buildInfoObject := "UpdateimpactSbtBuildInfo"

// Testing

ScriptedPlugin.scriptedSettings

scriptedLaunchOpts := { scriptedLaunchOpts.value ++
  Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
}

scriptedBufferLog := false

scriptedRun <<= scriptedRun dependsOn publishLocal
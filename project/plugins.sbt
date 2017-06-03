addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.4.0")

libraryDependencies <+= (sbtVersion) { sv =>
  "org.scala-sbt" % "scripted-plugin" % sv
}

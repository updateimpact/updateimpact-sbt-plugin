package com.updateimpact

case class DependencyReport(projectName: String, apikey: String, buildId: String, formatVersion: String,
  modules: Set[ModuleDependencies] = Set()) {

  def toJson: String = {
    import rapture.json.Json
    import rapture.json.jsonBackends.json4s._
    import rapture.json.formatters.compact._

    Json.format(Json(this))
  }
}

case class ModuleDependencies(moduleId: DependencyId, config: String, dependencies: Set[Dependency])

case class Dependency(id: DependencyId, dependencies: Set[DependencyChild] = Set())

case class DependencyId(groupId: String, artifactId: String, version: String, `type`: Option[String],
  classifier: Option[String])

case class DependencyChild(id: DependencyId, evictedByVersion: Option[String], cycle: Boolean = false)

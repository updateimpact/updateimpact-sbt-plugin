package com.updateimpact

/**
 * If a project depends on a project, the dependency childs id won't have the "type" set properly.
 * For the graph to be shown properly, this must be amended.
 */
object FixInterProjectDependencies {
  private case class PartialDependencyId(groupId: String, artifactId: String, version: String)
  private object PartialDependencyId {
    def from(id: DependencyId) = PartialDependencyId(id.groupId, id.artifactId, id.version)
  }

  def apply(moduleDependencies: Set[ModuleDependencies]): Set[ModuleDependencies] = {
    val moduleIds = moduleDependencies.map(_.moduleId)
    val partialToId = moduleIds.map(id => PartialDependencyId.from(id) -> id).toMap
    def fixId(id: DependencyId) = partialToId.getOrElse(PartialDependencyId.from(id), id)

    moduleDependencies.map(md =>
      md.copy(dependencies = md.dependencies.map(d =>
        d.copy(dependencies = d.dependencies.map(dc => dc.copy(id = fixId(dc.id)))))))
  }
}

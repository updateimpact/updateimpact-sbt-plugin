package com.updateimpact

import scala.collection.JavaConversions._

/**
 * If a project depends on a project, the dependency ids and child ids won't have the "type" set properly.
 * For the graph to be shown properly, this must be amended.
 */
object FixInterProjectDependencies {
  private case class PartialDependencyId(groupId: String, artifactId: String, version: String)
  private object PartialDependencyId {
    def from(id: DependencyId) = PartialDependencyId(id.getGroupId, id.getArtifactId, id.getVersion)
  }

  def apply(moduleDependencies: Set[ModuleDependencies]): Set[ModuleDependencies] = {
    val moduleIds = moduleDependencies.map(_.getModuleId)
    val partialToId = moduleIds.map(id => PartialDependencyId.from(id) -> id).toMap
    def fixId(id: DependencyId) = partialToId.getOrElse(PartialDependencyId.from(id), id)

    moduleDependencies.map(md =>
      new ModuleDependencies(md.getModuleId, md.getConfig,
        md.getDependencies.map(d =>
          new Dependency(
            fixId(d.getId),
            d.getDependencies.map(dc => new DependencyChild(fixId(dc.getId), dc.getEvictedByVersion, dc.getCycle))))))

  }
}

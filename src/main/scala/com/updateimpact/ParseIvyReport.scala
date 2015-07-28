package com.updateimpact

import java.io.File

import sbt.{Configuration, Artifact, Logger}
import scala.collection.JavaConversions._

import scala.xml.{Node, XML}

class ParseIvyReport(log: Logger) {
  private case class Caller(org: String, name: String, rev: String)
  private case class Revision(id: DependencyId, evictedBy: Option[String], callers: List[Caller]) {
    def toDependencyChild = new DependencyChild(id, evictedBy.orNull, null)
  }

  def parse(report: File, artifact: Artifact, config: Configuration): ModuleDependencies = {
    val root = XML.loadFile(report)
    val revisions = extractRevisions(root)

    val rootId = extractRootId(root, artifact)

    val callerToId = callerToIdMap(revisions) + callerToIdMapping(rootId)

    val dependencies = revisions
      .flatMap(r => r.callers.map(_ -> r))
      .map { case (caller, revision) =>
      val id = callerToId.getOrElse(caller,
        throw new IllegalStateException(s"Cannot find id for caller $caller, revisions: $revisions"))

      id -> revision.toDependencyChild
    }
      .groupBy(_._1)
      .map { case (id, idAndChildren) =>
        new Dependency(id, idAndChildren.map(_._2).toList)
    }
      .toSet

    new ModuleDependencies(rootId, config.name, dependencies)
  }

  private def extractRootId(root: Node, artifact: Artifact): DependencyId = {
    val info = (root \ "info").head
    new DependencyId(
      info.attribute("organisation").get.text,
      info.attribute("module").get.text,
      info.attribute("revision").get.text,
      artifact.`type`,
      artifact.classifier.orNull
    )
  }

  private def extractRevisions(root: Node): List[Revision] =
    (for {
      module <- root \ "dependencies" \ "module"
      org = module.attribute("organisation").get.text
      name = module.attribute("name").get.text
      revision <- module \ "revision"
    } yield {
        val artifact = (revision \ "artifacts" \ "artifact").headOption
        val id = new DependencyId(
          org,
          name,
          revision.attribute("name").get.text,
          artifact.flatMap(_.attribute("type")).map(_.text).orNull,
          artifact.flatMap(_.attribute("classifier")).map(_.text).orNull)

        val evictedBy = (revision \ "evicted-by").headOption.flatMap(_.attribute("rev")).map(_.text)

        Revision(id, evictedBy, extractCallers(revision))
      }).toList

  private def extractCallers(revision: Node) =
    (for {
      caller <- revision \ "caller"
    } yield {
        Caller(
          caller.attribute("organisation").get.text,
          caller.attribute("name").get.text,
          caller.attribute("callerrev").get.text
        )
      }).toList

  private def callerToIdMap(revisions: List[Revision]): Map[Caller, DependencyId] = {
    val callerToIdList = revisions.map(r => callerToIdMapping(r.id))
    val callerToId = callerToIdList.toMap
    if (callerToId.size != revisions.size) {
      // The only situation when we could get duplicates is if there are two revisions with same
      // groupid/artifactid/version, but different types/classifiers
      val duplicateCallers = callerToIdList.groupBy(_._1).filter(_._2.size > 1)
      log.warn(s"Duplicate callers when constructing caller-to-id map: $duplicateCallers")
    }

    callerToId
  }

  private def callerToIdMapping(id: DependencyId) = Caller(id.getGroupId, id.getArtifactId, id.getVersion) -> id
}
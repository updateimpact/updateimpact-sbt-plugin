package com.updateimpact

import java.io.FileInputStream
import java.util.Properties

import com.updateimpact.report.{Dependency, ModuleDependencies, DependencyId}
import org.apache.ivy.core.cache.ResolutionCacheManager
import org.apache.ivy.core.module.descriptor.ModuleDescriptor
import org.apache.ivy.core.module.id.{ModuleId, ModuleRevisionId}
import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorParser
import sbt.{ConfigurationReport, ModuleID}

import scala.collection.JavaConversions._

object TransformConfigurationReports {
  def toDepId(mid: ModuleID) = new DependencyId(mid.organization, mid.name, mid.revision,
    null, null)                                                        // TODO -> mid.configurations.orNull

  def toDepId(mri: ModuleRevisionId) = new DependencyId(mri.getOrganisation, mri.getName, mri.getRevision,
    null, null)

  def javaMap(m: Map[String, String]) = {
    if (m.isEmpty) null else scala.collection.JavaConversions.mapAsJavaMap(m)
  }

  def toModRevId(depId: DependencyId) = {
    ModuleRevisionId.newInstance(depId.getGroupId, depId.getArtifactId, depId.getVersion)
  }

  // TODO
  def toStr(d: DependencyId) = s"${d.getGroupId}:${d.getArtifactId}:${d.getVersion}:${d.getType}:${d.getClassifier}"

  def transform(rootMri: ModuleRevisionId, rcm: ResolutionCacheManager, settings: IvySettings, crs: Seq[ConfigurationReport]): List[ModuleDependencies] = {

    val rootId = toDepId(rootMri)

    crs.toList.map { cfg =>

      var depMap = Map[DependencyId, Set[DependencyId]]()
      var evictedMap = Map[DependencyId, String]()

      val pi = new ParseIvy(rcm, settings)

      def fillDepMapFromResolutionCache(depId: DependencyId): Unit = {
        println("FILLING FOR: " + toStr(depId))

        if (!depMap.contains(depId)) {
          depMap += depId -> Set()

          try {
            val md = pi.getResolvedModuleDescriptor(toModRevId(depId))
            val replacements = pi.properties(toModRevId(depId))
            val deps = md.getDependencies
              .filter(_.getModuleConfigurations.contains(cfg.configuration))
              .map(_.getDependencyRevisionId)
              .map { mrid =>
              if (replacements.contains(mrid)) {
                ModuleRevisionId.newInstance(mrid, replacements(mrid))
              } else {
                mrid
              }
            }
              .map(toDepId)

            depMap += depId -> deps.toSet

            deps.foreach(fillDepMapFromResolutionCache)
          } catch {
            case e: IllegalStateException =>
              println("NOT IN CACHE")
              // no file in cache
          }
        }
      }

      fillDepMapFromResolutionCache(rootId)

      cfg.details.foreach { detail =>
        detail.modules.foreach { moduleReport =>
          val id = toDepId(moduleReport.module)
          if (!depMap.contains(id)) depMap += id -> Set()

          if (moduleReport.evicted) {
            evictedMap += id -> detail.modules.find(_.evicted == false).map(_.module.revision).getOrElse("?")
          }

          moduleReport.callers.foreach { caller =>
            if (caller.caller.organization != "org.scala-sbt.temp") {
              val callerId = toDepId(caller.caller)
              depMap += callerId -> (depMap.getOrElse(callerId, Set()) + id)
            }
          }
        }
      }

      val deps = depMap.map { case (id, idDeps) =>
        new Dependency(id, evictedMap.getOrElse(id, null), false, idDeps.toList)
      }

      new ModuleDependencies(rootId, cfg.configuration, deps)
    }
  }
}

class ParseIvy(rcm: ResolutionCacheManager, settings: IvySettings) {
  // ResolutionCache:45
  def getResolvedModuleDescriptor(mrid: ModuleRevisionId): ModuleDescriptor = {
    val ivyFile = rcm.getResolvedIvyFileInCache(mrid)
    if (!ivyFile.exists()) {
      throw new IllegalStateException("Ivy file not found in cache for " + mrid + "!")
    }

    XmlModuleDescriptorParser.getInstance().parseDescriptor(settings, ivyFile.toURI.toURL, false)
  }

  // DeliverEngine:117
  def properties(mrid: ModuleRevisionId): Map[ModuleRevisionId, String] = {
    var resolvedRevisions = Map[ModuleRevisionId, String]() // Map (ModuleId -> String revision)
    val ivyProperties = rcm.getResolvedIvyPropertiesInCache(mrid)
    if (!ivyProperties.exists()) {
      throw new IllegalStateException("ivy properties not found in cache for " + mrid
        + " please resolve dependencies before delivering!")
    }
    val props = new Properties()
    val in = new FileInputStream(ivyProperties)
    props.load(in)
    in.close()

    val iter = props.keySet().iterator()
    while(iter.hasNext) {
      val depMridStr = iter.next().asInstanceOf[String]
      val parts = props.getProperty(depMridStr).split(" ")
      val decodedMrid = ModuleRevisionId.decode(depMridStr)

      if (parts.length >= 3) {
        resolvedRevisions += decodedMrid -> parts(2)
      } else {
        resolvedRevisions += decodedMrid -> parts(0)
      }
    }

    resolvedRevisions
  }
}

/*
DeliverEngine:117

 // 2) parse resolvedRevisions From properties file
        Map resolvedRevisions = new HashMap() // Map (ModuleId -> String revision)
        Map resolvedBranches = new HashMap() // Map (ModuleId -> String branch)
        Map dependenciesStatus = new HashMap() // Map (ModuleId -> String status)
        File ivyProperties = getCache().getResolvedIvyPropertiesInCache(mrid)
        if (!ivyProperties.exists()) {
            throw new IllegalStateException("ivy properties not found in cache for " + mrid
                    + " please resolve dependencies before delivering!")
        }
        Properties props = new Properties()
        FileInputStream in = new FileInputStream(ivyProperties)
        props.load(in)
        in.close()

        for (Iterator iter = props.keySet().iterator() iter.hasNext()) {
            String depMridStr = (String) iter.next()
            String[] parts = props.getProperty(depMridStr).split(" ")
            ModuleRevisionId decodedMrid = ModuleRevisionId.decode(depMridStr)
            if (options.isResolveDynamicRevisions()) {
                resolvedRevisions.put(decodedMrid, parts[0])
                if (parts.length >= 4) {
                    if (parts[3] != null && !"null".equals(parts[3])) {
                        resolvedBranches.put(decodedMrid, parts[3])
                    }
                }
            }
            dependenciesStatus.put(decodedMrid, parts[1])

            if (options.isReplaceForcedRevisions()) {
                if (parts.length <= 2) {
                    // maybe the properties file was generated by an older Ivy version
                    // so it is possible that this part doesn't exist.
                    throw new IllegalStateException("ivy properties file generated by an older" +
                    		" version of Ivy which doesn't support replacing forced revisions!")
                }

                resolvedRevisions.put(decodedMrid, parts[2])
            }
        }
 */

/*
ResolutionCache:45

// XXX: this method is required by ResolutionCacheManager in Ivy 2.3.0 final,
  // but it is apparently unused by Ivy as sbt uses Ivy.  Therefore, it is
  // unexercised in tests.  Note that the implementation of this method in Ivy 2.3.0's
  // DefaultResolutionCache also resolves parent properties for a given mrid
  def getResolvedModuleDescriptor(mrid: ModuleRevisionId): ModuleDescriptor = {
    val ivyFile = getResolvedIvyFileInCache(mrid)
    if (!ivyFile.exists()) {
      throw new IllegalStateException("Ivy file not found in cache for " + mrid + "!")
    }

    XmlModuleDescriptorParser.getInstance().parseDescriptor(settings, ivyFile.toURI.toURL, false)
  }
 */
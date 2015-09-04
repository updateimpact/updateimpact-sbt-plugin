package com.updateimpact

import java.awt.Desktop
import java.net.URI
import java.util.{Collections, UUID}

import com.updateimpact.report._
import org.apache.ivy.core.module.id.ModuleRevisionId
import sbt._
import sbt.Keys._

import scala.collection.JavaConverters._

object Plugin extends AutoPlugin {
  object autoImport {
    val updateImpactApiKey = settingKey[String]("The api key to access UpdateImpact")

    val updateImpactBaseUrl = settingKey[String]("The base UpdateImpact URL")

    val updateImpactOpenBrowser = settingKey[Boolean]("Should the default browser be " +
      "opened with the results after the build completes")

    val updateImpactBuildId = settingKey[UUID]("Unique id of this build")

    val updateImpactConfigs = settingKey[List[Configuration]]("Configurations for which to generate and submit dependencies")

    val updateImpactRootProjectId = taskKey[String]("The id of the root project, from which the name and apikeys are taken")

    val updateImpactSubmit = taskKey[Unit]("Submit the dependency report to UpdateImpact for all projects " +
      "and optionally open the browser with the results")
  }

  val apiKey = autoImport.updateImpactApiKey
  val baseUrl = autoImport.updateImpactBaseUrl
  val openBrowser = autoImport.updateImpactOpenBrowser
  val buildId = autoImport.updateImpactBuildId
  val configs = autoImport.updateImpactConfigs
  val rootProjectId = autoImport.updateImpactRootProjectId
  val submit = autoImport.updateImpactSubmit

  val dependencies = taskKey[List[ModuleDependencies]]("Compute module dependencies for a single project")
  val dependencyReport = taskKey[DependencyReport]("Create the dependency report for all of the projects")

  // We need a mapping from SBT's ModuleIDs, where for the projects in the build the artifact names do not contain
  // the _2.11 (scala version) suffix, to the artifact name that is used in Ivy (with the suffix)
  private val projectIdToIvyIdEntry = taskKey[(ModuleID, ModuleRevisionId)]("")
  private val projectIdToIvyIdEntryImpl = projectIdToIvyIdEntry := {
    projectID.value -> ivyModule.value.moduleDescriptor(streams.value.log).getModuleRevisionId
  }

  private val projectIdToIvyId = taskKey[Map[ModuleID, ModuleRevisionId]]("")
  private val projectIdToIvyIdImpl = projectIdToIvyId := {
    projectIdToIvyIdEntry.all(ScopeFilter(inAnyProject)).value.toMap
  }

  private val cfgWithCp = Def.taskDyn {
    val cfgs = configs.value
    Def.task { (configuration.value, classpathConfiguration.value, fullClasspath.value) }
      .all(ScopeFilter(configurations = inConfigurations(cfgs: _*)))
  }

  val dependenciesImpl = dependencies := {
    update.value

    val log = streams.value.log
    val md = ivyModule.value.moduleDescriptor(log)
    val pitii = projectIdToIvyId.value

    ivySbt.value.withIvy(log) { ivy =>
      val cmd = new CreateModuleDependencies(ivy, log, md, pitii)

      (for {
        (cfg, cpCfg, cp) <- cfgWithCp.value
      } yield {
          cmd.forClasspath(cfg, cpCfg, cp)
        }).toList
    }
  }

  val rootProjectIdImpl = rootProjectId := {
    // Trying to find the "root project" using a heuristic: from all the projects that aggregate other projects,
    // looking for the one with the shortest path (longer paths probably mean subprojects).
    val allProjects = buildStructure.value.allProjects
    val withAggregate = allProjects.filter(_.aggregate.nonEmpty)
    (if (withAggregate.nonEmpty) withAggregate else allProjects)
      .sortBy(_.base.getAbsolutePath.length)
      .head
      .id
  }

  val dependencyReportImpl = dependencyReport := {
    val Some((_, (rootProjectName, ak))) = thisProject.zip(name.zip(apiKey))
      .all(ScopeFilter(inAnyProject)).value
      .find(_._1.id == rootProjectId.value)
    if (ak == "") throw new IllegalStateException("Please define the api key. You can find it on UpdateImpact.com")

    val moduleDependencies = dependencies.all(ScopeFilter(inAnyProject)).value.flatten

    new DependencyReport(
      rootProjectName,
      ak,
      buildId.value.toString,
      moduleDependencies.asJavaCollection,
      Collections.emptyList(),
      "1.0",
      s"sbt-plugin-${UpdateimpactSbtBuildInfo.version}")
  }

  override def projectSettings = Seq(
    dependenciesImpl,
    projectIdToIvyIdEntryImpl,
    projectIdToIvyIdImpl
  )

  override def buildSettings = Seq(
    apiKey := "",
    baseUrl := "https://app.updateimpact.com",
    openBrowser := true,
    buildId := UUID.randomUUID(),
    configs := List(Compile, Test, Runtime),
    rootProjectIdImpl,
    dependencyReportImpl,
    submit := {
      val slog = streams.value.log
      val log = new SubmitLogger {
        override def error(message: String) = slog.error(message)
        override def info(message: String) = slog.info(message)
      }
      val dr = dependencyReport.value
      Option(new ReportSubmitter(baseUrl.value, log).trySubmitReport(dr)).foreach { viewLink =>
        if (openBrowser.value) {
          log.info("Trying to open the report in the default browser ... " +
            "(you can disable this by setting `updateImpactOpenBrowser in ThisBuild` to false)")
          openWebpage(viewLink)
        }
      }
    }
  )

  override def trigger = allRequirements

  // http://stackoverflow.com/questions/10967451/open-a-link-in-browser-with-java-button
  private def openWebpage(url: String) {
    val desktop = if (Desktop.isDesktopSupported) Desktop.getDesktop else null
    if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
      desktop.browse(URI.create(url))
    }
  }
}

package com.updateimpact

import java.awt.Desktop
import java.net.URI
import java.util.UUID

import com.google.gson.Gson
import sbt._
import sbt.Keys._

import scala.collection.JavaConversions._

object Plugin extends AutoPlugin {
  object autoImport {
    val updateImpactApiKey = settingKey[String]("The api key to access UpdateImpact")

    val updateImpactBaseUrl = settingKey[String]("The base UpdateImpact URL")

    val updateImpactOpenBrowser = settingKey[Boolean]("Should the default browser be " +
      "opened with the results after the build completes")

    val updateImpactBuildId = settingKey[UUID]("Unique id of this build")

    val updateImpactConfigs = settingKey[List[Configuration]]("Configurations for which to generate and submit dependencies")

    val updateImpactRootProjectId = taskKey[String]("The id of the root project, from which the name and apikeys are taken")

    val updateImpactIvyReports = taskKey[Map[Configuration, File]]("Returns the paths to the ivy reports with the dependency graphs")

    val updateImpactModuleDependencies = taskKey[Set[ModuleDependencies]]("Parse the ivy reports to create descriptions " +
      "of the dependencies of the current project in the given configurations")

    val updateImpactDependencyReport = taskKey[DependencyReport]("Create the dependency report for all of the projects")

    val updateImpactSubmit = taskKey[Unit]("Submit the dependency report to UpdateImpact for all projects " +
      "and optionally open the browser with the results")
  }

  val apiKey = autoImport.updateImpactApiKey
  val openBrowser = autoImport.updateImpactOpenBrowser

  import autoImport._

  val ivyReportsImpl = updateImpactIvyReports := {
    // based on https://github.com/jrudolph/sbt-dependency-graph
    type HasModule = {
      val module: ModuleID
    }
    def ivyModuleName(ivyModule: IvySbt#Module) = {
      ivyModule.moduleSettings match {
        case hm: HasModule => hm.module.name
        case _ => throw new IllegalStateException(
          s"Cannot read module name from ivy configuration ${ivyModule.moduleSettings}")
      }
    }

    // generates the report
    update.value

    val configs = updateImpactConfigs.value

    val reports = configs.map { c =>
      val reportName = s"${projectID.value.organization}-${ivyModuleName(ivyModule.value)}-${c.name}.xml"
      val report = target.value / "resolution-cache" / "reports" / reportName

      if (!report.exists()) {
        throw new IllegalStateException(s"Cannot find ivy report at $report!")
      }
      c -> report
    }

    reports.toMap
  }

  val moduleDependenciesImpl = updateImpactModuleDependencies := {
    val ar = artifact.value
    updateImpactIvyReports.value.map { case (config, report) =>
      new ParseIvyReport(streams.value.log).parse(report, ar, config)
    }.toSet
  }

  val rootProjectIdImpl = updateImpactRootProjectId := {
    // Trying to find the "root project" using a heuristic: from all the projects that aggregate other projects,
    // looking for the one with the shortest path (longer paths probably mean subprojects).
    val allProjects = buildStructure.value.allProjects
    val withAggregate = allProjects.filter(_.aggregate.nonEmpty)
    (if (withAggregate.nonEmpty) withAggregate else allProjects)
      .sortBy(_.base.getAbsolutePath.length)
      .head
      .id
  }

  val dependencyReportImpl = updateImpactDependencyReport := {
    val moduleDependencies = updateImpactModuleDependencies.all(ScopeFilter(inAnyProject)).value.toSet.flatten

    val Some((_, (rootProjectName, apiKey))) = thisProject.zip(name.zip(updateImpactApiKey))
      .all(ScopeFilter(inAnyProject)).value
      .find(_._1.id == updateImpactRootProjectId.value)
    if (apiKey == "") throw new IllegalStateException("Please define the api key. You can find it on UpdateImpact.com")

    new DependencyReport(
      rootProjectName,
      apiKey,
      updateImpactBuildId.value.toString,
      FixInterProjectDependencies(moduleDependencies),
      "1.0",
      "sbt-plugin-1.0.4")
  }

  override def projectSettings = Seq(
    ivyReportsImpl,
    moduleDependenciesImpl
  )

  override def buildSettings = Seq(
    updateImpactApiKey := "",
    updateImpactBaseUrl := "https://app.updateimpact.com",
    updateImpactOpenBrowser := true,
    updateImpactBuildId := UUID.randomUUID(),
    updateImpactConfigs := List(Compile, Test),
    rootProjectIdImpl,
    dependencyReportImpl,
    updateImpactSubmit := {
      val slog = streams.value.log
      val log = new SubmitLogger {
        override def error(message: String) = slog.error(message)
        override def info(message: String) = slog.info(message)
      }
      val dr = updateImpactDependencyReport.value
      val reportJson = new Gson().toJson(dr)
      Option(new ReportSubmitter(updateImpactBaseUrl.value, log).trySubmitReport(reportJson)).foreach { viewLink =>
        if (updateImpactOpenBrowser.value) {
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

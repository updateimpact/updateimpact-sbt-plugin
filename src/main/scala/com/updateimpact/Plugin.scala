package com.updateimpact

import java.awt.Desktop
import java.net.URI
import java.util.UUID

import sbt._
import sbt.Keys._

object Plugin extends AutoPlugin {
  object autoImport {
    val updateImpactApiKey = settingKey[String]("The api key to access UpdateImpact")

    val updateImpactBaseUrl = settingKey[String]("The base UpdateImpact URL")

    val updateImpactSubmitUrl = settingKey[String]("The URL where results will be submitted")

    val updateImpactOpenBrowser = settingKey[Boolean]("Should the default browser be " +
      "opened with the results after the build completes")

    val updateImpactBuildId = settingKey[UUID]("Unique id of this build")

    val updateImpactRootProjectId = taskKey[String]("The id of the root project, from which the name and apikeys are taken")

    val updateImpactIvyReport = taskKey[File]("Returns the path to the ivy report with the dependency graph")

    val updateImpactModuleDependencies = taskKey[ModuleDependencies]("Parse the ivy report to create a description " +
      "of the dependencies of the current project")

    val updateImpactDependencyReport = taskKey[DependencyReport]("Create the dependency report for all of the projects")

    val updateImpactDependencies = taskKey[Unit]("Send the dependency report to UpdateImpact for all projects " +
      "and optionally open the browser with the results")
  }

  val apiKey = autoImport.updateImpactApiKey

  import autoImport._

  val ivyReportImpl = updateImpactIvyReport := {
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

    val config = Compile

    val reportName = s"${projectID.value.organization}-${ivyModuleName(ivyModule.value)}-${config.name}.xml"
    val report = target.value / "resolution-cache" / "reports" / reportName
    if (!report.exists()) {
      throw new IllegalStateException(s"Cannot find ivy report at $report!")
    }

    report
  }

  val moduleDependenciesImpl = updateImpactModuleDependencies := {
    new ParseIvyReport(streams.value.log).parse(updateImpactIvyReport.value, artifact.value)
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
    val moduleDependencies = updateImpactModuleDependencies.all(ScopeFilter(inAnyProject)).value

    val Some((_, (rootProjectName, apiKey))) = thisProject.zip(name.zip(updateImpactApiKey))
      .all(ScopeFilter(inAnyProject)).value
      .find(_._1.id == updateImpactRootProjectId.value)
    if (apiKey == "") throw new IllegalStateException("Please define the api key. You can find it on UpdateImpact.com")

    DependencyReport(
      rootProjectName,
      apiKey,
      updateImpactBuildId.value.toString,
      "1.0",
      moduleDependencies.toSet)
  }

  override def projectSettings = Seq(
    ivyReportImpl,
    moduleDependenciesImpl
  )

  override def buildSettings = Seq(
    updateImpactApiKey := "",
    updateImpactBaseUrl := "https://app.updateimpact.com",
    updateImpactSubmitUrl := updateImpactBaseUrl.value + "/rest/submit",
    updateImpactOpenBrowser := true,
    updateImpactBuildId := UUID.randomUUID(),
    rootProjectIdImpl,
    dependencyReportImpl,
    updateImpactDependencies := {
      val log = streams.value.log
      val dr = updateImpactDependencyReport.value
      new ReportSender(log).send(dr.toJson, updateImpactBaseUrl.value, updateImpactSubmitUrl.value).foreach { viewLink =>
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

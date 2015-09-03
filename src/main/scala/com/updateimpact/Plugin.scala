package com.updateimpact

import java.awt.Desktop
import java.net.URI
import java.util.UUID

import com.updateimpact.report._
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.resolve.{IvyNode, ResolveOptions}
import sbt._
import sbt.Keys._

import scala.collection.JavaConverters._
import scala.xml.XML

object Plugin extends AutoPlugin {
  object autoImport {
    val updateImpactApiKey = settingKey[String]("The api key to access UpdateImpact")

    val updateImpactBaseUrl = settingKey[String]("The base UpdateImpact URL")

    val updateImpactOpenBrowser = settingKey[Boolean]("Should the default browser be " +
      "opened with the results after the build completes")

    val updateImpactBuildId = settingKey[UUID]("Unique id of this build")

    val updateImpactConfigs = settingKey[List[Configuration]]("Configurations for which to generate and submit dependencies")

    val updateImpactRootProjectId = taskKey[String]("The id of the root project, from which the name and apikeys are taken")

    val updateImpactIvyReportFiles = taskKey[Map[Configuration, File]]("Returns the paths to the ivy reports with the dependency graphs")

    // either updateImpactIvyReports or updateImpactDependenciesFromUpdate should be used
    val updateImpactIvyReports = taskKey[List[ModuleIvyReport]]("Returns the ivy reports")

    val updateImpactDependenciesFromUpdate = taskKey[List[ModuleDependencies]]("Returns module dependencies constructed from the update report")

    val updateImpactModuleReport = taskKey[Either[List[ModuleDependencies], List[ModuleIvyReport]]]("Returns a report for a single project")

    val updateImpactDependencyReport = taskKey[DependencyReport]("Create the dependency report for all of the projects")

    val updateImpactSubmit = taskKey[Unit]("Submit the dependency report to UpdateImpact for all projects " +
      "and optionally open the browser with the results")
  }

  val apiKey = autoImport.updateImpactApiKey
  val openBrowser = autoImport.updateImpactOpenBrowser
  val configs = autoImport.updateImpactConfigs
  val baseUrl = autoImport.updateImpactBaseUrl

  import autoImport._

  val ivyReportFilesImpl = updateImpactIvyReportFiles := {
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

    val reports: List[(Configuration, File)] = configs.flatMap { c =>
      val reportName = s"${projectID.value.organization}-${ivyModuleName(ivyModule.value)}-${c.name}.xml"
      val report = target.value / "resolution-cache" / "reports" / reportName

      if (!report.exists()) {
        streams.value.log.warn(s"Cannot find ivy report for project ${name.value} and configuration " +
          s"$c at path $report")

        None
      } else {
        Some(c -> report)
      }
    }

    reports.toMap
  }

  val ivyReportsImpl = updateImpactIvyReports := {
    val ar = artifact.value
    updateImpactIvyReportFiles.value.map { case (config, report) =>
      ModuleIvyReport.newWithReport(extractRootId(report, ar), config.name, report)
    }.toList
  }

  private def extractRootId(report: File, artifact: Artifact): DependencyId = {
    val root = XML.loadFile(report)

    val info = (root \ "info").head
    new DependencyId(
      info.attribute("organisation").get.text,
      info.attribute("module").get.text,
      info.attribute("revision").get.text,
      artifact.`type`,
      artifact.classifier.orNull
    )
  }

  val ivyEntry = taskKey[(ModuleID, ModuleRevisionId)]("")
  val ivyEntryImpl = ivyEntry := {
    projectID.value -> ivyModule.value.moduleDescriptor(streams.value.log).getModuleRevisionId
  }

  val ivyMap = taskKey[Map[ModuleID, ModuleRevisionId]]("")
  val ivyMapImpl = ivyMap := {
    ivyEntry.all(ScopeFilter(inAnyProject)).value.toMap
  }

  val dependenciesFromUpdateImpl = updateImpactDependenciesFromUpdate := {
    val report = update.value
    val configs = updateImpactConfigs.value.map(_.name).toSet
    val log = streams.value.log

    ivySbt.value.withIvy(log) { ivy =>

      val im = ivyMap.value

      val md = new CreateModuleDependencies(
        ivy,
        new FindDependencyDescriptors(ivy),
        log)
        .forClasspath(
          ivyModule.value.moduleDescriptor(streams.value.log),
          im,
          (classpathConfiguration in Compile).value,
          (fullClasspath in Compile).value
        )

      List(md)

      /*
      classpathConfiguration.value

      (fullClasspath in Compile).value.foreach { af =>
        println(af.data.getPath)
        af.metadata.entries.foreach { e =>
          println(s"   ${e.key} -> ${e.value}")
        }

        af.metadata.get(moduleID.key) match {
          case Some(mid) =>
            val mrid = ModuleRevisionId.newInstance(mid.organization, mid.name, mid.revision)

            val resolveOptions = new ResolveOptions
            resolveOptions.setResolveId(ResolveOptions.getDefaultResolveId(mrid.getModuleId))
            //resolveOptions.setLog(ivyLogLevel(configuration.logging))
            resolveOptions.setDownload(false)
            resolveOptions.setRefresh(false)
            resolveOptions.setCheckIfChanged(false)
            resolveOptions.setOutputReport(false)
            resolveOptions.setUseCacheOnly(true)

            val m = ivy.getResolveEngine.findModule(mrid, resolveOptions)
            println("Module " + m)

            if (m != null) {
              m.getDescriptor.getDependencies.foreach { d =>
                println("    " + d.getDependencyRevisionId + ", " + d.getDependencyConfigurations("compile").toList)
              }
            }



          /*val deps = ivy.resolve(mrid, resolveOptions, false).getDependencies
          val it = deps.iterator()
          while (it.hasNext()) {
            val n = it.next().asInstanceOf[IvyNode]
            println("        " + n.getId)
          }               */

          case None => println("No module id for " + af.data.getPath)
        }

        //af.metadata.get(artifact.key)

      } */

//
//      TransformConfigurationReports.transform(
//        ivyModule.value.moduleDescriptor(log).getModuleRevisionId,
//        ivy.getResolutionCacheManager,
//        ivy.getSettings,
//        report.configurations.filter(c => configs.contains(c.configuration)))
    }
  }

  val moduleReportImpl = updateImpactModuleReport := {
    val isCached = updateOptions.value.cachedResolution
    if (isCached) {
      println("L")
      Left(updateImpactDependenciesFromUpdate.value)
    } else {
      println("R")
      //Right(updateImpactIvyReports.value)
      Left(updateImpactDependenciesFromUpdate.value)
    }
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
    val Some((_, (rootProjectName, apiKey))) = thisProject.zip(name.zip(updateImpactApiKey))
      .all(ScopeFilter(inAnyProject)).value
      .find(_._1.id == updateImpactRootProjectId.value)
    if (apiKey == "") throw new IllegalStateException("Please define the api key. You can find it on UpdateImpact.com")

    val (moduleDependencies, ivyReports) = updateImpactModuleReport.all(ScopeFilter(inAnyProject)).value
      .foldLeft((Nil: List[ModuleDependencies], Nil: List[ModuleIvyReport])) {
      case ((allMds, allIrs), Left(mds)) => (allMds ++ mds, allIrs)
      case ((allMds, allIrs), Right(irs)) => (allMds, allIrs ++ irs)
    }

    new DependencyReport(
      rootProjectName,
      apiKey,
      updateImpactBuildId.value.toString,
      moduleDependencies.asJavaCollection,
      ivyReports.asJavaCollection,
      "1.0",
      s"sbt-plugin-${UpdateimpactSbtBuildInfo.version}")
  }

  override def projectSettings = Seq(
    dependenciesFromUpdateImpl,
    ivyReportFilesImpl,
    ivyReportsImpl,
    moduleReportImpl,
    ivyEntryImpl,
    ivyMapImpl
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
      Option(new ReportSubmitter(updateImpactBaseUrl.value, log).trySubmitReport(dr)).foreach { viewLink =>
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

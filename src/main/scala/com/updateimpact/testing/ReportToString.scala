package com.updateimpact.testing

import com.updateimpact.report.DependencyReport
import scala.collection.JavaConversions._

object ReportToString {
  def toString(dr: DependencyReport): String = {
    dr.getModuleDependencies.groupBy(_.getConfig).toList.sortBy(_._1).map { case (config, modules) =>
        modules.toList.sortBy(_.getModuleId.toString).map { m =>
          val h1 = s"${m.getModuleId} in ${m.getConfig}\n"

          h1 + m.getDependencies.toList.sortBy(_.getId.toString).map { d =>
            val h2 = s"   ${d.getId}${if (d.getEvictedByVersion != null) s" evicted by ${d.getEvictedByVersion}" else ""}"

            val chld = d.getChildren.toList.sortBy(_.toString).map { c =>
              "      " + c.toString
            }

            if (chld.isEmpty) h2 else h2 + "\n" + chld.mkString("\n")
          }.mkString("\n")
        }.mkString("\n")
    }.mkString("")
  }
}

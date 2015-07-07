package com.updateimpact

import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils
import sbt.Logger

class ReportSubmitter(log: Logger) {
  def submit(report: String, baseUrl: String, submitUrl: String): Option[String] = {
    log.info("")
    log.info(s"Submitting dependency report to: $submitUrl")

    val httpClient = HttpClientBuilder.create().build()
    val (responseJson, statusCode) = try {
      val post = new HttpPost(submitUrl)
      post.setEntity(new StringEntity(report))
      val response = httpClient.execute(post)

      (EntityUtils.toString(response.getEntity), response.getStatusLine.getStatusCode)
    } finally {
      httpClient.close()
    }

    if (statusCode < 200 || statusCode > 300) {
      log.error(s"Cannot submit report to $submitUrl, got response $statusCode: $responseJson")
      None
    } else {
      val sr = SubmitResponse.fromJson(responseJson)
      val viewLink = sr.viewLink(baseUrl)

      log.info("")
      log.info("Dependency report submitted. You can view it at: ")
      log.info(viewLink)
      log.info("")

      Some(viewLink)
    }
  }
}

case class SubmitResponse(userIdStr: String, buildId: String) {
  def viewLink(baseUrl: String) = baseUrl + "/#/builds/" + userIdStr + "/" + buildId
}

object SubmitResponse {
  def fromJson(json: String) = {
    import rapture.json.Json
    import rapture.json.jsonBackends.json4s._

    Json.parse(json).as[SubmitResponse]
  }
}
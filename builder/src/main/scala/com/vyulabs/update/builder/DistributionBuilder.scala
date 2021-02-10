package com.vyulabs.update.builder

import com.vyulabs.update.common.common.Common
import com.vyulabs.update.common.common.Common.{DistributionName, ServiceName}
import com.vyulabs.update.common.config.DistributionConfig
import com.vyulabs.update.common.distribution.client.{DistributionClient, HttpClientImpl, SyncDistributionClient, SyncSource}
import com.vyulabs.update.common.distribution.server.SettingsDirectory
import com.vyulabs.update.common.process.ProcessUtils
import com.vyulabs.update.common.utils.IoUtils
import com.vyulabs.update.common.version.{ClientDistributionVersion, ClientVersion, DeveloperDistributionVersion, DeveloperVersion}
import org.slf4j.{Logger, LoggerFactory}

import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 04.02.19.
  * Copyright FanDate, Inc.
  */
class DistributionBuilder(builderDir: File, setupDistributionScript: String,
                          cloudProvider: String, distributionDir: File, distributionName: String, distributionTitle: String, mongoDbName: String) {
  implicit val log = LoggerFactory.getLogger(this.getClass)

  private val developerBuilder = new DeveloperBuilder(builderDir, distributionName)
  private val clientBuilder = new ClientBuilder(builderDir, distributionName)

  private val settingsDirectory = new SettingsDirectory(builderDir, distributionName)

  private val initialVersion = DeveloperVersion(Seq(1, 0, 0))
  private val initialDeveloperVersion = DeveloperDistributionVersion(distributionName, initialVersion)
  private val initialClientVersion = ClientDistributionVersion(distributionName, ClientVersion(initialVersion))

  def buildDistributionFromSources(author: String)
                                   (implicit executionContext: ExecutionContext): Boolean = {
    log.info(s"------------------------------ Generate initial versions of services -----------------------------------")
    if (!generateInitVersions(Seq(Common.ScriptsServiceName, Common.BuilderServiceName, Common.DistributionServiceName))) {
      return false
    }

    log.info(s"---------------------------------- Install distribution service ----------------------------------------")
    if (!installDistributionService()) {
      log.error("Can't install distribution service")
      return false
    }

    log.info(s"------------------------------------ Start distribution service ----------------------------------------")
    if (!startDistributionService()) {
      log.error("Can't start distribution service")
      return false
    }

    log.info(s"------------------------ Waiting for distribution service became available -----------------------------")
    val config = DistributionConfig.readFromFile(new File(distributionDir, Common.DistributionConfigFileName)).getOrElse {
      log.error(s"Can't read distribution config file in the directory ${distributionDir}")
      return false
    }
    val protocol = if (config.network.ssl.isDefined) "https" else "http"
    val port = config.network.port
    val distributionUrl: URL = new URL(protocol, "localhost", port, "")

    val distributionClient = new SyncDistributionClient(
      new DistributionClient(distributionName, new HttpClientImpl(distributionUrl)), FiniteDuration(60, TimeUnit.SECONDS))
    if (!waitForServerAvailable(distributionClient, 10000)) {
      log.error("Can't start distribution service")
      return false
    }

    // TODO init desired versions

    true
  }

  def buildFromDeveloperDistribution(developerDistributionName: DistributionName): Boolean = {
    false
  }

  def installDistributionService(): Boolean = {
    if (!IoUtils.copyFile(new File(clientBuilder.clientBuildDir(Common.ScriptsServiceName), "distribution"), distributionDir) ||
        !IoUtils.copyFile(new File(clientBuilder.clientBuildDir(Common.ScriptsServiceName), Common.UpdateSh), new File(distributionDir, Common.UpdateSh)) ||
        !IoUtils.copyFile(clientBuilder.clientBuildDir(Common.DistributionServiceName), distributionDir)) {
      return false
    }
    if (!IoUtils.writeServiceVersion(distributionDir, Common.ScriptsServiceName, initialClientVersion)) {
      return false
    }
    if (!IoUtils.writeServiceVersion(distributionDir, Common.DistributionServiceName, initialClientVersion)) {
      return false
    }
    true
  }

  def startDistributionService(): Boolean = {
    log.info(s"Setup and run distribution server")
    val arguments = Seq.empty[String]
    if (!ProcessUtils.runProcess("/bin/sh", setupDistributionScript +: arguments, Map.empty,
        distributionDir, Some(0), None, ProcessUtils.Logging.Realtime)) {
      log.error(s"Run distribution server error")
      return false
    }
    true
  }

  def waitForServerAvailable(distributionClient: SyncDistributionClient[SyncSource], waitingTimeoutSec: Int = 10000)
                                  (implicit log: Logger): Boolean = {
    log.info(s"Wait for distribution server become available")
    for (_ <- 0 until waitingTimeoutSec) {
      if (distributionClient.available()) {
        return true
      }
      Thread.sleep(1000)
    }
    log.error(s"Timeout of waiting for distribution server become available")
    false
  }

  private def generateInitVersions(serviceNames: Seq[ServiceName]): Boolean = {
    for (serviceName <- serviceNames) {
      log.info(s"---------------- Generate init version of service ${serviceName} -----------------")
      log.info(s"Generate developer version ${initialDeveloperVersion} for service ${serviceName}")
      val arguments = Map.empty + ("version" -> initialDeveloperVersion.toString)
      if (!developerBuilder.generateDeveloperVersion(serviceName, new File("."), arguments)) {
        log.error(s"Can't generate developer version for service ${serviceName}")
        return false
      }

      log.info(s"Copy developer init version of service ${serviceName} to client directory")
      if (!IoUtils.copyFile(developerBuilder.developerBuildDir(serviceName), clientBuilder.clientBuildDir(serviceName))) {
        log.error(s"Can't copy ${developerBuilder.developerBuildDir(serviceName)} to ${clientBuilder.clientBuildDir(serviceName)}")
        return false
      }

      log.info(s"Generate client version ${initialClientVersion} for service ${serviceName}")
      if (!clientBuilder.generateClientVersion(serviceName, Map.empty)) {
        log.error(s"Can't generate client version for service ${serviceName}")
        return false
      }
    }
    true
  }
}

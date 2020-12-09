package com.vyulabs.update.builder

import com.vyulabs.update.builder.InstallResult.InstallResult
import com.vyulabs.update.common.Common
import com.vyulabs.update.common.Common.ServiceName
import com.vyulabs.update.distribution.AdminRepository
import com.vyulabs.update.distribution.client.graphql.AdministratorGraphqlCoder.{administratorMutations, administratorQueries}
import com.vyulabs.update.distribution.client.{OldDistributionInterface, SyncDistributionClient}
import com.vyulabs.update.info.{BuildInfo, ClientDesiredVersions, ClientVersionInfo, DeveloperDesiredVersions, InstallInfo}
import com.vyulabs.update.settings.{ConfigSettings, DefinesSettings}
import com.vyulabs.update.utils.IoUtils
import com.vyulabs.update.version.{ClientDistributionVersion, DeveloperDistributionVersion}
import com.vyulabs.update.distribution.client.graphql.DistributionGraphqlCoder._
import org.slf4j.Logger

import java.io.File
import spray.json.DefaultJsonProtocol._

import java.util.Date
import javax.print.DocFlavor.URL

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 04.02.19.
  * Copyright FanDate, Inc.
  */
class UpdateClient()(implicit log: Logger) {
  private val buildDir = new File("build")
  private val indexPattern = "(.*)\\.([0-9]*)".r

  def installUpdates(adminRepository: AdminRepository,
                     clientDistribution: SyncDistributionClient,
                     developerDistribution: SyncDistributionClient,
                     servicesOnly: Set[ServiceName],
                     localConfigOnly: Boolean,
                     assignDesiredVersions: Boolean,
                     clientDistributionUrl: URL): InstallResult = {
    var clientVersions = Map.empty[ServiceName, ClientDistributionVersion]
    try {
      if (buildDir.exists() && !IoUtils.deleteFileRecursively(buildDir)) {
        log.error(s"Can't remove directory ${buildDir}")
        return InstallResult.Failure
      }
      if (!buildDir.mkdir()) {
        log.error(s"Can't make directory ${buildDir}")
        return InstallResult.Failure
      }
      log.info("Get distribution client config")
      val clientConfig = developerDistribution.graphqlRequest(distributionQueries.getDistributionClientConfig()).getOrElse {
        log.error(s"Can't get distribution client config")
        return InstallResult.Failure
      }
      if (clientConfig.testDistributionMatch.isDefined && !servicesOnly.isEmpty && !localConfigOnly) {
        log.error("You may use option servicesOnly only with localConfigOnly for client that requires preliminary testing")
        return InstallResult.Failure
      }
      log.info("Get distribution client desired versions")
      val clientDesiredVersions = clientDistribution.graphqlRequest(administratorQueries.getClientDesiredVersions(servicesOnly.toSeq))
          .map(ClientDesiredVersions.toMap(_)).getOrElse {
        log.warn(s"Can't get distribution client desired versions")
        return InstallResult.Failure
      }
      var developerVersions = if (!localConfigOnly) {
        log.info("Get developer desired versions")
        developerDistribution.graphqlRequest(distributionQueries.getDesiredVersions(servicesOnly.toSeq))
            .map(DeveloperDesiredVersions.toMap(_)).getOrElse {
          log.error(s"Can't get developer desired versions.")
          if (clientConfig.testDistributionMatch.isDefined) {
            log.error("May be developer desired versions are not tested")
          }
          return InstallResult.Failure
        }
      } else {
        clientDesiredVersions.mapValues(_.original())
      }
      log.info("Define versions to install")
      for (servicesOnly <- servicesOnly) {
        developerVersions = developerVersions.filterKeys(servicesOnly.contains(_))
      }
      developerVersions.foreach {
        case (serviceName, developerVersion) =>
          val existingVersions = clientDistribution.graphqlRequest(administratorQueries.getClientVersionsInfo(serviceName)).getOrElse {
            log.error(s"Error of getting service ${serviceName} versions list")
            return InstallResult.Failure
          }.map(_.version)
           .filter(_.original() == developerVersion)
          val clientVersion =
            if (!localConfigOnly) {
              if (!existingVersions.isEmpty) {
                developerVersions -= serviceName
                clientDesiredVersions.get(serviceName) match {
                  case Some(clientVersion) if (developerVersion == clientVersion.original()) =>
                    clientVersion
                  case _ =>
                    existingVersions.sorted(ClientDistributionVersion.ordering.reverse).find(developerVersion == _.original()) match {
                      case Some(existingVersion) =>
                        existingVersion
                      case None =>
                        ClientDistributionVersion(developerVersion)
                    }
                }
              } else {
                ClientDistributionVersion(developerVersion)
              }
            } else {
              if (!existingVersions.isEmpty) {
                existingVersions.sorted(ClientDistributionVersion.ordering).last.next()
              } else {
                ClientDistributionVersion(developerVersion)
              }
            }
          clientVersions += (serviceName -> clientVersion)
      }
      log.info("Install updates")
      installVersions(adminRepository, clientDistribution, developerDistribution,
        developerVersions, clientVersions, assignDesiredVersions, clientDistributionUrl)
    } catch {
      case ex: Exception =>
        log.error("Exception", ex)
        InstallResult.Failure
    }
  }

  def waitForServerUpdated(distributionClient: SyncDistributionClient,
                           serviceName: ServiceName, desiredVersion: ClientDistributionVersion, waitingTimeoutSec: Int = 10000): Boolean = {
    log.info(s"Wait for distribution server updated")
    for (_ <- 0 until waitingTimeoutSec) {
      if (distributionClient.available()) {
        distributionClient.getServiceVersion(serviceName) match {
          case Some(version) =>
            if (version == desiredVersion) {
              log.info(s"Distribution server is updated")
              return true
            }
          case None =>
            return false
        }
      }
      Thread.sleep(1000)
    }
    log.error(s"Timeout of waiting for distribution server become available")
    false
  }

  def setDesiredVersions(clientDistribution: SyncDistributionClient, versions: Map[ServiceName, Option[ClientDistributionVersion]]): Boolean = {
    val desiredVersionsMap = clientDistribution.graphqlRequest(administratorQueries.getClientDesiredVersions()) {
      log.error("Error of getting desired versions")
      return false
    }.map(ClientDesiredVersions.toMap(_)).getOrElse(Map.empty)
    val newVersions =
      versions.foldLeft(desiredVersionsMap) {
        (map, entry) => entry._2 match {
          case Some(version) =>
            map + (entry._1 -> version)
          case None =>
            map - entry._1
        }}
    val desiredVersions = ClientDesiredVersions.fromMap(newVersions)
    if (!clientDistribution.graphqlRequest(administratorMutations.setClientDesiredVersions(desiredVersions)).getOrElse(false)) {
      log.error("Error of uploading desired versions")
      return false
    }
    true
  }

  def signVersionsAsTested(clientDistribution: SyncDistributionClient, developerDistribution: SyncDistributionClient): Boolean = {
    val clientDesiredVersionsMap = clientDistribution.graphqlRequest(administratorQueries.getClientDesiredVersions())
        .map(ClientDesiredVersions.toMap(_)).getOrElse {
      log.error("Error of getting client desired versions")
      return false
    }
    val developerDesiredVersionsMap = developerDistribution.graphqlRequest(distributionQueries.getDesiredVersions())
        .map(DeveloperDesiredVersions.toMap(_)).getOrElse {
      log.error("Error of getting developer desired versions")
      return false
    }
    if (!clientDesiredVersionsMap.filter(_._2.distributionName == developerDistribution.distributionName)
        .mapValues(_.original()).equals(developerDesiredVersionsMap)) {
      log.error("Client versions are different from developer versions:")
      clientDesiredVersionsMap foreach {
        case (serviceName, clientVersion) =>
          developerDesiredVersionsMap.get(serviceName) match {
            case Some(developerVersion) if developerVersion != clientVersion.original() =>
              log.info(s"  service ${serviceName} version ${clientVersion} != ${developerVersion}")
            case _ =>
          }
      }
      developerDesiredVersionsMap foreach {
        case (serviceName, developerVersion) =>
          if (!clientDesiredVersionsMap.get(serviceName).isDefined) {
            log.info(s"  service ${serviceName} version ${developerVersion} is not installed")
          }
      }
      clientDesiredVersionsMap foreach {
        case (serviceName, _) =>
          if (!developerDesiredVersionsMap.get(serviceName).isDefined) {
            log.info(s"  service ${serviceName} is not the developer service")
          }
      }
      return false
    }
    if (!developerDistribution.graphqlRequest(distributionMutations.setTestedVersions(
        DeveloperDesiredVersions.fromMap(clientDesiredVersionsMap.mapValues(_.original())))).getOrElse(false)) {
      log.error("Error of uploading desired versions to developer")
      return false
    }
    true
  }

  private def installVersions(adminRepository: AdminRepository,
                              clientDistribution: SyncDistributionClient,
                              developerDistribution: SyncDistributionClient,
                              developerVersions: Map[ServiceName, DeveloperDistributionVersion],
                              clientVersions: Map[ServiceName, ClientDistributionVersion],
                              assignDesiredVersions: Boolean, clientDistributionUrl: URL): InstallResult = {
    if (assignDesiredVersions && developerVersions.get(Common.InstallerServiceName).isDefined) {
      if (!installVersions(adminRepository, clientDistribution, developerDistribution,
        developerVersions.filterKeys(
          serviceName => {
            serviceName == Common.InstallerServiceName || serviceName == Common.DistributionServiceName
          }), clientVersions, clientDistributionUrl)) {
        return InstallResult.Failure
      }
      return InstallResult.NeedRestartToUpdate
    }
    if (!installVersions(adminRepository, clientDistribution, developerDistribution, developerVersions, clientVersions, clientDistributionUrl)) {
      return InstallResult.Failure
    }
    if (assignDesiredVersions) {
      log.info("Set desired versions")
      if (!setDesiredVersions(clientDistribution, clientVersions.map(entry => (entry._1, Some(entry._2))))) {
        log.error("Set desired versions error")
        return InstallResult.Failure
      }
      clientVersions.get(Common.DistributionServiceName) match {
        case Some(newDistributionVersion) =>
          if (!waitForServerUpdated(clientDistribution, Common.DistributionServiceName, newDistributionVersion)) {
            log.error("Update distribution server error")
            return InstallResult.Failure
          }
        case None =>
          for (newScriptsVersion <- clientVersions.get(Common.ScriptsServiceName)) {
            if (!waitForServerUpdated(clientDistribution, Common.ScriptsServiceName, newScriptsVersion)) {
              log.error("Update scripts on distribution server error")
              return InstallResult.Failure
            }
          }
      }
    }
    InstallResult.Complete
  }

  private def installVersions(adminRepository: AdminRepository,
                              clientDistribution: SyncDistributionClient,
                              developerDistribution: SyncDistributionClient,
                              developerVersions: Map[ServiceName, DeveloperDistributionVersion],
                              clientVersions: Map[ServiceName, ClientDistributionVersion],
                              clientDistributionUrl: URL): Boolean = {
    developerVersions.foreach {
      case (serviceName, version) =>
        if (!installVersion(adminRepository, clientDistribution, developerDistribution, serviceName, version,
            clientVersions.get(serviceName).get, clientDistributionUrl)) {
          log.error(s"Can't install desired version ${version} of service ${serviceName}")
          return false
        }
    }
    true
  }

  private def installVersion(adminRepository: AdminRepository,
                             clientDistribution: SyncDistributionClient,
                             developerDistribution: SyncDistributionClient,
                             serviceName: ServiceName,
                             fromVersion: DeveloperDistributionVersion, toVersion: ClientDistributionVersion,
                             clientDistributionUrl: URL): Boolean = {
    try {
      log.info(s"Download version ${fromVersion} of service ${serviceName}")
      val versionInfo = developerDistribution.graphqlRequest(distributionQueries.getVersionsInfo(serviceName)).getOrElse {
        log.error(s"Can't get version ${fromVersion} of service ${serviceName} info")
        return false
      }.headOption.getOrElse {
        log.error(s"Can't get version ${fromVersion} of service ${serviceName} info")
        return false
      }
      if (!IoUtils.deleteDirectoryContents(buildDir)) {
        log.error(s"Can't remove directory ${buildDir} contents")
        return false
      }
      if (!developerDistribution.downloadDeveloperVersionImage(serviceName, fromVersion, buildDir)) {
        log.error(s"Can't download version ${fromVersion} of service ${serviceName}")
        return false
      }

      if (!Common.isUpdateService(serviceName) && !adminRepository.getServiceDir(serviceName).exists()) {
        log.error(s"Service ${serviceName} directory is not exist in the admin repository")
        return false
      }

      if (!mergeInstallConfigFile(adminRepository, serviceName)) {
        return false
      }

      log.info(s"Configure version ${toVersion} of service ${serviceName}")
      val configDir = adminRepository.getServiceSettingsDir(serviceName)
      if (configDir.exists()) {
        log.info(s"Merge private settings files")
        if (!mergeSettings(clientDistribution, serviceName, buildDir, configDir, toVersion, clientDistributionUrl)) {
          return false
        }
      }

      val privateDir = adminRepository.getServicePrivateDir(serviceName)
      if (privateDir.exists()) {
        log.info(s"Install private files")
        if (!IoUtils.copyFile(privateDir, buildDir)) {
          return false
        }
      }

      log.info(s"Upload version ${toVersion} of service ${serviceName}")
      val clientVersionInfo = ClientVersionInfo(serviceName, toVersion, versionInfo.buildInfo, InstallInfo("user", new Date())) // TODO set userName
      if (!clientDistribution.graphqlRequest(administratorMutations.addClientVersionInfo(clientVersionInfo)).getOrElse(false)) {
        return false
      }
      true
    } catch {
      case ex: Exception =>
        log.error("Install updates error", ex)
        false
    }
  }

  private def mergeInstallConfigFile(adminRepository: AdminRepository, serviceName: ServiceName): Boolean = {
    val buildConfigFile = new File(buildDir, Common.InstallConfigFileName)
    val clientConfigFile = adminRepository.getServiceInstallConfigFile(serviceName)
    if (clientConfigFile.exists()) {
      log.info(s"Merge ${Common.InstallConfigFileName} with client version")
      val clientConfig = IoUtils.parseConfigFile(clientConfigFile).getOrElse(return false)
      if (buildConfigFile.exists()) {
        val buildConfig = IoUtils.parseConfigFile(buildConfigFile).getOrElse(return false)
        val newConfig = clientConfig.withFallback(buildConfig).resolve()
        IoUtils.writeConfigToFile(buildConfigFile, newConfig)
      } else {
        IoUtils.copyFile(buildConfigFile, clientConfigFile)
      }
    } else {
      true
    }
  }

  private def mergeSettings(clientDistribution: SyncDistributionClient,
                            serviceName: ServiceName, buildDirectory: File, localDirectory: File,
                            version: ClientDistributionVersion, clientDistributionUrl: URL, subPath: String = ""): Boolean = {
    for (localFile <- sortConfigFilesByIndex(new File(localDirectory, subPath).listFiles().toSeq)) {
      if (localFile.isDirectory) {
        val newSubPath = subPath + "/" + localFile.getName
        val buildSubDirectory = new File(buildDirectory, newSubPath)
        if (!buildSubDirectory.exists() && !buildSubDirectory.mkdir()) {
          log.error(s"Can't make ${buildSubDirectory}")
          return false
        }
        if (!mergeSettings(clientDistribution, serviceName, buildDirectory, localDirectory, version, clientDistributionUrl, newSubPath)) {
          return false
        }
      } else {
        val name = localFile.getName
        val originalName = getOriginalName(name)
        if (originalName.endsWith(".conf") || originalName.endsWith(".json") || originalName.endsWith(".properties")) {
          val filePath = if (subPath.isEmpty) originalName else subPath + "/" + originalName
          val buildConf = new File(buildDirectory, filePath)
          if (buildConf.exists()) {
            val configSettings = new ConfigSettings(IoUtils.parseConfigFile(localFile).getOrElse {
              return false
            })
            log.info(s"Merge configuration file ${filePath} with local configuration file ${localFile}")
            if (!configSettings.merge(buildConf)) {
              log.error("Merge configuration file error")
              return false
            }
          } else {
            log.info(s"Copy local configuration file ${localFile}")
            if (!IoUtils.copyFile(localFile, buildConf)) {
              return false
            }
          }
        } else if (originalName.endsWith(".defines")) {
          val sourceName = originalName.substring(0, originalName.length-8)
          val filePath = if (subPath.isEmpty) sourceName else subPath + "/" + sourceName
          val buildConf = new File(buildDirectory, filePath)
          var preSettings = Map.empty[String, String]
          preSettings += ("version" -> version.toString)
          preSettings += ("distribDirectoryUrl" -> clientDistributionUrl.toString)
          val definesSettings = DefinesSettings(localFile, preSettings).getOrElse {
            return false
          }
          log.info(s"Extend configuration file ${filePath} with defines")
          if (!definesSettings.propertiesExpansion(buildConf)) {
            log.error("Extend configuration file with defines error")
            return false
          }
        } else {
          val filePath = if (subPath.isEmpty) name else subPath + "/" + name
          val buildConf = new File(buildDirectory, filePath)
          log.info(s"Copy local configuration file ${filePath}")
          if (!IoUtils.copyFile(localFile, buildConf)) {
            return false
          }
        }
      }
    }
    true
  }

  private def sortConfigFilesByIndex(files: Seq[File]): Seq[File] = {
    files.sortWith { (file1, file2) =>
      val (name1, index1) = file1.getName match {
        case indexPattern(name, index) => (name, index.toInt)
        case name => (name, 0)
      }
      val (name2, index2) = file2.getName match {
        case indexPattern(name, index) => (name, index.toInt)
        case name => (name, 0)
      }
      if (name1 != name2) {
        name1 < name2
      } else {
        index1 < index2
      }
    }
  }

  private def getOriginalName(name: String): String = {
    name match {
      case indexPattern(name, _) => name
      case name => name
    }
  }
}

object InstallResult extends Enumeration {
  type InstallResult = Value
  val Complete, Failure, NeedRestartToUpdate = Value
}

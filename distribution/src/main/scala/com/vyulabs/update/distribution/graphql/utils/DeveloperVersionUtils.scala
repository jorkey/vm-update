package com.vyulabs.update.distribution.graphql.utils

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.mongodb.client.model.Filters
import com.vyulabs.update.common.common.Common.{DistributionName, ServiceName, TaskId, UserName}
import com.vyulabs.update.common.config.DistributionConfig
import com.vyulabs.update.common.distribution.server.DistributionDirectory
import com.vyulabs.update.common.info._
import com.vyulabs.update.common.version.{DeveloperDistributionVersion, DeveloperVersion}
import com.vyulabs.update.distribution.graphql.NotFoundException
import com.vyulabs.update.distribution.mongo.DatabaseCollections
import com.vyulabs.update.distribution.task.TaskManager
import org.bson.BsonDocument
import org.slf4j.Logger

import scala.collection.JavaConverters.asJavaIterableConverter
import scala.concurrent.{ExecutionContext, Future}

trait DeveloperVersionUtils extends DistributionConsumersUtils with StateUtils with RunBuilderUtils with SprayJsonSupport {
  protected val directory: DistributionDirectory
  protected val collections: DatabaseCollections
  protected val config: DistributionConfig
  protected val taskManager: TaskManager

  protected implicit val executionContext: ExecutionContext

  def buildDeveloperVersion(serviceName: ServiceName, developerVersion: DeveloperVersion, author: UserName,
                            sourceBranches: Seq[String], comment: Option[String])(implicit log: Logger): TaskId = {
    val task = taskManager.create(s"Build developer version ${developerVersion} of service ${serviceName}",
      (taskId, logger) => {
        implicit val log = logger
        val arguments = Seq("buildDeveloperVersion",
          s"distributionName=${config.distributionName}", s"service=${serviceName}", s"version=${developerVersion.toString}", s"author=${author}",
          s"sourceBranches=${sourceBranches.foldLeft("")((branches, branch) => { branches + (if (branches.isEmpty) branch else s",${branch}}") })}") ++
          comment.map(comment => s"comment=${comment}")
        runBuilder(taskId, arguments)
      })
    task.taskId
  }

  def addDeveloperVersionInfo(versionInfo: DeveloperVersionInfo)(implicit log: Logger): Future[Unit] = {
    log.info(s"Add developer version info ${versionInfo}")
    for {
      _ <- collections.Developer_VersionsInfo.insert(versionInfo)
      _ <- removeObsoleteVersions(versionInfo.distributionName, versionInfo.serviceName)
    } yield {}
  }

  private def removeObsoleteVersions(distributionName: DistributionName, serviceName: ServiceName)(implicit log: Logger): Future[Unit] = {
    for {
      versions <- getDeveloperVersionsInfo(serviceName, distributionName = Some(distributionName))
      busyVersions <- getBusyVersions(distributionName, serviceName)
      complete <- {
        val notUsedVersions = versions.filterNot(info => busyVersions.contains(info.version))
          .sortBy(_.buildInfo.date.getTime).map(_.version)
        if (notUsedVersions.size > config.versions.maxHistorySize) {
          Future.sequence(notUsedVersions.take(notUsedVersions.size - config.versions.maxHistorySize).map { version =>
            removeDeveloperVersion(serviceName, DeveloperDistributionVersion(distributionName, version))
          })
        } else {
          Future()
        }
      }
    } yield {}
  }

  def removeDeveloperVersion(serviceName: ServiceName, version: DeveloperDistributionVersion)(implicit log: Logger): Future[Boolean] = {
    log.info(s"Remove developer version ${version} of service ${serviceName}")
    val filters = Filters.and(
      Filters.eq("serviceName", serviceName),
      Filters.eq("distributionName", version.distributionName))
      Filters.eq("version", version.version)
    directory.getDeveloperVersionImageFile(serviceName, version).delete()
    for {
      profile <- {
        collections.Developer_VersionsInfo.delete(filters).map(_ > 0)
      }
    } yield profile
  }

  def getDeveloperVersionsInfo(serviceName: ServiceName, distributionName: Option[DistributionName] = None,
                               version: Option[DeveloperVersion] = None)(implicit log: Logger): Future[Seq[DeveloperVersionInfo]] = {
    val serviceArg = Filters.eq("serviceName", serviceName)
    val distributionArg = distributionName.map { distributionName => Filters.eq("distributionName", distributionName ) }
    val versionArg = version.map { version => Filters.eq("version", version) }
    val filters = Filters.and((Seq(serviceArg) ++ distributionArg ++ versionArg).asJava)
    collections.Developer_VersionsInfo.find(filters)
  }

  def setDeveloperDesiredVersions(deltas: Seq[DeveloperDesiredVersionDelta])(implicit log: Logger): Future[Unit] = {
    log.info(s"Upload developer desired versions ${deltas}")
    collections.Developer_DesiredVersions.update(new BsonDocument(), { desiredVersions =>
      val desiredVersionsMap = DeveloperDesiredVersions.toMap(desiredVersions.map(_.versions).getOrElse(Seq.empty))
      val newVersions =
        deltas.foldLeft(desiredVersionsMap) {
          (map, entry) => entry.version match {
            case Some(version) =>
              map + (entry.serviceName -> version)
            case None =>
              map - entry.serviceName
          }}
      Some(DeveloperDesiredVersions(DeveloperDesiredVersions.fromMap(newVersions)))
    }).map(_ => ())
  }

  def getDeveloperDesiredVersions(serviceNames: Set[ServiceName])(implicit log: Logger): Future[Seq[DeveloperDesiredVersion]] = {
    for {
      profile <- collections.Developer_DesiredVersions.find(new BsonDocument()).map(_.map(_.versions).headOption.getOrElse(Seq.empty[DeveloperDesiredVersion])
        .filter(v => serviceNames.isEmpty || serviceNames.contains(v.serviceName)))
    } yield profile
  }

  def getDeveloperDesiredVersion(serviceName: ServiceName)(implicit log: Logger): Future[Option[DeveloperDistributionVersion]] = {
    getDeveloperDesiredVersions(Set(serviceName)).map(_.headOption.map(_.version))
  }

  def filterDesiredVersionsByProfile(distributionName: DistributionName, future: Future[Seq[DeveloperDesiredVersion]])(implicit log: Logger)
      : Future[Seq[DeveloperDesiredVersion]] = {
    for {
      desiredVersions <- future
      consumerProfile <- getDistributionConsumerProfile(distributionName)
      versions <- Future(desiredVersions.filter(version => consumerProfile.services.contains(version.serviceName)))
    } yield versions
  }

  def getDeveloperDesiredVersions(distributionName: DistributionName, serviceNames: Set[ServiceName])(implicit log: Logger)
      : Future[Seq[DeveloperDesiredVersion]] = {
    for {
      distributionConsumerInfo <- getDistributionConsumerInfo(distributionName)
      developerVersions <- distributionConsumerInfo.testDistributionMatch match {
        case Some(testDistributionMatch) =>
          for {
            testedVersions <- getTestedVersions(distributionConsumerInfo.consumerProfile).map(testedVersions => {
              testedVersions match {
                case Some(testedVersions) =>
                  val regexp = testDistributionMatch.r
                  val testCondition = testedVersions.signatures.exists(signature =>
                    signature.distributionName match {
                      case regexp() =>
                        true
                      case _ =>
                        false
                    })
                  if (testCondition) {
                    testedVersions.versions
                  } else {
                    throw NotFoundException(s"Desired versions for profile ${distributionConsumerInfo.consumerProfile} are not tested by clients ${testDistributionMatch}")
                  }
                case None =>
                  throw NotFoundException(s"Desired versions for profile ${distributionConsumerInfo.consumerProfile} are not tested by anyone")
              }
            })
          } yield testedVersions
        case None =>
          getDeveloperDesiredVersions(serviceNames)
      }
    } yield developerVersions
  }

  private def getBusyVersions(distributionName: DistributionName, serviceName: ServiceName)(implicit log: Logger): Future[Set[DeveloperVersion]] = {
    for {
      desiredVersion <- getDeveloperDesiredVersion(serviceName)
      clientsInfo <- getDistributionConsumersInfo()
      installedVersions <- Future.sequence(clientsInfo.map(client => getInstalledDesiredVersion(client.distributionName, serviceName))).map(
        _.flatten.map(_.version.original()))
      testedVersions <- Future.sequence(clientsInfo.map(client => getTestedVersions(client.consumerProfile))).map(
        _.flatten.map(_.versions.find(_.serviceName == serviceName).map(_.version)).flatten)
    } yield {
      (desiredVersion.toSet ++ installedVersions ++ testedVersions).filter(_.distributionName == distributionName).map(_.version)
    }
  }
}
package com.vyulabs.update.distribution.graphql.utils

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.vyulabs.update.common.common.Common
import com.vyulabs.update.common.common.Common.TaskId
import com.vyulabs.update.common.distribution.client.DistributionClient
import com.vyulabs.update.common.distribution.client.graphql.{AdministratorSubscriptionsCoder, GraphqlArgument, GraphqlMutation}
import com.vyulabs.update.common.distribution.server.DistributionDirectory
import com.vyulabs.update.common.process.ChildProcess
import com.vyulabs.update.distribution.client.AkkaHttpClient
import com.vyulabs.update.distribution.common.AkkaTimer
import com.vyulabs.update.distribution.config.DistributionConfig
import com.vyulabs.update.distribution.mongo.DatabaseCollections
import com.vyulabs.update.distribution.task.TaskManager
import org.slf4j.Logger
import spray.json.DefaultJsonProtocol._
import spray.json._

import java.io.File
import java.net.URL
import scala.concurrent.{ExecutionContext, Future, Promise}

trait RunBuilderUtils extends StateUtils with SprayJsonSupport {
  protected val dir: DistributionDirectory
  protected val collections: DatabaseCollections
  protected val config: DistributionConfig
  protected val taskManager: TaskManager

  protected implicit val executionContext: ExecutionContext
  protected implicit val system: ActorSystem

  implicit val timer = new AkkaTimer(system.scheduler)

  def runBuilder(taskId: TaskId, arguments: Seq[String])(implicit log: Logger): (Future[Boolean], Option[() => Unit]) = {
    config.builderConfig.builderDirectory match {
      case Some(builderDirectory) =>
        runLocalBuilder(taskId, arguments, builderDirectory)
      case None =>
        config.builderConfig.distributionUrl match {
          case Some(distributionUrl) =>
            runRemoteBuilder(taskId, arguments, distributionUrl)
          case None =>
            sys.error("Local directory or distribution URL are not defined for builder")
        }
    }
  }

  def runLocalBuilderByRemoteDistribution(arguments: Seq[String])(implicit log: Logger): TaskId = {
    val task = taskManager.create("Run local builder by remote distribution",
      (taskId, logger) => {
        implicit val log = logger
        runLocalBuilder(taskId, arguments, config.builderConfig.builderDirectory.get)
      })
    task.taskId
  }

  private def runLocalBuilder(taskId: TaskId, arguments: Seq[String], builderDirectory: String)
                             (implicit log: Logger): (Future[Boolean], Option[() => Unit]) = {
    val process = for {
      process <- ChildProcess.start("/bin/sh", Common.BuilderSh +: arguments, Map.empty, new File(builderDirectory))
    } yield {
      process.handleOutput(lines => { lines.foreach(line => log.info(line._1)) })
      process.onTermination().map { exitCode => log.info(s"Builder process terminated with status ${exitCode}") }
//      @volatile var logOutputFuture = Option.empty[Future[Unit]]
//      process.handleOutput(lines => {
//        val logLines = lines.map(line => {
//          val array = line._1.split(" ", 6)
//          val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
//          val date = dateFormat.parse(s"${array(0)} ${array(1)}")
//          LogLine(date, array(2), Some(array(3)), array(5), None)
//        })
//        logOutputFuture = Some(logOutputFuture.getOrElse(Future()).flatMap { _ =>
//          addServiceLogs(config.distributionName, Common.DistributionServiceName,
//            Some(taskId), config.instanceId, process.getHandle().pid().toString, builderDirectory, logLines).map(_ => ())
//        })
//      })
//      logOutputFuture.getOrElse(Future()).flatMap { _ =>
//        process.onTermination().map { exitCode =>
//          addServiceLogs(config.distributionName, Common.DistributionServiceName,
//            Some(taskId), config.instanceId, process.getHandle().pid().toString, builderDirectory, Seq(LogLine(new Date, "", Some("PROCESS"),
//              s"Builder process terminated with status ${exitCode}", None)))
//        })
      process
    }
    (process.map(_.onTermination().map(_ == 0)).flatten, Some(() => process.map(_.terminate())))
  }

  private def runRemoteBuilder(taskId: TaskId, arguments: Seq[String], distributionUrl: URL)(implicit log: Logger): (Future[Boolean], Option[() => Unit]) = {
    val result = Promise[Boolean]()
    val client = new DistributionClient(config.distributionName, new AkkaHttpClient(distributionUrl))
    val remoteTaskId = client.graphqlRequest(GraphqlMutation[TaskId]("runBuilder", Seq(GraphqlArgument("arguments" -> arguments.toJson))))
    for {
      remoteTaskId <- remoteTaskId
      logSource <- client.graphqlSubRequest(AdministratorSubscriptionsCoder.subscribeTaskLogs(remoteTaskId))
    } yield {
      @volatile var logOutputFuture = Option.empty[Future[Unit]]
      logSource.map(line => {
        for (terminationStatus <- line.logLine.line.terminationStatus) {
          result.success(terminationStatus)
        }
        logOutputFuture = Some(logOutputFuture.getOrElse(Future()).flatMap { _ =>
          addServiceLogs(config.distributionName, Common.DistributionServiceName,
            Some(taskId), config.instanceId, 0.toString, "", Seq(line.logLine.line)).map(_ => ())
        })
      }).run()
    }
    (result.future, Some(() => {
      remoteTaskId.map(remoteTaskId => client.graphqlRequest(GraphqlMutation[Boolean]("cancelTask", Seq(GraphqlArgument("task" -> remoteTaskId)))).map(_ => ()))
    }))
  }
}

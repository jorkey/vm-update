package distribution.developer.uploaders

import akka.stream.Materializer
import com.vyulabs.update.common.Common.ClientName
import com.vyulabs.update.distribution.developer.DeveloperDistributionDirectory
import com.vyulabs.update.lock.SmartFilesLocker
import com.vyulabs.update.state.InstancesState
import com.vyulabs.update.utils.IOUtils
import com.vyulabs.update.state.InstancesStateJson._

import org.slf4j.LoggerFactory
import spray.json._

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 18.12.19.
  * Copyright FanDate, Inc.
  */
class DeveloperStateUploader(dir: DeveloperDistributionDirectory)
                            (implicit filesLocker: SmartFilesLocker, materializer: Materializer) extends Thread { self =>
  implicit val log = LoggerFactory.getLogger(this.getClass)

  private var client2instancesState = Map.empty[ClientName, InstancesState]
  private var stopping = false

  private val expireDiedInstanceStateTime = 24 * 60 * 60 * 1000L

  def close(): Unit = {
    self.synchronized {
      stopping = true
      notify()
    }
    join()
  }

  def receiveInstancesState(clientName: ClientName, instancesState: InstancesState): Unit = {
    log.info(s"Receive instances state of client ${clientName}")
    self.synchronized {
      client2instancesState += (clientName -> instancesState)
      notify()
    }
  }

  override def run(): Unit = {
    log.info("State uploader started")
    try {
      while (true) {
        val states = self.synchronized {
          if (stopping) {
            return
          }
          wait()
          if (stopping) {
            return
          }
          client2instancesState.foldLeft(Map.empty[ClientName, InstancesState])((m, e) => m + (e._1 -> e._2))
        }
        states.foreach { case (clientName, instancesState) => {
            log.info(s"Process instances state of client ${clientName}")
            try {
              val statesFile = dir.getInstancesStateFile(clientName)
              val oldStates = IOUtils.readFileToJsonWithLock(statesFile).map(_.convertTo[InstancesState])
              for (oldStates <- oldStates) {
                val newDeadStates = oldStates.states.filterKeys(!instancesState.states.contains(_))
                val deadStatesFile = dir.getDeadInstancesStateFile(clientName)
                val deadStates = IOUtils.readFileToJsonWithLock(deadStatesFile).map(_.convertTo[InstancesState]) match {
                  case Some(deadInstancesState) =>
                    deadInstancesState.states
                      .filterKeys(!instancesState.states.contains(_))
                      .filter(state => (System.currentTimeMillis() - state._2.startDate.getTime) < expireDiedInstanceStateTime)
                  case None =>
                    Map.empty
                }
                if (!IOUtils.writeJsonToFileWithLock(deadStatesFile, InstancesState(deadStates ++ newDeadStates).toJson)) {
                  log.error(s"Can't write ${deadStatesFile}")
                }
              }
              if (!IOUtils.writeJsonToFileWithLock(dir.getInstancesStateFile(clientName), instancesState.toJson)) {
                log.error("Error of writing instances state")
              }
            } catch {
              case ex: Exception =>
                log.error(s"Process instances state if client ${clientName} is failed", ex)
            }
          }
        }
      }
    } catch {
      case ex: Exception =>
        log.error(s"Fault uploader thread is failed", ex)
    }
  }
}

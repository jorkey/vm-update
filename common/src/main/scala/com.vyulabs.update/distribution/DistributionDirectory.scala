package com.vyulabs.update.distribution

import java.io._

import com.vyulabs.update.common.Common.{ClientName, ServiceName}
import com.vyulabs.update.lock.SmartFilesLocker
import com.vyulabs.update.utils.IoUtils
import com.vyulabs.update.version.BuildVersion
import org.slf4j.LoggerFactory

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 23.04.19.
  * Copyright FanDate, Inc.
  */
class DistributionDirectory(val directory: File) {
  private implicit val log = LoggerFactory.getLogger(this.getClass)

  protected val developerDir = new File(directory, "developer")
  protected val developerServicesDir = new File(developerDir, "services")
  protected val clientDir = new File(directory, "client")
  protected val clientServicesDir = new File(clientDir, "services")
  protected val faultsDir = new File(directory, "faults")

  if (!directory.exists()) directory.mkdirs()
  if (!developerDir.exists()) developerDir.mkdir()
  if (!developerServicesDir.exists()) developerServicesDir.mkdir()
  if (!clientDir.exists()) clientDir.mkdir()
  if (!clientServicesDir.exists()) clientServicesDir.mkdir()
  if (!faultsDir.exists()) faultsDir.mkdir()

  def getVersionImageFileName(serviceName: ServiceName, version: BuildVersion): String = {
    serviceName + "-" + version + ".zip"
  }

  def getFaultReportFileName(faultId: String): String = {
    faultId + "-fault.zip"
  }

  def drop(): Unit = {
    IoUtils.deleteFileRecursively(directory)
  }

  def getDeveloperServiceDir(serviceName: ServiceName): File = {
    val dir = new File(developerServicesDir, serviceName)
    if (!dir.exists()) dir.mkdir()
    dir
  }

  def getClientServiceDir(serviceName: ServiceName): File = {
    val dir = new File(clientServicesDir, serviceName)
    if (!dir.exists()) dir.mkdir()
    dir
  }

  def getDeveloperVersionImageFile(serviceName: ServiceName, version: BuildVersion): File = {
    new File(getDeveloperServiceDir(serviceName), getVersionImageFileName(serviceName, version))
  }

  def getClientVersionImageFile(serviceName: ServiceName, version: BuildVersion): File = {
    new File(getClientServiceDir(serviceName), getVersionImageFileName(serviceName, version))
  }

  def getFaultReportFile(faultId: String): File = {
    new File(faultsDir, getFaultReportFileName(faultId))
  }
}

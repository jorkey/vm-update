package com.vyulabs.update.config

import com.vyulabs.update.common.Common.{ClientName, InstallProfileName}
import spray.json.DefaultJsonProtocol

import scala.util.matching.Regex

case class ClientConfig(installProfile: InstallProfileName, testClientMatch: Option[Regex])

object ClientConfigJson extends DefaultJsonProtocol {
  import com.vyulabs.update.utils.Utils.RegexJson._

  implicit val clientConfigJson = jsonFormat2(ClientConfig.apply)
}

case class ClientInfo(name: ClientName, installProfile: InstallProfileName, testClientMatch: Option[Regex])

object ClientInfoJson extends DefaultJsonProtocol {
  import com.vyulabs.update.utils.Utils.RegexJson._

  implicit val clientInfoJson = jsonFormat3(ClientInfo.apply)
}

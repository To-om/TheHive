package controllers

import javax.inject.{ Inject, Singleton }

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.util.Try
import play.api.Configuration
import play.api.libs.json.{ JsBoolean, JsObject, JsString, Json }
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.mvc.{ AbstractController, Action, AnyContent, ControllerComponents }
import com.sksamuel.elastic4s.ElasticDsl
import connectors.Connector
import models.HealthStatus
import org.elastic4play.Timed
import org.elastic4play.database.DBIndex
import org.elastic4play.services.AuthSrv
import org.elastic4play.services.auth.MultiAuthSrv

@Singleton
class StatusCtrl @Inject() (
    connectors: immutable.Set[Connector],
    configuration: Configuration,
    dbIndex: DBIndex,
    authSrv: AuthSrv,
    components: ControllerComponents,
    implicit val ec: ExecutionContext) extends AbstractController(components) {

  private[controllers] def getVersion(c: Class[_]) = Option(c.getPackage.getImplementationVersion).getOrElse("SNAPSHOT")

  @Timed("controllers.StatusCtrl.get")
  def get: Action[AnyContent] = Action {
    val clusterStatusName = Try(dbIndex.clusterStatusName).getOrElse("ERROR")
    Ok(Json.obj(
      "versions" → Json.obj(
        "TheHive" → getVersion(classOf[models.Case]),
        "Elastic4Play" → getVersion(classOf[Timed]),
        "Play" → getVersion(classOf[AbstractController]),
        "Elastic4s" → getVersion(classOf[ElasticDsl]),
        "ElasticSearch" → getVersion(classOf[org.elasticsearch.Build])),
      "connectors" → JsObject(connectors.map(c ⇒ c.name → c.status).toSeq),
      "health" → Json.obj("elasticsearch" → clusterStatusName),
      "config" → Json.obj(
        "protectDownloadsWith" → configuration.get[String]("datastore.attachment.password"),
        "authType" → (authSrv match {
          case multiAuthSrv: MultiAuthSrv ⇒ multiAuthSrv.authProviders.map { a ⇒ JsString(a.name) }
          case _                          ⇒ JsString(authSrv.name)
        }),
        "capabilities" → authSrv.capabilities.map(c ⇒ JsString(c.toString)),
        "ssoAutoLogin" → JsBoolean(configuration.getOptional[Boolean]("auth.sso.autologin").getOrElse(false)))))
  }

  @Timed("controllers.StatusCtrl.health")
  def health: Action[AnyContent] = Action.async {
    for {
      dbStatusInt ← dbIndex.getClusterStatus
      dbStatus = dbStatusInt match {
        case 0 ⇒ HealthStatus.Ok
        case 1 ⇒ HealthStatus.Warning
        case _ ⇒ HealthStatus.Error
      }
      connectorStatus = connectors.map(c ⇒ c.health).toSeq
      distinctStatus = connectorStatus :+ dbStatus
      globalStatus = if (distinctStatus.contains(HealthStatus.Ok)) {
        if (distinctStatus.size > 1) HealthStatus.Warning else HealthStatus.Ok
      }
      else if (distinctStatus.contains(HealthStatus.Error)) HealthStatus.Error
      else HealthStatus.Warning
    } yield Ok(globalStatus.toString)
  }
}
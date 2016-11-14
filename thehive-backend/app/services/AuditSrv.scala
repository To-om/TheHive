package services

import javax.inject.{ Inject, Singleton }

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

import akka.actor.ActorDSL.{ Act, actor }
import akka.actor.ActorSystem

import play.api.Logger
import play.api.libs.json.{ JsBoolean, JsObject, Json }

import org.elastic4play.controllers.Fields
import org.elastic4play.models.{ Attribute, BaseEntity, BaseModelDef }
import org.elastic4play.services.{ AuditOperation, CreateSrv, EventMessage, EventSrv, RequestProcessEnd }
import org.elastic4play.utils.Instance

import models.{ Audit, AuditModel }

trait AuditedModel { self: BaseModelDef ⇒
  def attributes: Seq[Attribute[_]]

  lazy val auditedAttributes: Map[String, Attribute[_]] =
    attributes
      .collect { case a if !a.isUnaudited ⇒ a.name → a }
      .toMap
  def selectAuditedAttributes(attrs: JsObject) = JsObject {
    attrs.fields.flatMap {
      case nv @ (name, value) ⇒ auditedAttributes.get(name).map(_ ⇒ nv)
    }
  }
}

@Singleton
class AuditSrv @Inject() (
  auditModel: AuditModel,
    eventSrv: EventSrv,
    createSrv: CreateSrv,
    implicit val ec: ExecutionContext,
    implicit val system: ActorSystem
) {

  object EntityExtractor {
    def unapply(e: BaseEntity) = Some((e.model, e.id, e.routing))
  }

  val auditActor = actor(new Act {

    lazy val log = Logger(getClass)
    var currentRequestIds = Set.empty[String]

    become {
      case RequestProcessEnd(request, result) ⇒
        currentRequestIds = currentRequestIds - Instance.getRequestId(request)
      case AuditOperation(EntityExtractor(model: AuditedModel, id, routing), action, details, authContext, date) ⇒
        val requestId = authContext.requestId
        createSrv[AuditModel, Audit](auditModel, Fields.empty
          .set("operation", action.toString)
          .set("details", model.selectAuditedAttributes(details))
          .set("objectType", model.name)
          .set("objectId", id)
          .set("base", JsBoolean(!currentRequestIds.contains(requestId)))
          .set("startDate", Json.toJson(date))
          .set("rootId", routing)
          .set("requestId", requestId))(authContext)
          .onFailure { case t ⇒ log.error("Audit error", t) }
        currentRequestIds = currentRequestIds + requestId
    }
  })
  eventSrv.subscribe(auditActor, classOf[EventMessage]) // need to unsubsribe ?
}
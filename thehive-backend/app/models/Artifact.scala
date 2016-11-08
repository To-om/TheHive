package models

import java.util.Date

import javax.inject.{ Inject, Provider, Singleton }

import scala.concurrent.{ ExecutionContext, Future }
import scala.language.postfixOps

import akka.stream.Materializer

import play.api.libs.json.{ JsNull, JsObject, JsString, JsValue }
import play.api.libs.json.JsLookupResult.jsLookupResultToJsLookup
import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper

import org.elastic4play.BadRequestError
import org.elastic4play.models.{ AttributeDef, AttributeFormat ⇒ F, AttributeOption ⇒ O, BaseEntity, ChildModelDef, EntityDef, HiveEnumeration }
import org.elastic4play.services.DBLists
import org.elastic4play.utils.MultiHash

import models.JsonFormat.artifactStatusFormat
import services.{ ArtifactSrv, AuditedModel }

object ArtifactStatus extends Enumeration with HiveEnumeration {
  type Type = Value
  val Ok, Deleted = Value
}

trait ArtifactAttributes { _: AttributeDef ⇒
  def dblists: DBLists
  val artifactId = attribute("_id", F.stringFmt, "Artifact id", O.model)
  val data = optionalAttribute("data", F.stringFmt, "Content of the artifact", O.readonly)
  val dataType = attribute("dataType", F.listEnumFmt("artifactDataType")(dblists), "Type of the artifact", O.readonly)
  val message = attribute("message", F.textFmt, "Description of the artifact in the context of the case")
  val startDate = attribute("startDate", F.dateFmt, "Creation date", new Date)
  val attachment = optionalAttribute("attachment", F.attachmentFmt, "Artifact file content", O.readonly)
  val tlp = attribute("tlp", F.numberFmt, "TLP level", 2L)
  val tags = multiAttribute("tags", F.stringFmt, "Artifact tags")
  val ioc = attribute("ioc", F.booleanFmt, "Artifact is an IOC", false)
  val status = attribute("status", F.enumFmt(ArtifactStatus), "Status of the artifact", ArtifactStatus.Ok)
}

@Singleton
class ArtifactModel @Inject() (
    caseModel: CaseModel,
    val dblists: DBLists,
    artifactSrv: Provider[ArtifactSrv],
    implicit val mat: Materializer,
    implicit val ec: ExecutionContext) extends ChildModelDef[ArtifactModel, Artifact, CaseModel, Case](caseModel, "case_artifact") with ArtifactAttributes with AuditedModel {
  override val removeAttribute = Json.obj("status" -> ArtifactStatus.Deleted)

  override def getStats(entity: BaseEntity): Future[JsObject] = {
    entity match {
      case artifact: Artifact ⇒
        val (_, total) = artifactSrv.get.findSimilar(artifact, Some("0-0"), Nil)
        total.map { t ⇒ Json.obj("seen" -> t) }
      case _ ⇒ Future.successful(JsObject(Nil))
    }
  }
}

class Artifact(model: ArtifactModel, attributes: JsObject) extends EntityDef[ArtifactModel, Artifact](model, attributes) with ArtifactAttributes {
  def dblists = model.dblists
}
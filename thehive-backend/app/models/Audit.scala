package models

import java.util.Date

import javax.inject.{ Inject, Singleton }

import scala.collection.immutable

import play.api.{ Configuration, Logger }
import play.api.libs.json.JsObject

import org.elastic4play.models.{ Attribute, AttributeDef, AttributeFormat ⇒ F, AttributeOption ⇒ O, EntityDef, EnumerationAttributeFormat, ModelDef, ObjectAttributeFormat, StringAttributeFormat }
import org.elastic4play.services.AuditableAction
import org.elastic4play.services.JsonFormat.auditableActionFormat

import services.AuditedModel

trait AuditAttributes { _: AttributeDef ⇒
  def detailsAttributes: Seq[Attribute[_]]

  val operation = attribute("operation", F.enumFmt(AuditableAction), "Operation", O.readonly)
  val details = attribute("details", F.objectFmt(detailsAttributes), "Details", JsObject(Nil), O.readonly)
  val otherDetails = optionalAttribute("otherDetails", F.textFmt, "Other details", O.readonly)
  val objectType = attribute("objectType", F.stringFmt, "Table affected by the operation", O.readonly)
  val objectId = attribute("objectId", F.stringFmt, "Object targeted by the operation", O.readonly)
  val base = attribute("base", F.booleanFmt, "Indicates if this operation is the first done for a http query", O.readonly)
  val startDate = attribute("startDate", F.dateFmt, "Date and time of the operation", new Date, O.readonly)
  val rootId = attribute("rootId", F.stringFmt, "Root element id (routing id)", O.readonly)
  val requestId = attribute("requestId", F.stringFmt, "Id of the request that do the operation", O.readonly)
}

@Singleton
class AuditModel(
  auditName: String,
    auditedModels: immutable.Set[AuditedModel]
) extends ModelDef[AuditModel, Audit](auditName) with AuditAttributes {

  @Inject() def this(
    configuration: Configuration,
    auditedModels: immutable.Set[AuditedModel]
  ) =
    this(
      configuration.getString("audit.name").get,
      auditedModels
    )

  lazy val log = Logger(getClass)

  def detailsAttributes = {
    auditedModels
      .flatMap(_.attributes)
      .flatMap {
        // if attribute is object, add sub attributes
        case attr @ Attribute(_, _, ObjectAttributeFormat(subAttributes), _, _, _) ⇒ attr +: subAttributes
        case attr ⇒ Seq(attr)
      }
      .filter(_.isModel)
      .groupBy(_.name)
      .flatMap {
        // if only one attribute is found for a name, get it
        case (name, attribute @ Seq(a)) ⇒ attribute
        // otherwise, check if attribute format is compatible
        case (name, attributes) ⇒
          attributes.headOption.foreach { first ⇒
            val isSensitive = first.isSensitive
            val formatName = first.format.name
            if (!attributes.forall(a ⇒ a.isSensitive == isSensitive && a.format.name == formatName)) {
              log.error("Mapping is not consistent :")
              attributes.foreach { attr ⇒
                val s = if (attr.isSensitive) " (is sensitive)" else ""
                log.error(s"\t${attr.name} : ${attr.format.name} $s")
              }
            }
          }
          attributes.headOption
      }
      .map {
        case attr @ Attribute(_, _, EnumerationAttributeFormat(_), _, _, _) ⇒ attr.copy(format = StringAttributeFormat, defaultValue = None)
        case attr                                                           ⇒ attr
      }
      .toSeq
  }
  override def apply(attributes: JsObject): Audit = new Audit(this, attributes)
}

class Audit(model: AuditModel, attributes: JsObject) extends EntityDef[AuditModel, Audit](model, attributes) with AuditAttributes {
  def detailsAttributes = Nil
}
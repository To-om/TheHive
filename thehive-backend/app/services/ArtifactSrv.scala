package services

import javax.inject.{ Inject, Singleton }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

import play.api.libs.json.JsValue.jsValueToJsLookup

import org.elastic4play.CreateError
import org.elastic4play.controllers.Fields
import org.elastic4play.services.{ Agg, AuthContext, CreateSrv, DeleteSrv, FieldsSrv, FindSrv, GetSrv, QueryDSL, QueryDef, UpdateSrv }

import models.{ Artifact, ArtifactModel, ArtifactStatus, Case, CaseModel, JobModel }
import org.elastic4play.utils.{ RichFuture, RichOr }
import org.elastic4play.BadRequestError
import play.api.libs.json.JsString
import org.elastic4play.utils.MultiHash
import akka.stream.Materializer
import play.api.libs.json.JsValue
import play.api.libs.json.JsNull

@Singleton
class ArtifactSrv @Inject() (
    artifactModel: ArtifactModel,
    caseModel: CaseModel,
    jobModel: JobModel,
    createSrv: CreateSrv,
    getSrv: GetSrv,
    updateSrv: UpdateSrv,
    deleteSrv: DeleteSrv,
    findSrv: FindSrv,
    fieldsSrv: FieldsSrv,
    implicit val ec: ExecutionContext,
    implicit val mat: Materializer) {

  def create(caseId: String, fields: Fields)(implicit authContext: AuthContext): Future[Artifact] =
    getSrv[CaseModel, Case](caseModel, caseId)
      .flatMap { caze ⇒ create(caze, fields) }

  def create(caze: Case, fields: Fields)(implicit authContext: AuthContext): Future[Artifact] = {
    if (fields.contains("data") == fields.contains("attachment"))
      throw new BadRequestError(s"Artifact must contain data or attachment (but not both)")
    computeId(caze, fields).flatMap { id ⇒
      val artifactFields = fields.set("_id", JsString(id))
      createSrv[ArtifactModel, Artifact, Case](artifactModel, caze, fields)
        .fallbackTo(updateIfDeleted(caze, fields)) // maybe the artifact already exists. If so, search it and update it
    }
  }

  private[services] def computeId(parent: Case, fields: Fields): Future[String] = {
    // in order to make sure that there is no duplicated artifact, calculate its id from its content (dataType, data, attachment and parent)
    val mm = new MultiHash("MD5")
    mm.addValue(fields.getValue("data").getOrElse(JsNull))
    mm.addValue(fields.getValue("dataType").getOrElse(JsNull))
    fields.getFile("attachment")
      .fold(Future.successful(())) { fiv ⇒
        mm.addFile(fiv.filepath)
      }
      .map { _ ⇒
        mm.addValue(JsString(parent.id))
        mm.digest.toString
      }
  }

  private[services] def updateIfDeleted(caze: Case, fields: Fields)(implicit authContext: AuthContext): Future[Artifact] = {
    fieldsSrv.parse(fields, artifactModel).toFuture.flatMap { attrs ⇒
      val updatedArtifact = for {
        id ← computeId(caze, fields)
        artifact ← getSrv[ArtifactModel, Artifact](artifactModel, id)
        if artifact.status() == ArtifactStatus.Deleted
        updatedArtifact ← updateSrv[ArtifactModel, Artifact](artifactModel, artifact.id, fields.unset("data").unset("dataType").unset("attachment").set("status", "Ok"))
      } yield updatedArtifact
      updatedArtifact.recoverWith {
        case _ ⇒ Future.failed(CreateError(Some("CONFLICT"), "Artifact already exists", attrs))
      }
    }
  }

  def create(caseId: String, fieldSet: Seq[Fields])(implicit authContext: AuthContext): Future[Seq[Try[Artifact]]] =
    getSrv[CaseModel, Case](caseModel, caseId)
      .flatMap { caze ⇒ create(caze, fieldSet) }

  def create(caze: Case, fieldSet: Seq[Fields])(implicit authContext: AuthContext): Future[Seq[Try[Artifact]]] =
    Future.traverse(fieldSet) { fields ⇒
      create(caze, fields).toTry
    }

  def get(id: String, fields: Option[Seq[String]] = None)(implicit authContext: AuthContext) = {
    val fieldAttribute = fields.map { _.flatMap(f ⇒ artifactModel.attributes.find(_.name == f)) }
    getSrv[ArtifactModel, Artifact](artifactModel, id, fieldAttribute)
  }

  def update(id: String, fields: Fields)(implicit authContext: AuthContext) =
    updateSrv[ArtifactModel, Artifact](artifactModel, id, fields)

  def bulkUpdate(ids: Seq[String], fields: Fields)(implicit authContext: AuthContext) = {
    updateSrv.apply[ArtifactModel, Artifact](artifactModel, ids, fields)
  }

  def delete(id: String)(implicit Context: AuthContext) =
    deleteSrv[ArtifactModel, Artifact](artifactModel, id)

  def find(queryDef: QueryDef, range: Option[String], sortBy: Seq[String]) = {
    findSrv[ArtifactModel, Artifact](artifactModel, queryDef, range, sortBy)
  }

  def stats(queryDef: QueryDef, aggs: Seq[Agg]) = findSrv(artifactModel, queryDef, aggs: _*)

  def isSeen(artifact: Artifact): Future[Long] = {
    import org.elastic4play.services.QueryDSL._
    findSrv(artifactModel, similarArtifactFilter(artifact), selectCount).map { stats ⇒
      (stats \ "count").asOpt[Long].getOrElse(1L)
    }
  }

  def findSimilar(artifact: Artifact, range: Option[String], sortBy: Seq[String]) =
    find(similarArtifactFilter(artifact), range, sortBy)

  private def similarArtifactFilter(artifact: Artifact): QueryDef = {
    import org.elastic4play.services.QueryDSL._
    val dataType = artifact.dataType()
    artifact.data() match {
      // artifact is an hash
      case Some(d) if dataType == "hash" ⇒
        and(
          not(parent("case", "_id" ~= artifact.parentId.get)),
          "status" ~= "Ok",
          or(
            and(
              "data" ~= d,
              "dataType" ~= dataType),
            "attachment.hashes" ~= d))
      // artifact contains data but not an hash
      case Some(d) ⇒
        and(
          not(parent("case", "_id" ~= artifact.parentId.get)),
          "status" ~= "Ok",
          "data" ~= d,
          "dataType" ~= dataType)
      // artifact is a file
      case None ⇒
        val hashes = artifact.attachment().toSeq.flatMap(_.hashes).map(_.toString)
        val hashFilter = hashes.map { h ⇒ "attachment.hashes" ~= h }
        and(
          not(parent("case", "_id" ~= artifact.parentId.get)),
          "status" ~= "Ok",
          or(
            hashFilter :+
              and(
                "dataType" ~= "hash",
                or(hashes.map { h ⇒ "data" ~= h }))))
    }
  }
}

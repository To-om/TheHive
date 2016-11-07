package services

import javax.inject.{ Inject, Singleton }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

import akka.NotUsed
import akka.stream.scaladsl.Source

import play.api.Logger
import play.api.libs.json.{ JsObject, Json }
import play.api.libs.json.Json.toJsFieldJsValueWrapper

import org.elastic4play.InternalError
import org.elastic4play.controllers.Fields
import org.elastic4play.services.{ Agg, AuthContext, CreateSrv, DeleteSrv, FindSrv, GetSrv, QueryDSL, QueryDef, UpdateSrv }

import models.{ Artifact, ArtifactModel, Case, CaseModel, Task, TaskModel }
import org.elastic4play.services.SequenceSrv
import play.api.libs.json.JsNumber
import java.util.Date

@Singleton
class CaseSrv @Inject() (
    caseModel: CaseModel,
    artifactModel: ArtifactModel,
    taskModel: TaskModel,
    createSrv: CreateSrv,
    artifactSrv: ArtifactSrv,
    taskSrv: TaskSrv,
    getSrv: GetSrv,
    updateSrv: UpdateSrv,
    deleteSrv: DeleteSrv,
    findSrv: FindSrv,
    sequenceSrv: SequenceSrv,
    implicit val ec: ExecutionContext) {

  lazy val log = Logger(getClass)

  def create(fields: Fields)(implicit authContext: AuthContext): Future[Case] = {
    for {
      caseId ← sequenceSrv("case")
      caseFields = fields.unset("tasks").set("caseId", JsNumber(caseId))
      caze ← createSrv[CaseModel, Case](caseModel, caseFields)
      taskFields = fields.getValues("tasks").collect {
        case task: JsObject ⇒ caze -> Fields(task)
      }
      _ ← createSrv[TaskModel, Task, Case](taskModel, taskFields)
    } yield caze
  }

  def get(id: String)(implicit authContext: AuthContext): Future[Case] =
    getSrv[CaseModel, Case](caseModel, id)

  def update(caze: Case, fields: Fields)(implicit authContext: AuthContext): Future[Case] = {
    val caseFields = fields.getString("status") match {
      case Some("Resolved") if !fields.contains("endDate") ⇒ fields.set("endDate", Json.toJson(new Date))
      case Some("Open")                                    ⇒ fields.unset("endDate")
      case _                                               ⇒ fields
    }
    updateSrv(caze, caseFields)
  }

  def bulkUpdate(ids: Seq[String], fields: Fields)(implicit authContext: AuthContext): Future[Seq[Try[Case]]] = {
    updateSrv[CaseModel, Case](caseModel, ids, fields)
  }

  def delete(id: String)(implicit Context: AuthContext): Future[Case] =
    deleteSrv[CaseModel, Case](caseModel, id)

  def find(queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[Case, NotUsed], Future[Long]) = {
    findSrv[CaseModel, Case](caseModel, queryDef, range, sortBy)
  }

  def stats(queryDef: QueryDef, aggs: Seq[Agg]): Future[JsObject] = findSrv(caseModel, queryDef, aggs: _*)

  def getStats(id: String): Future[JsObject] = {
    import org.elastic4play.services.QueryDSL._
    for {
      taskStats ← findSrv(taskModel, and(
        "_parent" ~= id,
        "status" in ("Waiting", "InProgress", "Completed")), groupByField("status", selectCount))
      artifactStats ← findSrv(artifactModel, and("_parent" ~= id, "status" ~= "Ok"), groupByField("status", selectCount))
    } yield Json.obj(("tasks", taskStats), ("artifacts", artifactStats))
  }

  def linkedCases(id: String): Source[(Case, Seq[Artifact]), NotUsed] = {
    import org.elastic4play.services.QueryDSL._
    findSrv[ArtifactModel, Artifact](artifactModel, parent("case", withId(id)), Some("all"), Nil)
      ._1
      .flatMapConcat { artifact ⇒ artifactSrv.findSimilar(artifact, Some("all"), Nil)._1 }
      .groupBy(20, _.parentId)
      .map { a ⇒ (a.parentId, Seq(a)) }
      .reduce((l, r) ⇒ (l._1, r._2 ++ l._2))
      .mergeSubstreams
      .mapAsyncUnordered(5) {
        case (Some(caseId), artifacts) ⇒ getSrv[CaseModel, Case](caseModel, caseId) map (_ -> artifacts)
        case _                         ⇒ Future.failed(InternalError("Case not found"))
      }
      .mapMaterializedValue(_ ⇒ NotUsed)
  }
}
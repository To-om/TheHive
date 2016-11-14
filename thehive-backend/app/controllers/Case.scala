package controllers

import javax.inject.{ Inject, Singleton }

import scala.concurrent.{ ExecutionContext, Future }

import org.elastic4play.{ BadRequestError, Timed }
import org.elastic4play.controllers.{ ApiDocs, ApiModelParam, Authenticated, FieldsBodyParser, Renderer }
import org.elastic4play.models.JsonFormat.{ baseModelEntityWrites, multiFormat }
import org.elastic4play.services.{ Agg, AuxSrv }
import org.elastic4play.services.{ QueryDSL, QueryDef, Role }
import org.elastic4play.services.JsonFormat.{ aggReads, queryReads }

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import models.{ CaseModel, CaseStatus }
import play.api.Logger
import play.api.http.Status
import play.api.libs.json.{ JsArray, JsObject, Json }
import play.api.mvc.Controller
import services.{ CaseSrv, TaskSrv }
import shapeless.{ HNil, :: }

@Singleton
class CaseCtrl @Inject() (
    caseModel: CaseModel,
    caseSrv: CaseSrv,
    taskSrv: TaskSrv,
    auxSrv: AuxSrv,
    authenticated: Authenticated,
    val apiDocs: ApiDocs,
    renderer: Renderer,
    fieldsBodyParser: FieldsBodyParser,
    implicit val ec: ExecutionContext,
    implicit val mat: Materializer
) extends Controller with Status {

  val log = Logger(getClass)

  @Timed
  def create() = apiDocs("create a case", "this method create a new case")
    .authenticated(Role.write)
    .withPathParameter(ApiModelParam(caseModel))
    .async { implicit request ⇒
      val caseFields :: HNil = request.body
      caseSrv.create(caseFields)
        .map(caze ⇒ renderer.toOutput(CREATED, caze))
    }

  @Timed
  def get(id: String) = authenticated(Role.read).async { implicit request ⇒
    caseSrv.get(id)
      .map(caze ⇒ renderer.toOutput(OK, caze))
  }

  @Timed
  def update(id: String) = authenticated(Role.write).async(fieldsBodyParser) { implicit request ⇒
    val isCaseClosing = request.body.getString("status").filter(_ == CaseStatus.Resolved.toString).isDefined

    for {
      // Closing the case, so lets close the open tasks
      caze ← caseSrv.update(id, request.body)
      closedTasks ← if (isCaseClosing) taskSrv.closeTasksOfCase(id) else Future.successful(Nil) // FIXME log warning if closedTasks contains errors
    } yield renderer.toOutput(OK, caze)
  }

  @Timed
  def bulkUpdate() = authenticated(Role.write).async(fieldsBodyParser) { implicit request ⇒
    val isCaseClosing = request.body.getString("status").filter(_ == CaseStatus.Resolved.toString).isDefined

    request.body.getStrings("ids").fold(Future.successful(Ok(JsArray()))) { ids ⇒
      if (isCaseClosing) taskSrv.closeTasksOfCase(ids: _*) // FIXME log warning if closedTasks contains errors
      caseSrv.bulkUpdate(ids, request.body.unset("ids")).map(multiResult ⇒ renderer.toMultiOutput(OK, multiResult))
    }
  }

  @Timed
  def delete(id: String) = authenticated(Role.write).async { implicit request ⇒
    caseSrv.delete(id)
      .map(_ ⇒ NoContent)
  }

  @Timed
  def find() = authenticated(Role.read).async(fieldsBodyParser) { implicit request ⇒
    val query = request.body.getValue("query").fold[QueryDef](QueryDSL.any)(_.as[QueryDef])
    val range = request.body.getString("range")
    val sort = request.body.getStrings("sort").getOrElse(Nil)
    val nparent = request.body.getLong("nparent").getOrElse(0L).toInt
    val withStats = request.body.getBoolean("nstats").getOrElse(false)

    val (cases, total) = caseSrv.find(query, range, sort)
    val casesWithStats = auxSrv.apply(cases, nparent, withStats)
    renderer.toOutput(OK, casesWithStats, total)
  }

  @Timed
  def stats() = authenticated(Role.read).async(fieldsBodyParser) { implicit request ⇒
    val query = request.body.getValue("query").fold[QueryDef](QueryDSL.any)(_.as[QueryDef])
    val aggs = request.body.getValue("stats").getOrElse(throw BadRequestError("Parameter \"stats\" is missing")).as[Seq[Agg]]
    caseSrv.stats(query, aggs).map(s ⇒ Ok(s))
  }

  @Timed
  def linkedCases(id: String) = authenticated(Role.read).async { implicit request ⇒
    caseSrv.linkedCases(id)
      .runWith(Sink.seq)
      .map { cases ⇒
        val casesList = cases.sortWith {
          case ((c1, _), (c2, _)) ⇒ c1.startDate().after(c2.startDate())
        }.map {
          case (caze, artifacts) ⇒
            Json.toJson(caze).as[JsObject] - "description" +
              ("linkedWith" → Json.toJson(artifacts)) +
              ("linksCount" → Json.toJson(artifacts.size))
        }
        renderer.toOutput(OK, casesList)
      }
  }
}
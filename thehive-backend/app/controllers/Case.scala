package controllers

import javax.inject.{ Inject, Singleton }

import scala.concurrent.{ ExecutionContext, Future }

import org.elastic4play.Timed
import org.elastic4play.controllers.{ ApiDocs, ApiModelParam, ApiTypeParam, Authenticated, FieldsBodyParser, Renderer }
import org.elastic4play.models.{ AttributeFormat ⇒ F }
import org.elastic4play.models.JsonFormat.{ baseModelEntityWrites, multiFormat }
import org.elastic4play.services.{ Agg, AuxSrv }
import org.elastic4play.services.{ QueryDSL, QueryDef, Role }
import org.elastic4play.services.JsonFormat._

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import models.{ CaseModel, CaseStatus }
import play.api.Logger
import play.api.http.Status
import play.api.libs.json._
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
    .withParameter(ApiModelParam(caseModel))
    .withResult(CREATED, ApiModelParam(caseModel))
    .async { implicit request ⇒
      val caseFields :: HNil = request.body
      caseSrv.create(caseFields)
        .map(caze ⇒ renderer.toOutput(CREATED, caze))
    }

  @Timed
  def get(id: String) = apiDocs("retrieve a case", "")
    .authenticated(Role.read)
    .withResult(OK, ApiModelParam(caseModel))
    .async[Nothing] { implicit request ⇒
      caseSrv.get(id)
        .map(caze ⇒ renderer.toOutput(OK, caze))
    }

  @Timed
  def update(id: String) = apiDocs("update a case", "")
    .authenticated(Role.write)
    .withParameter(ApiModelParam(caseModel))
    .withResult(OK, ApiModelParam(caseModel))
    .async { implicit request ⇒
      val caseFields :: HNil = request.body
      val isCaseClosing = caseFields.getString("status").filter(_ == CaseStatus.Resolved.toString).isDefined

      for {
        // Closing the case, so lets close the open tasks
        caze ← caseSrv.update(id, caseFields)
        closedTasks ← if (isCaseClosing) taskSrv.closeTasksOfCase(id) else Future.successful(Nil) // FIXME log warning if closedTasks contains errors
      } yield renderer.toOutput(OK, caze)
    }

  @Timed
  def bulkUpdate() = apiDocs("", "")
    .authenticated(Role.write)
    .withParameter(ApiModelParam(caseModel))
    .withParameter(ApiTypeParam("ids", F.multi(F.stringFmt), None, "list of case id"))
    .async { implicit request ⇒
      val caseFields :: ids :: HNil = request.body
      val isCaseClosing = caseFields.getString("status").filter(_ == CaseStatus.Resolved.toString).isDefined

      if (isCaseClosing) taskSrv.closeTasksOfCase(ids: _*) // FIXME log warning if closedTasks contains errors
      caseSrv.bulkUpdate(ids, caseFields).map(multiResult ⇒ renderer.toMultiOutput(OK, multiResult))
    }

  @Timed
  def delete(id: String) = apiDocs("", "")
    .authenticated(Role.write)
    .async[Nothing] { implicit request ⇒
      caseSrv.delete(id)
        .map(_ ⇒ NoContent)
    }

  @Timed
  def find() = apiDocs("", "")
    .authenticated(Role.read)
    .withParameter(ApiTypeParam("query", F.jsonFmt[QueryDef](JsObject(Nil)), Some(QueryDSL.any), ""))
    .withParameter(ApiTypeParam("range", F.optional(F.stringFmt), None, ""))
    .withParameter(ApiTypeParam("sort", F.multi(F.stringFmt), None, ""))
    .withParameter(ApiTypeParam("nparent", F.numberFmt, Some(0L), ""))
    .withParameter(ApiTypeParam("nstats", F.booleanFmt, Some(false), ""))
    .async { implicit request ⇒
      val query :: range :: sort :: nparent :: nstats :: HNil = request.body

      val (cases, total) = caseSrv.find(query, range, sort)
      val casesWithStats = auxSrv.apply(cases, nparent.toInt, nstats)
      renderer.toOutput(OK, casesWithStats, total)
    }

  @Timed
  def stats() = apiDocs("", "")
    .authenticated(Role.read)
    .withParameter(ApiTypeParam("query", F.jsonFmt[QueryDef](JsObject(Nil)), Some(QueryDSL.any), ""))
    .withParameter(ApiTypeParam("aggs", F.jsonFmt[Seq[Agg]](JsObject(Nil)), None, ""))
    .async { implicit request ⇒
      val query :: aggs :: HNil = request.body
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

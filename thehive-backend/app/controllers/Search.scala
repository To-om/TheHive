package controllers

import javax.inject.{ Inject, Singleton }

import scala.concurrent.ExecutionContext

import play.api.http.Status
import play.api.mvc.Controller

import org.elastic4play.Timed
import org.elastic4play.controllers.{ Authenticated, FieldsBodyParser, Renderer }
import org.elastic4play.services.{ AuxSrv, FindSrv }
import org.elastic4play.services.{ QueryDSL, QueryDef, Role }
import org.elastic4play.services.JsonFormat.queryReads

@Singleton
class SearchCtrl @Inject() (
    findSrv: FindSrv,
    auxSrv: AuxSrv,
    authenticated: Authenticated,
    renderer: Renderer,
    fieldsBodyParser: FieldsBodyParser,
    implicit val ec: ExecutionContext
) extends Controller with Status {

  @Timed
  def find() = authenticated(Role.read).async(fieldsBodyParser) { implicit request ⇒
    import QueryDSL._
    val query = request.body.getValue("query").fold[QueryDef](QueryDSL.any)(_.as[QueryDef])
    val range = request.body.getString("range")
    val sort = request.body.getStrings("sort").getOrElse(Nil)
    val nparent = request.body.getLong("nparent").getOrElse(0L).toInt
    val withStats = request.body.getBoolean("nstats").getOrElse(false)

    val (entities, total) = findSrv(None, and(query, "status" ~!= "Deleted", not(or(ofType("audit"), ofType("data"), ofType("user"), ofType("analyzer"), ofType("misp")))), range, sort)
    val entitiesWithStats = auxSrv(entities, nparent, withStats)
    renderer.toOutput(OK, entitiesWithStats, total)
  }
}
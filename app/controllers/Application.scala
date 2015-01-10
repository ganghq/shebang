package controllers

import java.util.UUID.randomUUID

import actors.UserActor
import play.api._
import play.api.libs.json.JsValue
import play.api.mvc._
import play.api.Play.current

import scala.concurrent.Future

object Application extends Controller {

  def index = Action { implicit request =>
    val uid: String = request.session.get("uid").getOrElse {
      randomUUID().toString
    }
    Logger.debug("UID: " + uid)
    Ok(views.html.main()).withSession(request.session + ("uid" -> uid))


  }

  def ws = WebSocket.tryAcceptWithActor[JsValue, JsValue] { request =>
    Future.successful(request.session.get("uid") match {
      case Some(uid) => Right(UserActor.props(uid))
      case None => Left(Forbidden)
    })
  }

}
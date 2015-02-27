package controllers

import java.util.UUID.randomUUID

import actors.UserActor
import controllers.backApi.protocol.AppUser
import play.api._
import play.api.libs.json.{Json, JsValue}
import play.api.libs.ws.{WSResponse, WS}
import play.api.mvc._
import play.api.Play.current

import scala.concurrent.Future
import scala.util.Random

object Application extends Controller {

  def index = Action { implicit request =>
    val uid: String = request.session.get("uid").getOrElse {
      randomUUID().toString
    }
    //    Logger.debug("UID: " + uid)

    val result = views.html.main()

    val session = (request.session + ("uid" -> uid)) + ("username" -> Random.nextInt().toString)

    Ok(result).withSession(session)
  }


  def ws(token: String) = WebSocket.tryAcceptWithActor[JsValue, JsValue] { request =>

    import scala.concurrent.ExecutionContext.Implicits.global

    println("token = " + token)
    backApi.me(token).map { (appUser: AppUser) =>

      val uid: String = appUser.username
      Right(UserActor.props(uid) _)
    }.recover {
      case error =>
        println(error)
        Left(Forbidden)
    }

  }


  def renderChannel(id: String) = Action { implicit request =>
    Ok(views.html.renderedChannel(id))
  }

  def escapedFragment() = Action { implicit request =>
    request.getQueryString("_escaped_fragment_").map { ef =>

      Ok(views.html.renderedChannel(ef))
    }.getOrElse {

      Ok(views.html.renderedChannel("000000"))

    }
  }
}


object backApi {
  def me(token: String) = {
    //todo parametre olarak al bunu
    import scala.concurrent.ExecutionContext.Implicits.global


    val base = "http://app.ganghq.com/api/me"

    val meURL = s"$base?token=$token"
    val ws = WS.url(meURL)

    (ws get) map { (x: WSResponse) =>
      import protocol.jsonAppUser
      println(x.json)
      val result = x.json
      (result \ "appUser").validate[AppUser](jsonAppUser).get
    }


  }

  object protocol {

    //{"appUser":{"id":13,"username":"sumnulu","firstName":"","lastName":"","email":"ilgaz@fikrimuhal.com","teams":[]}}
    case class AppUser(id: Long, username: String, firstName: String = "", lastName: String = "", email: String /*, teams: List[Long]*/)

    implicit val jsonAppUser = Json.format[AppUser]

  }

}
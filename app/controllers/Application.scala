package controllers

import java.net.URI
import java.util.UUID.randomUUID

import actors.UserActor
import controllers.backApi.protocol
import controllers.backApi.protocol.{Team, AppUser}
import play.api._
import play.api.libs.json.{JsResult, Json, JsValue}
import play.api.libs.ws.{WSResponse, WS}
import play.api.mvc._
import play.api.Play.current

import scala.concurrent.Future
import scala.util.Random

object Application extends Controller {
  val debugMode = false

  def index = Action { implicit request =>
    val uid: String = request.session.get("uid").getOrElse {
      randomUUID().toString
    }
    //    Logger.debug("UID: " + uid)

    val result = views.html.main()

    val session = (request.session + ("uid" -> uid)) + ("username" -> Random.nextInt().toString)



    if (debugMode) Ok(result).withSession(session) else Redirect("http://ganghq.com")


  }


  def ws(token: String) = WebSocket.tryAcceptWithActor[JsValue, JsValue] { request =>
    import protocol._

    import scala.concurrent.ExecutionContext.Implicits.global

    backApi.me(token).map { (appUser: AppUser) =>

      val channels: Seq[Long] = (appUser.teams \\ "team").map(_.validate[Team](jsonTeam).asOpt).filter(_.isDefined).map(_.get.id)
      println("teams = " + channels)

      val uid: Long = appUser.id

      Right(UserActor.props(uid, channels) _)
    }.recover {
      case error =>
        println(error)
        Left(Forbidden)
    }

  }


  def renderChannel(id: String) = Action { implicit request =>
    Ok(views.html.renderedChannel(id,id))
  }

  def escapedFragment() = Action { implicit request =>
    request.getQueryString("_escaped_fragment_").map { ef =>
      val canonicalURI = new URI(ef).getPath.substring(1)
      val channelid = canonicalURI.split("/")(0)

      Ok(views.html.renderedChannel(channelid,canonicalURI))
    }.getOrElse {

      BadRequest( s"""
        <html>
          <body>
            GANG: Bad request with params ${request.rawQueryString}
          </body>
        </html>
        """)

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
      //      println(x.json)
      val result = x.json
      (result \ "appUser").validate[AppUser](jsonAppUser).get
    }


  }

  object protocol {

    //{"appUser":{"id":13,"username":"sumnulu","firstName":"","lastName":"","email":"ilgaz@fikrimuhal.com","teams":[]}}

    /*
    channels: [{id: 2, name: "General", description: "Default channel", team: null, messages: []}]
description: null
domains: null
id: 2
name: "Founder Level"
uniqueId: "541478492613351"
users: []
     */
    case class AppUser(id: Long, username: String, firstName: String = "", lastName: String = "", email: String, teams: JsValue)

    case class Team(id: Long, description: Option[String], name: String, uniqueId: String /*, users: List[AppUser]*/)

    implicit val jsonAppUser = Json.format[AppUser]
    implicit val jsonTeam = Json.format[Team]

  }

}
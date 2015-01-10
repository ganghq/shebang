package actors

import akka.actor.Actor
import akka.actor.ActorLogging
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import akka.actor.ActorRef
import akka.actor.Props


class UserActor(uid: String, channel: ActorRef, out: ActorRef) extends Actor with ActorLogging {
  override def preStart() = channel ! Subscribe

  def receive = {

    /**
     * from channel
     */
    case Message(_uid, s) if sender == channel =>
      val js = Json.obj("type" -> "message", "uid" -> _uid, "msg" -> s, "self" -> (uid == _uid))
      out ! js

    /**
     * from client
     * todo send to specific channel, not all of them
     * todo filter only allowed html/xml tags i.e. <b>, <img>, etc.
     */
    case js: JsValue =>
      (js \ "msg").validate[String] foreach {
        channel ! Message(uid, _)
      }

    case other =>
      log.error("unhandled: " + other)
  }
}

object UserActor {
  def props(uid: String)(out: ActorRef) = Props(new UserActor(uid, ChannelActor(), out))
}
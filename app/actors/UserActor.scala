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
    case Message(_uid, s, c) if sender == channel =>
      val js = Json.obj("type" -> "message", "uid" -> _uid, "msg" -> s, "channel" -> c, "self" -> (uid == _uid))
      out ! js

    case StatusUserTyping(_uid, t, c) if (sender == channel) && (uid != _uid) => //do not send self typing status about them self
      val js = Json.obj("type" -> "cmd_usr_typing", "uid" -> _uid, "isTyping" -> t, "channel" -> c)
      out ! js


    /**
     * from client
     * todo send to specific channel, not all of them
     * todo filter only allowed html/xml tags i.e. <b>, <img>, etc.
     */
    case js: JsValue =>
      (js \ "type").validate[String] foreach { msg_type =>
        (js \ "channel").validate[String] foreach { channelId =>
          msg_type match {
            case "message" =>
              (js \ "msg").validate[String] foreach { message =>
                channel ! Message(uid, message, channelId)
              }
            case "cmd_usr_typing" =>
              (js \ "isTyping").validate[Boolean] foreach { isTyping =>
                channel ! StatusUserTyping(uid, isTyping, channelId)
              }
            case other =>
              log.error("unknown message type do nothing!: " + js.toString)
          }
        }
      }

    case other =>
      log.error("unhandled: " + other)

  }


}

object UserActor {
  def props(uid: String)(out: ActorRef) = Props(new UserActor(uid, ChannelActor(), out))
}
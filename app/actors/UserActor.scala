package actors

import akka.actor.Actor
import akka.actor.ActorLogging
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import akka.actor.ActorRef
import akka.actor.Props
import scala.concurrent.duration._


class UserActor(uid: Long, channels: Map[Long, ActorRef], out: ActorRef) extends Actor with ActorLogging {

  /**
   * for a small performance gain
   */
  lazy val _channelsActrs = channels.values.toSet


  def receive = {

    /**
     * from channel
     */
    case Message(_uid, s, c, ts) if _channelsActrs.contains(sender) =>
      val js = Json.obj("type" -> "message", "uid" -> _uid, "msg" -> s, "channel" -> c, "ts" -> ts, "self" -> (uid == _uid))
      out ! js

    case StatusUserTyping(_uid, t, c) if _channelsActrs.contains(sender) && (uid != _uid) => //do not send self typing status about them self
      val js = Json.obj("type" -> "cmd_usr_typing", "uid" -> _uid, "isTyping" -> t, "channel" -> c)
      out ! js


    /**
     * from client
     * todo send to specific channel, not all of them
     * todo filter only allowed html/xml tags i.e. <b>, <img>, etc.
     */
    case js: JsValue =>
      (js \ "type").validate[String] foreach { msg_type =>
        (js \ "channel").validate[Long] foreach { channelId =>
          msg_type match {
            case "message" =>
              (js \ "msg").validate[String] foreach { message =>
                channels get channelId foreach (_ ! Message(uid, message, channelId))
              }
            case "cmd_usr_typing" =>
              (js \ "isTyping").validate[Boolean] foreach { isTyping =>
                channels get channelId foreach (_ ! StatusUserTyping(uid, isTyping, channelId))
              }
            case other =>
              log.error("unknown message type do nothing!: " + js.toString)
          }
        }
      }

    case Ping =>
      val js = Json.obj("type" -> "ping", "ts" -> System.currentTimeMillis)
      out ! js

    case other =>
      log.error("unhandled: " + other)


  }

  override def preStart() = {
    import scala.concurrent.ExecutionContext.Implicits.global

    _channelsActrs.foreach(_ ! Subscribe(uid))

    /**
     * For keeping connection alive
     */
    context.system.scheduler.schedule(30 seconds, 45 seconds, self, Ping)
  }


}

object UserActor {
  def props(uid: Long, channelIds: Seq[Long])(out: ActorRef) = {
    val channels: Map[Long, ActorRef] = (channelIds map (id => (id, ChannelActor(id)))).toMap

    //used by broadcast messages (this workaround maybe buggy)
    val channelsWithDefault = channels + (ChannelActor.TODO_DEFAULT_CHANNEL_ID -> ChannelActor.defaultChannel)

    Props(new UserActor(uid, channelsWithDefault, out))
  }


}
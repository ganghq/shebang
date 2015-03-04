package actors

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.dispatch.sysmsg.Terminate
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import akka.actor.ActorRef
import akka.actor.Props
import utils.RateLimit
import scala.concurrent.duration._


class UserActor(uid: Long, channels: Map[Long, ActorRef], out: ActorRef) extends Actor with ActorLogging {

  /**
   * for a small performance gain
   */
  lazy val _channelsActrs = channels.values.toSet

  /**
   * at most 20 call per 30 seconds allowed
   */
  val messageRateLimit = new RateLimit(10, 10)

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

                //todo move this empty message check to some filters functions
                //todo add rate limit filter, and kill the connection on bad behavior
                //todo do not use System.currentTimeMillis. ts should be unique for the channel.
                if (messageRateLimit.tickAndCheck) {
                  //todo send error message to the client if message size exceeds upper bound!
                  if (message.size > 0 && message.size < 2000) channels get channelId foreach (_ ! Message(uid, message, channelId))
                } else {
                  //rate limit exceeded! Terminate self connection. i.e. kill websocket connection
                  //todo send message to the client that they should behave nice.
                  println(s"RATE LIMIT EXCEEDED! DIE $uid.")
                  context stop self
                }
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

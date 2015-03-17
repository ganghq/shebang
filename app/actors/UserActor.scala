package actors

import actors.UserActor.{PingTimeOut, RateLimitReached}
import akka.actor._
import akka.dispatch.sysmsg.Terminate
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import utils.RateLimit
import scala.concurrent.duration._


class UserActor(uid: Long, channels: Map[Long, ActorRef], out: ActorRef) extends Actor with ActorLogging {

  import scala.concurrent.ExecutionContext.Implicits.global

  /**
   * for a small performance gain
   */
  lazy val _channelsActrs = channels.values.toSet

  var pingPongPoisonPill = createPingPongPoisonPillSchedule



  /**
   * at most 10 call per 10 seconds allowed
   */
  val messageRateLimit = new RateLimit(10, 10)

  val preShutdownState: Receive = {
    case RateLimitReached =>
      println("RATE LIMIT !!!!!!!")
      val js = Json.obj("type" -> "error", "code" -> s"rate_limit", "msg" -> s"RATE LIMIT EXCEEDED! BACK OFF! ${0.1 * messageRateLimit.getRate}/s", "ts" -> System.currentTimeMillis)
      out ! js

      //kill after 10 seconds i.e. hell ban for 10 sec
      context.system.scheduler.scheduleOnce(10 seconds, self, PoisonPill)

    case x =>
      messageRateLimit.tick
      println(x)
    //catch all messages and do nothing

  }


  def receive = {

    /**
     * from channel
     */
    case Message(_uid, s, c, ts) if _channelsActrs.contains(sender) =>
      val canEdit = UserActor.admins.contains(uid) || (uid == _uid)
      out ! Json.obj(
      "type" -> "message",
      "uid" -> _uid,
      "msg" -> s,
      "channel" -> c,
      "ts" -> ts,
      "self" -> (uid == _uid),
      "canedit" -> canEdit)


    case StatusUserTyping(_uid, t, c) if _channelsActrs.contains(sender) && (uid != _uid) => //do not send self typing status about them self
      val js = Json.obj("type" -> "cmd_usr_typing", "uid" -> _uid, "isTyping" -> t, "channel" -> c)
      out ! js

    case PingTimeOut =>
      out ! Json.obj("type" -> "error", "code" -> s"ping_time_out", "msg" -> "Sorry you must ping me !", "ts" -> System.currentTimeMillis)

      self ! PoisonPill

    /**
     * from client
     * todo filter only allowed html/xml tags i.e. <b>, <img>, etc.
     */
    case js: JsValue =>

      (js \ "type").validate[String] foreach { msg_type =>
        msg_type match {

          case "ping" =>
            out ! Json.obj("type" -> "pong", "ts" -> System.currentTimeMillis, "observedRate" -> s"${0.1 * messageRateLimit.getRate}/s")
            pingPongPoisonPill.cancel()
            pingPongPoisonPill = createPingPongPoisonPillSchedule
          case _ =>
          //do nothing
        }

        (js \ "channel").validate[Long] foreach { channelId =>
          msg_type match {
            case "message" =>
              (js \ "msg").validate[String] foreach { messageUnEscaped =>

                //todo front end escaping
                // escape all xml tags
                val message = messageUnEscaped //xml.Utility.escape(messageUnEscaped)

                //todo move this empty message check to some filters functions
                //todo add rate limit filter, and kill the connection on bad behavior
                //todo do not use System.currentTimeMillis. ts should be unique for the channel.

                if (messageRateLimit.tickAndCheck) {
                  //todo send error message to the client if message size exceeds upper bound!



                  if (!handleCmd(message)) (js \ "ts").validate[Long].asOpt.map { ts =>

                    val command = ModifyMessageCommand(Message(uid, message.stripMargin, channelId, ts))
                    if (message.size < 2000) channels get channelId foreach (_ ! command)

                  }.getOrElse {

                    val command = Message(uid, message.stripMargin, channelId)
                    if (message.stripMargin.size > 0 && message.size < 2000) channels get channelId foreach (_ ! command)

                  }


                } else {
                  //rate limit exceeded! Terminate self connection. i.e. kill websocket connection
                  self ! RateLimitReached
                  context become preShutdownState
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

  //fixme
  def handleCmd(s: String) = s match {
    case "/x-restart" if UserActor.admins.contains(uid) =>
      import scala.sys.process._
      "/gang/bin/start-shebang.sh".run()
      true
    case _ =>
      false
  }

  override def preStart() = {


    _channelsActrs.foreach(_ ! Subscribe(uid))

    /**
     * For keeping connection alive
     */
    context.system.scheduler.schedule(30 seconds, 45 seconds, self, Ping)
  }

  def createPingPongPoisonPillSchedule = context.system.scheduler.scheduleOnce(60 seconds, self, PingTimeOut)


}

object UserActor {
  def props(uid: Long, channelIds: Seq[Long])(out: ActorRef) = {
    val channels: Map[Long, ActorRef] = (channelIds map (id => (id, ChannelActor(id)))).toMap

    //used by broadcast messages (this workaround maybe buggy)
    val channelsWithDefault = channels + (ChannelActor.TODO_DEFAULT_CHANNEL_ID -> ChannelActor.defaultChannel)

    Props(new UserActor(uid, channelsWithDefault, out))
  }

  object RateLimitReached

  object PingTimeOut

  /**
   * admin uids
   */
  val admins: List[Long] = List(5, 6, 7, 8, 13)
}

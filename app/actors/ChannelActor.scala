package actors


import akka.actor.Actor
import akka.actor.ActorLogging
import akka.event.LoggingReceive
import akka.actor.ActorRef
import akka.actor.Terminated
import play.api.libs.ws.{WSResponse, WS}
import play.libs.Akka
import akka.actor.Props
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

class ChannelActor(channelId: String) extends Actor with ActorLogging {
  //  println("CHANNEL CREATED ID "+ channelId)
  var onlineUsers = Map[ActorRef, String]()

  var posts = List[Message]()

  var lastPersistTime: Long = 0
  val numberOfMessagesWeShouldKeep = 1000

  def receive = LoggingReceive {
    /**
     * Send this message to the each user which registered to this channel
     */
    case m: Message =>
      posts +:= m

      onlineUsers.keys foreach (_ ! m)

    case m: StatusUserTyping => onlineUsers.keys foreach (_ ! m)

    /**
     * Subscribe new user to this channel
     */
    case Subscribe(uid) =>
      onlineUsers += sender -> uid

      //watch the sender actor for Termination so we can deRegister them
      context watch sender

      //send last 300 message for each channel to the new User
      //todo send only users subscribed channels
      sender ! Message("_replyingChannelHistory_STARTED", channelId)

      (posts take 300).reverse foreach (sender ! _)

      sender ! Message("_replyingChannelHistory_FINISHED", channelId)

      //      sender ! Message("Cowboy Bebop (BOT)", "Gotta Knock a Little Harder!")
      //      sender ! Message("Shebang", s"Hello welcome back #!")

      //send status to every online user
      onlineUsers.keys foreach (_ ! Message("_userStatusChanged_ONLINE", uid))
      //send every other online users status to sender
      onlineUsers.values.filterNot(_ == uid) foreach (sender ! Message("_userStatusChanged_ONLINE", _))


    /**
     * broadcast number of online users
     */
    case BroadcastStatus =>
      onlineUsers.keys foreach (_ ! Message("_numberOfOnlineUsers", s"${onlineUsers.size}"))


    /**
     * deRegister the user from this channel on user (UserActor) termination.
     */
    case Terminated(user) => onlineUsers.get(user).map { uid =>
      onlineUsers -= user

      // Eger bu user'i cikartinca ve baska ayni uid li user kalmazsa o zaman OFFLINE oldu mesaji yolla.
      val userIsOffline = !onlineUsers.values.exists(_ == uid)

      if (userIsOffline) {
        onlineUsers foreach { case (userActor, _) => userActor ! Message("_userStatusChanged_OFFLINE", uid)}
      }
    }

    case PersistMessages =>
      val persistPeriod = 1000 * 60 * 10
      val persistEveryNMessages = 1000

      val now = System.currentTimeMillis
      val diffTime = now - lastPersistTime

      val isTimeToPersist = (diffTime / persistPeriod) > 0

      if (isTimeToPersist) {
        val notPersistedMessages = posts.filter(_.ts > lastPersistTime)

        // send messages to backend api
        //todo do not use toLong, refactor channelId from String to Long
        backendApi.persistMessages(notPersistedMessages,channelId.toLong)

        //todo only update time after backend returns success. otherwise on error we will lose some messages

        //update time
        lastPersistTime = notPersistedMessages.head.ts

        //remove old messages so we will not run out of memory
        posts = posts.take(numberOfMessagesWeShouldKeep)
      }

  }

  override def preStart() = {

    /**
     * Every 30 seconds broadcast number of online users, just for default channel
     */
    if (channelId == "") context.system.scheduler.schedule(3 seconds, 60 seconds, self, BroadcastStatus)


    //max 2 minutes, so each channel will start persistence at different time from each other.
    val initialDelayForPersistence = (Random nextInt 120) seconds

    //try persisting the history
    if (channelId != "") context.system.scheduler.schedule(initialDelayForPersistence, 60 seconds, self, PersistMessages)
  }


}


object ChannelActor {
  lazy val defaultChannel = Akka.system().actorOf(Props(new ChannelActor("")))

  var allChannels = Map[String, ActorRef]()

  def apply(channelId: String): ActorRef = allChannels.getOrElse(channelId, {
    val newChannel = Akka.system().actorOf(Props(new ChannelActor(channelId)))
    allChannels += channelId -> newChannel
    newChannel
  })

  def apply(channelIds: Seq[String]): Seq[ActorRef] = channelIds map apply

}

case class Message(uid: String, txt: String, channel: String = "", ts: Long = System.currentTimeMillis)

case class StatusUserTyping(uid: String, isTyping: Boolean, channel: String = "")

case class UserList(users: List[String])

case class Subscribe(uid: String)

object BroadcastStatus

object PersistMessages

object Ping


object backendApi {

  def persistMessages(messages: Seq[Message]) = {
    //    http://app.ganghq.com/api/saveMessages
    import scala.concurrent.ExecutionContext.Implicits.global


    val url = "http://app.ganghq.com/api/saveMessages"


    val ws = WS.url(url)

    (ws post()) map { (x: WSResponse) =>
      import protocol.jsonAppUser
      //      println(x.json)
      val result = x.json
      (result \ "appUser").validate[AppUser](jsonAppUser).get
    }

  }

}











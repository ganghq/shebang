package actors


import akka.actor._
import akka.event.LoggingReceive
import controllers.backApi.protocol
import play.api.libs.json._
import play.api.libs.ws.{WSRequestHolder, WSResponse, WS}
import play.libs.Akka
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

class ChannelActor(channelId: Long) extends Actor with Stash with ActorLogging {

  var onlineUsers = Map[ActorRef, Long]()

  var posts = List[Message]()

  var lastPersistTime: Long = 0
  val numberOfMessagesWeShouldKeep = 1000

  /**
   * To create unique time stamps
   */
  var lastMessagesUniqueTimeStamp: Long = _

  def getUniqueTs(thatTS: Long = System currentTimeMillis) = {
    lastMessagesUniqueTimeStamp = thatTS + (if (lastMessagesUniqueTimeStamp == thatTS) 1 else 0)
    lastMessagesUniqueTimeStamp
  }

  def generateUniqueMessage(that: Message) = that.copy(ts = getUniqueTs())


  object todo_ids {
    val _PERSISTED: Long = -15

    val _userStatusChanged_OFFLINE: Long = -14

    val _numberOfOnlineUsers: Long = -13

    val _userStatusChanged_ONLINE: Long = -12

    val _replyingChannelHistory_FINISHED: Long = -11

    val _replyingChannelHistory_STARTED: Long = -10

  }

  def receive = LoggingReceive {

    case MessagesReceivedFromBackend =>
      unstashAll()
      context become stateReady

    //todo are there any special messages that should be handled immediately?
    case _ => stash()
  }

  /**
   * Start accepting messages history fetched
   */
  val stateReady = LoggingReceive {
    /**
     * Send this message to the each user which registered to this channel
     */
    case m: Message =>
      val persistentMessage: Message = generateUniqueMessage(m)

      posts +:= persistentMessage

      onlineUsers.keys foreach (_ ! persistentMessage)

    /**
     * todo Handle api delete and edit. They are not implemented yet
     * send api modified messages maybe store them some where
     */
    case ModifyMessageCommand(m) =>
      val previousSize = posts.size

      val isUsersOwnPosts: Boolean = onlineUsers get sender contains m.uid

      //todo
      val admin_id_fix_me = 7L

      lazy val isAdmin = onlineUsers get sender contains admin_id_fix_me

      if (isUsersOwnPosts || isAdmin) {
        val (_possibleOldMessage, modifiedPosts) = posts.partition(_.ts == m.ts)
        _possibleOldMessage.headOption foreach { (oldMessage: Message) =>

          // We had requested message for to be edited or to be delete

          // Warning! we are changing UID in case this is an admin action
          val niceMessage = m.copy(uid = oldMessage.uid)

          if (niceMessage.txt.size > 0) {
            //message edited
            posts = (niceMessage +: modifiedPosts) sortBy (-_.ts)

          } else {
            //message deleted
            posts = modifiedPosts
          }

          onlineUsers.keys foreach (_ ! niceMessage)
        }

      } else {
        // they do not have access to delete or edit
      }


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
      sender ! Message(todo_ids._replyingChannelHistory_STARTED, channelId.toString)

      (posts take 70).reverse foreach (sender ! _)

      sender ! Message(todo_ids._replyingChannelHistory_FINISHED, channelId.toString)

      //      sender ! Message("Cowboy Bebop (BOT)", "Gotta Knock a Little Harder!")
      //      sender ! Message("Shebang", s"Hello welcome back #!")

      //send status to every online user
      onlineUsers.keys foreach (_ ! Message(todo_ids._userStatusChanged_ONLINE, uid.toString))
      //send every other online users status to sender
      onlineUsers.values.filterNot(_ == uid) foreach (userId => sender ! Message(todo_ids._userStatusChanged_ONLINE, userId.toString))


    /**
     * broadcast number of online users
     */
    case BroadcastStatus =>
      onlineUsers.keys foreach (_ ! Message(todo_ids._numberOfOnlineUsers, s"${onlineUsers.size}"))


    /**
     * deRegister the user from this channel on user (UserActor) termination.
     */
    case Terminated(user) => onlineUsers.get(user).map { uid =>
      onlineUsers -= user

      // Eger bu user'i cikartinca ve baska ayni uid li user kalmazsa o zaman OFFLINE oldu mesaji yolla.
      val userIsOffline = !onlineUsers.values.exists(_ == uid)

      if (userIsOffline) {
        onlineUsers foreach { case (userActor, _) => userActor ! Message(todo_ids._userStatusChanged_OFFLINE, uid.toString)}
      }

    }

    case PersistMessages =>

      //10 minutes
      val persistPeriod = 1000 * 60 * 10
      val persistEveryNMessages = 1000

      val now = System.currentTimeMillis

      // scheduler may start the job few seconds delayed: so we are adding 10 seconds otherwise job will start at next Tick!
      // (ps: that would not be so serious problem for this use case)
      val ten_Seconds = 10000
      val diffTime = now - lastPersistTime + ten_Seconds

      //      println(s"sec:${diffTime.toLong / 1000L}, channel:$channelId")

      val isTimeToPersist = (diffTime / persistPeriod) > 0

      if (isTimeToPersist) {
        val notPersistedMessages = posts.filter(_.ts > lastPersistTime)

        // send messages to backend api
        if (notPersistedMessages.size > 0 && channelId > 0) {
          backendApi.persistMessages(notPersistedMessages)

          //remove old messages so we will not run out of memory
          posts = posts.take(numberOfMessagesWeShouldKeep)

          //todo just for remote debug delete me
          onlineUsers.keys foreach (_ ! Message(todo_ids._PERSISTED, s"posts.size: ${posts.size}, channelid: $channelId, lastPersistTime: $lastPersistTime"))
        }


        //todo only update time after backend returns success. otherwise on error we will lose some messages

        //update time
        lastPersistTime = notPersistedMessages.headOption.map(_.ts) getOrElse now

      }

  }

  override def preStart() = {

    /**
     * Every 30 seconds broadcast number of online users, just for default channel
     */
    if (channelId == ChannelActor.TODO_DEFAULT_CHANNEL_ID) context.system.scheduler.schedule(3 seconds, 60 seconds, self, BroadcastStatus)


    //max 2 minutes, so each channel will start persistence at different time from each other.
    val initialDelayForPersistence = (Random nextInt 120) seconds

    //try persisting the history
    if (channelId != ChannelActor.TODO_DEFAULT_CHANNEL_ID) {
      context.system.scheduler.schedule(initialDelayForPersistence, 60 seconds, self, PersistMessages)

      //fetch history!
      val oneWeek = 1000 * 60 * 60 * 24 * 7
      val now = System.currentTimeMillis
      val oneWeekBeforeNow = now - oneWeek
      val futureMessages = backendApi.readMessages(channelId, now, oneWeekBeforeNow)
      futureMessages.foreach { ms =>

        //we are sorting in case they are not! But should be!
        val groupedByTS = ms groupBy (x => x.ts)

        val uniqueMessages = (groupedByTS.values map { (m: Seq[Message]) =>
          //todo report error if more than one. I.E. Time stamp must be unique per channel
          assert(m.size == 1)
          m.head
        }).toList sortBy (-_.ts)

        posts ++= uniqueMessages

        self ! MessagesReceivedFromBackend
      }
    } else {
      // default channel do not wait the api
      //todo refactor
      self ! MessagesReceivedFromBackend
    }
  }

  /**
   * Persist all messages, before termination
   */
  override def postStop() = {
    println(s"Terminating channel! ID:$channelId")
    val now = System.currentTimeMillis
    val notPersistedMessages = posts.filter(_.ts > lastPersistTime)
    if (notPersistedMessages.size > 0 && channelId > 0) {
      backendApi.persistMessages(notPersistedMessages)
      lastPersistTime = notPersistedMessages.headOption.map(_.ts) getOrElse now
    }
  }

}


object ChannelActor {
  val TODO_DEFAULT_CHANNEL_ID: Long = -1

  lazy val defaultChannel = Akka.system().actorOf(Props(new ChannelActor(TODO_DEFAULT_CHANNEL_ID)))

  var allChannels = Map[Long, ActorRef]()

  def apply(channelId: Long): ActorRef = allChannels.getOrElse(channelId, {
    val newChannel = Akka.system().actorOf(Props(new ChannelActor(channelId)))
    allChannels += channelId -> newChannel
    newChannel
  })

  def apply(channelIds: Seq[Long]): Seq[ActorRef] = channelIds map apply

}

sealed trait Status

case class Message(uid: Long, txt: String, channel: Long = ChannelActor.TODO_DEFAULT_CHANNEL_ID, ts: Long = System.currentTimeMillis)

case class ModifyMessageCommand(m: Message)

case class StatusUserTyping(uid: Long, isTyping: Boolean, channel: Long = ChannelActor.TODO_DEFAULT_CHANNEL_ID) extends Status

case class UserList(users: List[String])

case class Subscribe(uid: Long)

object BroadcastStatus

object PersistMessages

object Ping

object MessagesReceivedFromBackend


object ClientApi {

}

/*

  object todo_ids {

    val _PERSISTED: Long = -15

    val _userStatusChanged_OFFLINE: Long = -14

    val _numberOfOnlineUsers: Long = -13

    val _userStatusChanged_ONLINE: Long = -12

    val _replyingChannelHistory_FINISHED: Long = -11

    val _replyingChannelHistory_STARTED: Long = -10

  }
 */


object backendApi {
  implicit val toJsonMessage: Format[Message] = Json.format[Message]

  import scala.concurrent.ExecutionContext.Implicits.global
  import play.api.Play.current

  val SaveMessagesUrl = "http://app.ganghq.com/api/saveMessages"
  val GetMessagesUrl = "http://app.ganghq.com/api/getMessages"

  object timeouts {
    //10 seconds
    val defaultTimeout = 1000 * 10

    val readMessagesTimeout = defaultTimeout
    val writeMessagesTimeout = defaultTimeout
  }


  def persistMessages(messages: Seq[Message]) = {
    val data = JsArray(messages map (Json.toJson(_))) toString

    (WS
      url SaveMessagesUrl
      withRequestTimeout timeouts.writeMessagesTimeout
      post data) map { (x: WSResponse) =>
      val result = x.json
      val maybeSuccess = (result \ "message").validate[String].asOpt.map(_ == "OK")
      maybeSuccess
    }

  }


  def readMessages(channelId: Long, endTS: Long, startTS: Long): Future[Seq[Message]] = (WS
    url GetMessagesUrl
    withQueryString("channelId" -> channelId.toString, "startDate" -> startTS.toString, "endDate" -> endTS.toString)
    withRequestTimeout timeouts.readMessagesTimeout
    get) map { (x: WSResponse) =>




    val result = x.json

    val jMessageSeq: Seq[JsValue] = (result \ "messageList").validate[JsArray].asOpt.getOrElse(new JsArray).value

    //todo do validation, not safe!
    jMessageSeq.map { js =>

      val ts: Long = (js \ "date").as[Long]
      val uid: Long = (js \ "sender" \ "id").as[Long]
      val channelId: Long = (js \ "channel" \ "id").as[Long]
      val txt: String = (js \ "message").as[String]

      Message(uid, txt, channelId, ts)
    }
  }


}

/*

 */















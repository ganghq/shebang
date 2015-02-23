package actors

import java.util

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.event.LoggingReceive
import akka.actor.ActorRef
import akka.actor.Terminated
import play.libs.Akka
import akka.actor.Props
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class ChannelActor extends Actor with ActorLogging {
  var users = Set[ActorRef]()

  var posts = List[Message]()

  def receive = LoggingReceive {
    /**
     * Send this message to the each user which registered to this channel
     */
    case m: Message =>
      posts +:= m

      users foreach {
        _ ! m

      }

    /**
     * Subscribe new user to this channel
     */
    case Subscribe =>
      users += sender

      //watch the sender actor for Termination so we can deRegister them
      context watch sender

      //send last 300 message for each channel to the new User
      //todo send only users subscribed channels
      (posts groupBy (_.channel) flatMap (_._2 take 300) toList).reverse foreach (sender ! _)

      sender ! Message("Cowboy Bebop (BOT)", "Gotta Knock a Little Harder!")
    //      sender ! Message("Shebang", s"Hello welcome back #!")

    /**
     * broadcast number of online users
     */
    case BroadcastStatus =>
      users foreach (_ ! Message("_numberOfOnlineUsers", s"${users.size}"))


    /**
     * deRegister the user from this channel on user (UserActor) termination.
     */
    case Terminated(user) => users -= user

  }

  /**
   * Every 30 seconds broadcast number of online users
   */
  override def preStart() = {
    context.system.scheduler.schedule(3 seconds, 30 seconds, self, BroadcastStatus)
  }

  ChannelActor()

}


object ChannelActor {
  lazy val channel = Akka.system().actorOf(Props[ChannelActor])
  lazy val generalChannel = Akka.system().actorOf(Props[ChannelActor])
  lazy val generalChannel2 = Akka.system().actorOf(Props[ChannelActor])

  def apply() = channel
}

case class Message(uid: String, s: String, channel: String = "")

case class UserList(users: List[String])

object Subscribe

object BroadcastStatus
package actors

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

      //sen last 20 message to the new User
      posts take 20 foreach (sender ! _)
      sender ! Message("Cowboy Bebop (BOT)", "Gotta Knock a Little Harder!")
      sender ! Message("Shebang", s"Hello welcome back #!")

    /**
     * broadcast number of online users
     */
    case BroadcastStatus =>
      users foreach (_ ! Message("Shebang", s" ${users.size} users online"))


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


}


object ChannelActor {
  lazy val channel = Akka.system().actorOf(Props[ChannelActor])

  def apply() = channel
}

case class Message(uid: String, s: String)

case class UserList(users: List[String])

object Subscribe

object BroadcastStatus
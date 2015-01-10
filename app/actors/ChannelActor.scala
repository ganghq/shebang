package actors

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.event.LoggingReceive
import akka.actor.ActorRef
import akka.actor.Terminated
import play.libs.Akka
import akka.actor.Props

class ChannelActor extends Actor with ActorLogging {
  var users = Set[ActorRef]()

  def receive = LoggingReceive {
    /**
     * Send this message to the each user which registered to this channel
     */
    case m: Message => users foreach {_ ! m}

    /**
     * Subscribe new user to this channel
     */
    case Subscribe =>
      users += sender
      //watch the sender actor for Termination so we can deRegister them
      context watch sender

    /**
     * deRegister the user from this channel on user (UserActor) termination.
     */
    case Terminated(user) => users -= user

  }
}

object ChannelActor {
  lazy val channel = Akka.system().actorOf(Props[ChannelActor])

  def apply() = channel
}

case class Message(uid: String, s: String)

object Subscribe
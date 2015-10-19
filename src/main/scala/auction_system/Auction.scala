package auction_system

import akka.actor._
import akka.actor.FSM
import akka.actor.Props
import akka.event.LoggingReceive
import scala.concurrent.duration._
import scala.util.Random

// received events
final case object Relist
final case class Bid(price: Int)
// sent events
final case class ObjectSold(theObject: Any, buyer: ActorRef, price: Int)
final case class ObjectBought(theObject: Any, seller: ActorRef, price: Int)
final case class ObjectTimedout(theObject: Any)

// states
sealed trait State
case object Ignored extends State
case object Activated extends State
case object Sold extends State
    
final case class Data(seller: ActorRef, theObject: Any, bidTimeout: FiniteDuration, delTimeout: FiniteDuration, winner: ActorRef = null, price: Int = -1)

object Auction {
  def props(seller: ActorRef, theObject: Any, bidTimeout: FiniteDuration = Duration(2, SECONDS), delTimeout: FiniteDuration = Duration(20, MILLISECONDS)): Props = Props(new Auction(Data(seller, theObject, bidTimeout, delTimeout)))
}
class Auction(data: Data) extends FSM[State, Data] {
    // Created = Activated, see timeout handling
    startWith(Activated, data.copy(price = 0, winner = null))
    
    when (Activated, stateTimeout = data.bidTimeout) {
      case Event(Bid(price: Int), data) if (price > data.price) =>
          stay using data.copy(price = price, winner = sender)
      case Event(Bid(price: Int), data) =>
          stay 
      case Event(FSM.StateTimeout, data) if (data.price > 0) =>
          data.winner ! ObjectBought(data.theObject, data.seller, data.price)
          data.seller ! ObjectSold(data.theObject, data.winner, data.price)
          goto(Sold)
      case Event(FSM.StateTimeout, data) =>
          goto(Ignored)
    }
    
    when (Ignored, stateTimeout = data.delTimeout) {
      case Event(Relist, data) =>
        goto(Activated)
      case Event(FSM.StateTimeout, data) =>
        data.seller ! ObjectTimedout(data.theObject)
        stop()
    }
    
    when (Sold, stateTimeout = data.delTimeout) {
      case Event(FSM.StateTimeout, data) =>
        stop()
    }
    
    initialize()
}

object Buyer {
  def props(auctions: Seq[ActorRef]): Props = Props(new Buyer(auctions))
}
class Buyer(auctions: Seq[ActorRef]) extends Actor {
  def receive = LoggingReceive {
    case "Bid" =>
      val price = Random.nextInt(100)
      val auction = auctions(Random.nextInt(auctions.size))
      auction ! Bid(price)
  }
}

class AuctionMain extends Actor {
  
  def AwaitSold(objects: Int = 3): Receive = LoggingReceive {
    case "Init" =>
      val auction1 = context.actorOf(Auction.props(self, this), "auction1")
      val auction2 = context.actorOf(Auction.props(self, this), "auction2")
      val auction3 = context.actorOf(Auction.props(self, this), "auction3")
      
      val auctions = Seq[ActorRef](auction1, auction2, auction3)
      
      val buyer1 = context.actorOf(Buyer.props(auctions), "buyer1")
      val buyer2 = context.actorOf(Buyer.props(auctions), "buyer2")
      val buyer3 = context.actorOf(Buyer.props(auctions), "buyer3")
      val buyer4 = context.actorOf(Buyer.props(auctions), "buyer4")
      
      buyer1 ! "Bid"
      buyer2 ! "Bid"
      buyer3 ! "Bid"
      buyer4 ! "Bid"
    
    case ObjectTimedout(_) | ObjectSold(_, _, _) =>
      if (objects == 1) {
        context.system.shutdown
      }
      context become AwaitSold(objects - 1)
  }
  
  def receive = AwaitSold(3)
}

object AuctionApp extends App {
  val system = ActorSystem("AuctionMain")
  val mainActor = system.actorOf(Props[AuctionMain], "mainActor")

  mainActor ! "Init"

  system.awaitTermination()
}
package auction_system

import akka.actor._
import akka.actor.Props
import akka.event.LoggingReceive
import scala.concurrent.duration._
import scala.util.Random

// received events
final case object BidTimerExpired
final case object DelTimerExpired
final case object Relist
final case class Bid(price: Int)
// sent events
final case class ObjectSold(theObject: Any, buyer: ActorRef, price: Int)
final case class ObjectBought(theObject: Any, seller: ActorRef, price: Int)
final case class ObjectTimedout(theObject: Any)

object Auction {
  def props(seller: ActorRef, theObject: Any, bidTimeout: FiniteDuration = Duration(20, MILLISECONDS), delTimeout: FiniteDuration = Duration(2, SECONDS)): Props = Props(new Auction(seller, theObject, bidTimeout, delTimeout))
}
class Auction(seller: ActorRef, theObject: Any, bidTimeout: FiniteDuration, delTimeout: FiniteDuration) extends Actor {
    val system = akka.actor.ActorSystem("system")
    import system.dispatcher
    
    def Created(bidTimer: Cancellable): Receive = LoggingReceive {
      case Bid(price: Int) if (price > 0) =>
        context become Activated(sender, price)
      case Bid(_) =>
      case BidTimerExpired =>
        context become Ignored(system.scheduler.scheduleOnce(delTimeout, self, DelTimerExpired))
    }
    
    def Ignored(delTimer: Cancellable): Receive = LoggingReceive {
      case Relist =>
        delTimer.cancel()
        context become Created(system.scheduler.scheduleOnce(bidTimeout, self, BidTimerExpired))
      case DelTimerExpired =>
        seller ! ObjectTimedout(theObject)
        context.stop(self)
    }
    
    def Activated(winner: ActorRef, price: Int): Receive = LoggingReceive {
      case Bid(newPrice: Int) if (newPrice > price) =>
        context become Activated(sender, newPrice)
      case Bid(_) =>
      case BidTimerExpired =>
        context become Sold(winner, price)
        winner ! ObjectBought(theObject, seller, price)
        seller ! ObjectSold(theObject, winner, price)
        system.scheduler.scheduleOnce(delTimeout, self, DelTimerExpired)
    }
    
    def Sold(winner: ActorRef, price: Int): Receive = LoggingReceive {
      case DelTimerExpired =>
        context.stop(self)
    }
    
    // initial state
    def receive = Created(system.scheduler.scheduleOnce(bidTimeout, self, BidTimerExpired))
}

object Buyer {
  def props(auctions: Seq[ActorRef]): Props = Props(new Buyer(auctions))
}
class Buyer(auctions: Seq[ActorRef]) extends Actor {
  def receive = LoggingReceive {
    case "Bid" =>
      val price = Random.nextInt()
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
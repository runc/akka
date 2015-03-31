/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ ActorRef, Cancellable, PoisonPill, Props }
import akka.stream.impl.StreamLayout.Module
import akka.stream.scaladsl.OperationAttributes
import akka.stream.{ Outlet, Shape, SourceShape }
import org.reactivestreams._

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

/**
 * INTERNAL API
 */
private[akka] abstract class SourceModule[+Out, +Mat](val shape: SourceShape[Out]) extends Module {

  def create(materializer: ActorFlowMaterializerImpl, flowName: String): (Publisher[Out] @uncheckedVariance, Mat)

  override def replaceShape(s: Shape): Module =
    if (s == shape) this
    else throw new UnsupportedOperationException("cannot replace the shape of a Source, you need to wrap it in a Graph for that")

  // This is okay since the only caller of this method is right below.
  protected def newInstance(shape: SourceShape[Out] @uncheckedVariance): SourceModule[Out, Mat]

  override def carbonCopy: Module = {
    val out = new Outlet[Out](shape.outlet.toString)
    newInstance(SourceShape(out))
  }

  override def subModules: Set[Module] = Set.empty

  def amendShape(attr: OperationAttributes): SourceShape[Out] = {
    attr.nameOption match {
      case None ⇒ shape
      case s: Some[String] if s == attributes.nameOption ⇒ shape
      case Some(name) ⇒ shape.copy(outlet = new Outlet(name + ".out"))
    }
  }

}

/**
 * INTERNAL API
 * Holds a `Subscriber` representing the input side of the flow.
 * The `Subscriber` can later be connected to an upstream `Publisher`.
 */
private[akka] final class SubscriberSource[Out](val attributes: OperationAttributes, shape: SourceShape[Out]) extends SourceModule[Out, Subscriber[Out]](shape) {

  override def create(materializer: ActorFlowMaterializerImpl, flowName: String): (Publisher[Out], Subscriber[Out]) = {
    val processor = new Processor[Out, Out] {
      @volatile private var subscriber: Subscriber[_ >: Out] = null

      override def subscribe(s: Subscriber[_ >: Out]): Unit = subscriber = s

      override def onError(t: Throwable): Unit = subscriber.onError(t)
      override def onSubscribe(s: Subscription): Unit = subscriber.onSubscribe(s)
      override def onComplete(): Unit = subscriber.onComplete()
      override def onNext(t: Out): Unit = subscriber.onNext(t)
    }

    (processor, processor)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Subscriber[Out]] = new SubscriberSource[Out](attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new SubscriberSource[Out](attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Construct a transformation starting with given publisher. The transformation steps
 * are executed by a series of [[org.reactivestreams.Processor]] instances
 * that mediate the flow of elements downstream and the propagation of
 * back-pressure upstream.
 */
private[akka] final class PublisherSource[Out](p: Publisher[Out], val attributes: OperationAttributes, shape: SourceShape[Out]) extends SourceModule[Out, Unit](shape) {
  override def create(materializer: ActorFlowMaterializerImpl, flowName: String) = (p, ())

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Unit] = new PublisherSource[Out](p, attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new PublisherSource[Out](p, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Start a new `Source` from the given `Future`. The stream will consist of
 * one element when the `Future` is completed with a successful value, which
 * may happen before or after materializing the `Flow`.
 * The stream terminates with an error if the `Future` is completed with a failure.
 */
private[akka] final class FutureSource[Out](future: Future[Out], val attributes: OperationAttributes, shape: SourceShape[Out]) extends SourceModule[Out, Unit](shape) {
  override def create(materializer: ActorFlowMaterializerImpl, flowName: String) =
    future.value match {
      case Some(Success(element)) ⇒
        (SynchronousIterablePublisher(List(element), s"$flowName-0-synciterable"), ()) // Option is not Iterable. sigh
      case Some(Failure(t)) ⇒
        (ErrorPublisher(t, s"$flowName-0-error").asInstanceOf[Publisher[Out]], ())
      case None ⇒
        (ActorPublisher[Out](materializer.actorOf(FuturePublisher.props(future, materializer.settings),
          name = s"$flowName-0-future")), ()) // FIXME this does not need to be an actor
    }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Unit] = new FutureSource(future, attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new FutureSource(future, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
private[akka] final class LazyEmptySource[Out](val attributes: OperationAttributes, shape: SourceShape[Out]) extends SourceModule[Out, Promise[Unit]](shape) {
  import ReactiveStreamsCompliance._

  override def create(materializer: ActorFlowMaterializerImpl, flowName: String) = {
    val p = Promise[Unit]()

    val pub = new Publisher[Unit] {
      override def subscribe(s: Subscriber[_ >: Unit]) = {
        requireNonNullSubscriber(s)
        tryOnSubscribe(s, new Subscription {
          override def request(n: Long): Unit = ()
          override def cancel(): Unit = p.trySuccess(())
        })
        p.future.onComplete {
          case Success(_)  ⇒ tryOnComplete(s)
          case Failure(ex) ⇒ tryOnError(s, ex) // due to external signal
        }(materializer.executionContext)
      }
    }

    pub.asInstanceOf[Publisher[Out]] → p
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Promise[Unit]] = new LazyEmptySource[Out](attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new LazyEmptySource(attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Elements are emitted periodically with the specified interval.
 * The tick element will be delivered to downstream consumers that has requested any elements.
 * If a consumer has not requested any elements at the point in time when the tick
 * element is produced it will not receive that tick element later. It will
 * receive new tick elements as soon as it has requested more elements.
 */
private[akka] final class TickSource[Out](initialDelay: FiniteDuration, interval: FiniteDuration, tick: Out, val attributes: OperationAttributes, shape: SourceShape[Out]) extends SourceModule[Out, Cancellable](shape) {

  override def create(materializer: ActorFlowMaterializerImpl, flowName: String) = {
    val cancelled = new AtomicBoolean(false)
    val ref =
      materializer.actorOf(TickPublisher.props(initialDelay, interval, tick, materializer.settings, cancelled),
        name = s"$flowName-0-tick")
    (ActorPublisher[Out](ref), new Cancellable {
      override def cancel(): Boolean = {
        if (!isCancelled) ref ! PoisonPill
        true
      }
      override def isCancelled: Boolean = cancelled.get()
    })
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Cancellable] = new TickSource[Out](initialDelay, interval, tick, attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new TickSource(initialDelay, interval, tick, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates and wraps an actor into [[org.reactivestreams.Publisher]] from the given `props`,
 * which should be [[akka.actor.Props]] for an [[akka.stream.actor.ActorPublisher]].
 */
private[akka] final class ActorPublisherSource[Out](props: Props, val attributes: OperationAttributes, shape: SourceShape[Out]) extends SourceModule[Out, ActorRef](shape) {

  override def create(materializer: ActorFlowMaterializerImpl, flowName: String) = {
    val publisherRef = materializer.actorOf(props, name = s"$flowName-0-actorPublisher")
    (akka.stream.actor.ActorPublisher[Out](publisherRef), publisherRef)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, ActorRef] = new ActorPublisherSource[Out](props, attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new ActorPublisherSource(props, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
private[akka] final class ActorRefSource[Out](val attributes: OperationAttributes, shape: SourceShape[Out]) extends SourceModule[Out, ActorRef](shape) {

  override def create(materializer: ActorFlowMaterializerImpl, flowName: String) = {
    val ref = materializer.actorOf(ActorRefSourceActor.props, name = s"$flowName-0-actorRef")
    (akka.stream.actor.ActorPublisher[Out](ref), ref)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, ActorRef] = new ActorRefSource[Out](attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new ActorRefSource(attr, amendShape(attr))
}

package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Failure
import com.twitter.util.{Future, Promise, Return, Throw, Try}

/**
 * A Stream represents a stream of Data frames, optionally
 * followed by Trailers.
 *
 * A Stream is not prescriptive as to how data is produced. However,
 * flow control semantics are built into the Stream--consumers MUST
 * release each data frame after it has processed its contents.
 *
 * Consumers SHOULD call `read()` until it fails (i.e. when the
 * stream is fully closed).
 *
 * If a consumer cancels a `read()` Future, the stream is reset.
 */
trait Stream {
  override def toString = s"Stream(isEmpty=$isEmpty, onEnd=${onEnd.poll})"

  def isEmpty: Boolean
  final def nonEmpty: Boolean = !isEmpty

  def read(): Future[Frame]

  /**
   * Satisfied when an end-of-stream frame has been read from this
   * stream.
   *
   * If the stream is reset prematurely, onEnd fails with a [[Reset]].
   */
  def onEnd: Future[Unit]
}

/**
 * A Stream of Frames
 */
object Stream {

  // TODO Create a dedicated Static stream type that indicates the
  // entire message is buffered (i.e. so that retries may be
  // performed).  This would require some form of buffering, ideally
  // in the dispatcher and server stream

  /**
   * In order to create a stream, we need a mechanism to write to it.
   */
  trait Writer {

    /**
     * Write an object to a Stream so that it may be read as a Frame
     * (i.e. onto an underlying transport). The returned future is not
     * satisfied until the frame is written and released.
     */
    def write(frame: Frame): Future[Unit]

    def reset(err: Reset): Unit
    def close(): Unit
  }

  private trait AsyncQueueReader extends Stream {
    protected[this] val frameQ: AsyncQueue[Frame]

    override def isEmpty = false

    private[this] val endP = new Promise[Unit]
    override def onEnd: Future[Unit] = endP

    private[this] val endOnReleaseIfEnd: Try[Frame] => Unit = {
      case Return(f) => if (f.isEnd) endP.become(f.onRelease)
      case Throw(e) => endP.updateIfEmpty(Throw(e)); ()
    }

    override def read(): Future[Frame] = {
      val f = frameQ.poll()
      f.respond(endOnReleaseIfEnd)
      failOnInterrupt(f, frameQ)
    }
  }

  private[this] def failOnInterrupt[T, Q](f: Future[T], q: AsyncQueue[Q]): Future[T] = {
    val p = new Promise[T]
    p.setInterruptHandler {
      case e =>
        q.fail(e, discard = true)
        f.raise(e)
    }
    f.proxyTo(p)
    p
  }

  private class AsyncQueueReaderWriter extends AsyncQueueReader with Writer {
    override protected[this] val frameQ = new AsyncQueue[Frame]

    override def write(f: Frame): Future[Unit] =
      if (frameQ.offer(f)) failOnInterrupt(f.onRelease, frameQ)
      else Future.exception(Reset.Closed)

    override def reset(err: Reset): Unit = frameQ.fail(err, discard = true)
    override def close(): Unit = frameQ.fail(Reset.NoError, discard = false)
  }

  def apply(q: AsyncQueue[Frame]): Stream =
    new AsyncQueueReader { override protected[this] val frameQ = q }

  def apply(): Stream with Writer =
    new AsyncQueueReaderWriter

  def const(buf: Buf): Stream = {
    val q = new AsyncQueue[Frame]
    q.offer(Frame.Data.eos(buf))
    apply(q)
  }

  def const(s: String): Stream =
    const(Buf.Utf8(s))

  def empty(q: AsyncQueue[Frame]): Stream =
    new AsyncQueueReader {
      override protected[this] val frameQ = q
      override def isEmpty = true
    }

  def empty(): Stream with Writer =
    new Stream with Writer {
      private[this] val frameQ = new AsyncQueue[Frame](1)
      override def isEmpty = true
      override def onEnd = Future.Unit
      override def read(): Future[Frame] = failOnInterrupt(frameQ.poll(), frameQ)
      override def write(f: Frame): Future[Unit] = {
        frameQ.fail(Reset.Closed, discard = true)
        Future.exception(Reset.Closed)
      }
      override def reset(err: Reset): Unit = frameQ.fail(err, discard = true)
      override def close(): Unit = frameQ.fail(Reset.NoError, discard = false)
    }

}

/**
 * A single item in a Stream.
 */
sealed trait Frame {

  /**
   * When `isEnd` is true, no further events will be returned on the
   * Stream.
   */
  def isEnd: Boolean

  def onRelease: Future[Unit]
  def release(): Future[Unit]
}

object Frame {
  /**
   * A frame containing aribtrary data.
   *
   * `release()` MUST be called so that the producer may manage flow control.
   */
  trait Data extends Frame {
    override def toString = s"Frame.Data(length=${buf.length}, isEnd=$isEnd)"
    def buf: Buf
  }

  object Data {

    def apply(buf0: Buf, eos: Boolean, release0: () => Future[Unit]): Data = new Data {
      def buf = buf0
      private[this] val releaseP = new Promise[Unit]
      def onRelease = releaseP
      def release() = {
        val f = release0()
        releaseP.become(f)
        f
      }
      def isEnd = eos
    }

    def apply(buf: Buf, eos: Boolean): Data =
      apply(buf, eos, () => Future.Unit)

    def apply(s: String, eos: Boolean, release: () => Future[Unit]): Data =
      apply(Buf.Utf8(s), eos, release)

    def apply(s: String, eos: Boolean): Data =
      apply(Buf.Utf8(s), eos)

    def eos(buf: Buf): Data = apply(buf, true)
    def eos(s: String): Data = apply(s, true)
  }

  /** A terminal Frame including headers. */
  trait Trailers extends Frame with Headers { headers =>
    override def toString = s"Frame.Trailers(${headers.toSeq})"
    final override def isEnd = true
  }

}

package rx.redis.serialization

import io.netty.buffer.{ByteBuf, ByteBufAllocator}
import io.reactivex.netty.channel.ContentTransformer

import scala.annotation.{implicitNotFound, tailrec}
import scala.collection.mutable.ArrayBuffer
import scala.language.experimental.macros


@implicitNotFound("No type class found for ${A}. You have to implement an rx.redis.api.Write[${A}] in order to send ${A} directly.")
trait Writes[A] {

  def write(value: A, allocator: ByteBufAllocator): ByteBuf

  lazy val contentTransformer: ContentTransformer[A] =
    new ContentTransformer[A] {
      def call(t1: A, t2: ByteBufAllocator): ByteBuf = write(t1, t2)
    }
}

object Writes {

  @inline def apply[A](implicit A: Writes[A]): Writes[A] = A

  def writes[A]: Writes[A] = macro Macros.writes[A]
  
  implicit object DirectStringWrites extends Writes[String] {
    def write(value: String, allocator: ByteBufAllocator): ByteBuf =
      Bytes.StringBytes.write(value, allocator)
  }

  private[serialization] def long2bytes(n: Long): Array[Byte] = {
    @tailrec
    def loop(l: Long, b: ArrayBuffer[Byte]): Array[Byte] = {
      if (l == 0) b.reverse.toArray
      else {
        b += (l % 10 + '0').toByte
        loop(l / 10, b)
      }
    }
    if (n == 0) Array((0 + '0').toByte)
    else loop(n, new ArrayBuffer[Byte](10))
  }

  private val ArrayMarker = '*'.toByte
  private val StringMarker = '$'.toByte
  private val Cr = '\r'.toByte
  private val Lf = '\n'.toByte

  /* implemented by macro generation */
  private[serialization] trait MWrites[A] extends Writes[A] {
    def nameHeader: Array[Byte]
    def sizeHint(value: A): Long
    def writeArgs(buf: ByteBuf, value: A): Unit

    protected def writeArg[B](buf: ByteBuf, value: B, B: Bytes[B]): Unit = {
      val contentBytes = B.write(value, buf.alloc())
      val contentLength = long2bytes(contentBytes.readableBytes())
      buf.
        writeByte(StringMarker).
        writeBytes(contentLength).
        writeByte(Cr).writeByte(Lf).
        writeBytes(contentBytes).
        writeByte(Cr).writeByte(Lf)
    }

    private def writeHeader(buf: ByteBuf, value: A): Unit = {
      buf.
        writeByte(ArrayMarker).
        writeBytes(long2bytes(sizeHint(value))).
        writeByte(Cr).writeByte(Lf).
        writeBytes(nameHeader)
    }

    final def write(value: A, allocator: ByteBufAllocator): ByteBuf = {
      val buf = allocator.buffer()
      writeHeader(buf, value)
      writeArgs(buf, value)
      buf
    }
  }
}

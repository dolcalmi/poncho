package UnixSocket

import scala.Byte.byte2int
import scala.concurrent.{Promise}
import scala.scalanative.unsafe._
import scala.scalanative.libc.stdlib
import scala.scalanative.libc.string
import scalanative.unsigned.UnsignedRichLong
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.LibUV._
import scala.scalanative.loop.LibUVConstants._

// stuff that is missing from the libuv interface exposed by scala-native-loop
@link("uv")
@extern
object LibUVMissing {
  type ConnectReq = Ptr[Byte]
  type ConnectCB = CFuncPtr2[ConnectReq, CInt, Unit]
  type PipeAllocCB = CFuncPtr3[PipeHandle, CSize, Ptr[Buffer], Unit]
  type PipeReadCB = CFuncPtr3[PipeHandle, CSSize, Ptr[Buffer], Unit]

  def uv_pipe_connect(
      uv_connect_t: ConnectReq,
      uv_pipe_t: PipeHandle,
      name: CString,
      cb: ConnectCB
  ): Unit = extern

  def uv_buf_init(base: CString, len: Int): Buffer = extern
  def uv_strerror(err: CInt): CString = extern
  def uv_read_start(
      handle: PipeHandle,
      alloc_cb: PipeAllocCB,
      read_cb: PipeReadCB
  ): CInt = extern
}

object UnixSocket {
  import LibUVMissing._

  val UV_CONNECT_REQUEST = 2
  val UV_EOF = -4095

  val pipe: PipeHandle =
    stdlib.malloc(uv_handle_size(UV_PIPE_T)).asInstanceOf[PipeHandle]
  val connect: ConnectReq =
    stdlib.malloc(uv_req_size(UV_CONNECT_REQUEST)).asInstanceOf[ConnectReq]
  val write = stdlib.malloc(uv_req_size(UV_WRITE_REQ_T)).asInstanceOf[WriteReq]
  var p = ""
  var result = ""
  val resultPromise = Promise[String]()

  def call(path: String, payload: String): Promise[String] = {
    p = payload // store this as a global like an animal

    // libuv magic
    uv_pipe_init(loop, pipe, 0)
    var pathC: CString = c""
    Zone { implicit z =>
      pathC = toCString(path)
    }

    // ask libuv: "hey we want to open a connection to this thing, please"
    uv_pipe_connect(
      connect,
      pipe,
      pathC,
      onConnect
    )

    // return this global promise like we're javascript programmers
    resultPromise
  }

  val onConnect: ConnectCB = (_: ConnectReq, status: CInt) =>
    status match {
      case 0 => {
        // we have connected successfully
        val buffer = stdlib.malloc(sizeof[Buffer]).asInstanceOf[Ptr[Buffer]]
        Zone { implicit z =>
          val temp_payload = toCString(p)
          val payload_len = string.strlen(temp_payload) + 1L.toULong
          buffer._1 = stdlib.malloc(payload_len)
          buffer._2 = payload_len
          string.strncpy(buffer._1, temp_payload, payload_len)
        }

        // ask libuv: "can you please let us write this payload into the pipe?"
        val r = uv_write(write, pipe, buffer, 1, onWrite)
        if (r != 0) {
          resultPromise.failure(
            new Exception(
              s"couldn't even try to write ($r): ${fromCString(uv_strerror(r))}"
            )
          )
          ()
        }
      }
      case _ =>
        // fail the promise
        resultPromise.failure(
          new Exception(
            s"failed to connect ($status): ${fromCString(uv_strerror(status))}"
          )
        )
        ()
    }

  val onWrite: WriteCB = (_: WriteReq, status: CInt) =>
    status match {
      case 0 =>
        // written successfully, now ask libuv: "now we want to read the response"
        uv_read_start(pipe, onAlloc, onRead)
        ()
      case _ =>
        // fail the promise
        resultPromise.failure(
          new Exception(
            s"failed to write ($status): ${fromCString(uv_strerror(status))}"
          )
        )
        ()
    }

  val onAlloc: PipeAllocCB =
    (_: PipeHandle, suggested_size: CSize, buf: Ptr[Buffer]) => {
      // this is called in a loop with an empty buffer, we must allocate some bytes for it
      buf._1 = stdlib.malloc(64L.toULong)
      buf._2 = 64L.toULong
    }

  val onRead: PipeReadCB = (_: PipeHandle, nread: CSSize, buf: Ptr[Buffer]) => {
    nread match {
      case UV_EOF => {
        // done reading
        uv_read_stop(pipe)
        uv_close(pipe, onClose)
      }
      case n if n > 0 => {
        // success reading
        val bytesRead: Ptr[Byte] = stdlib.malloc(nread.toULong + 1L.toULong)
        string.strncpy(bytesRead, buf._1, nread.toULong)
        !(bytesRead + nread - 1) = 0 // set a null byte at the end
        val part = fromCString(bytesRead)

        // append this part to the full payload we're storing globally like animals
        result += part

        if (!(buf._1 + buf._2 - 1L.toULong) == 0) {
          // there is a null byte at the end, we're done reading
          uv_read_stop(pipe)
          uv_close(pipe, onClose)
        } else if (nread.toULong != buf._2) {
          // less chars than the actual buffer size, we're done reading
          uv_read_stop(pipe)
          uv_close(pipe, onClose)
        }

        // otherwise there is more stuff to be read, we'll be called again
      }
      case 0 => {
        // this means the read is still happening, we'll be called again, do nothing
      }
      case n if n < 0 && n != UV_EOF => {
        // error reading
        resultPromise.failure(new Exception(s"failed to read ($nread)}"))
        uv_read_stop(pipe)
        uv_close(pipe, onClose)
      }
    }

    // free buffer if it was allocated
    if (buf._2 > 0L.toULong) {
      stdlib.free(buf._1)
    }
  }

  val onClose: CloseCB = (_: UVHandle) => {
    // after closing the pipe we free all the memory
    stdlib.free(pipe.asInstanceOf[Ptr[Byte]])
    stdlib.free(connect.asInstanceOf[Ptr[Byte]])
    stdlib.free(write.asInstanceOf[Ptr[Byte]])

    if (!resultPromise.isCompleted) {
      resultPromise.success(result)
    }
    ()
  }
}

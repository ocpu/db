package io.opencubes.sql

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.sql.Blob
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException

/**
 * A simple blob for when the driver does not ship one themselves.
 */
class SimpleBlob : Blob {
  private var bytes: ByteArray? = null
  private var closed = false
  private val onClose: ObservableOutputStream.() -> Unit = {
    val streamSize = size()

    if (streamSize < bytes?.size ?: 0)
      write(bytes!!, streamSize, (bytes?.size ?: 0) - streamSize)

    bytes = toByteArray()
  }

  /** @see Blob.setBytes */
  override fun setBytes(pos: Long, bytes: ByteArray): Int {
    checkClosed()
    return setBytes(pos, bytes, 0, bytes.size)
  }

  /** @see Blob.setBytes */
  override fun setBytes(pos: Long, bytes: ByteArray, offset: Int, len: Int): Int {
    checkClosed()

    val o = setBinaryStream(pos)
    o.write(bytes, offset, len)
    o.close()
    return len
  }

  /** @see Blob.length */
  override fun length(): Long = bytes?.size?.toLong() ?: 0L

  /** @see Blob.setBinaryStream */
  override fun setBinaryStream(pos: Long): OutputStream {
    checkClosed()
    checkPos(pos)

    val o = ObservableOutputStream()
    o.onClose(onClose)

    if (bytes != null)
      o.write(bytes!!, 0, (pos - 1).toInt())

    return o
  }

  /** @see Blob.free */
  override fun free() {
    bytes = null
    closed = true
  }

  /** @see Blob.position */
  override fun position(pattern: ByteArray?, start: Long): Long {
    throwNotSupported()
    return 0
  }

  /** @see Blob.position */
  override fun position(pattern: Blob?, start: Long): Long {
    throwNotSupported()
    return 0
  }

  /** @see Blob.getBytes */
  override fun getBytes(pos: Long, length: Int): ByteArray {
    checkClosed()
    checkPos(pos)
    val b = bytes ?: throw SQLException("No bytes available")
    if (length + pos - 1 > b.size) throw SQLException("No bytes available")
    return b.copyOfRange((pos - 1).toInt(), (length + pos - 1).toInt())
  }

  /** @see Blob.getBinaryStream */
  override fun getBinaryStream(): InputStream = ByteArrayInputStream(bytes)

  /** @see Blob.getBinaryStream */
  override fun getBinaryStream(pos: Long, length: Long): InputStream {
    checkClosed()
    checkPos(pos)
    val b = bytes ?: throw SQLException("No bytes available")
    if (length + pos - 1 > b.size) throw SQLException("No bytes available")
    return ByteArrayInputStream(bytes, (pos - 1).toInt(), length.toInt())
  }

  /** @see Blob.truncate */
  override fun truncate(len: Long) {
    checkClosed()
    val b = bytes ?: throw SQLException("No bytes available")
    if (len < 0) throw SQLException("Cannot truncate to less than 0 length")
    if (len > b.size) throw SQLException("Truncated size must be within the size of the BLOB")
    bytes = b.copyOf(len.toInt())
  }

  private fun checkClosed() {
    if (closed)
      throw SQLException("Cannot operate on closed BLOB")
  }

  private fun checkPos(pos: Long) {
    if (pos <= 0)
      throw SQLException("Position cannot be below 1")
  }

  private fun throwNotSupported() {
    throw SQLFeatureNotSupportedException("The feature you want to use is not supported")
  }

  /** A observable output stream that observes when it closes. */
  class ObservableOutputStream : ByteArrayOutputStream() {
    private var closeListener: (ObservableOutputStream.() -> Unit)? = null
    /**
     * Set a event handler for observing when it closes.
     */
    fun onClose(listener: ObservableOutputStream.() -> Unit) {
      closeListener = listener
    }

    /** @see InputStream.close */
    override fun close() {
      super.close()
      closeListener?.invoke(this)
    }
  }
}
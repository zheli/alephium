package org.alephium.protocol.model

import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey}
import org.alephium.protocol.config.GroupConfig

import scala.annotation.tailrec

class GroupIndex private (val value: Int) extends AnyVal {
  override def toString: String = s"GroupIndex($value)"

  @tailrec
  final def generateKey()(implicit config: GroupConfig): (ED25519PrivateKey, ED25519PublicKey) = {
    val (privateKey, publicKey) = ED25519.generatePriPub()
    if (GroupIndex.from(publicKey) == this) (privateKey, publicKey)
    else generateKey()
  }
}

object GroupIndex {
  def apply(value: Int)(implicit config: GroupConfig): GroupIndex = {
    require(validate(value))
    new GroupIndex(value)
  }

  def from(publicKey: ED25519PublicKey)(implicit config: GroupConfig): GroupIndex = {
    GroupIndex((publicKey.bytes.last & 0xFF) % config.groups)
  }

  def unsafe(value: Int): GroupIndex = new GroupIndex(value)
  def validate(group: Int)(implicit config: GroupConfig): Boolean =
    0 <= group && group < config.groups
}

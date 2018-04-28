package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import org.alephium.util.BaseActor

object TcpServer {
  def props(port: Int, peerManager: ActorRef): Props =
    Props(new TcpServer(port, peerManager))
}

class TcpServer(port: Int, peerManager: ActorRef) extends BaseActor {

  import context.system

  IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress("localhost", port))

  override def receive: Receive = binding

  def binding: Receive = {
    case Tcp.Bound(localAddress) =>
      logger.debug(s"Server binded to $localAddress")
      context.become(ready)
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      logger.debug(s"Bind failed")
      context stop self
  }

  def ready: Receive = {
    case c: Tcp.Connected =>
      peerManager.forward(c)
  }
}

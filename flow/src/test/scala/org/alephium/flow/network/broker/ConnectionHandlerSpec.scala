// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.io.Tcp
import akka.testkit.{SocketUtil, TestActorRef, TestProbe}

import org.alephium.flow.network.broker.ConnectionHandler.Ack
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.protocol.{SignatureSchema, WireVersion}
import org.alephium.protocol.message.{Header, Hello, Message, Ping, RequestId}
import org.alephium.protocol.model.{BrokerInfo, CliqueId}
import org.alephium.util.{AlephiumActorSpec, TimeStamp}

class ConnectionHandlerSpec extends AlephiumActorSpec with AlephiumConfigFixture {
  trait Fixture {
    val remoteAddress = SocketUtil.temporaryServerAddress()
    val connection    = TestProbe()
    val brokerHandler = TestProbe()

    val connectionHandler = TestActorRef[ConnectionHandler.CliqueConnectionHandler](
      ConnectionHandler.clique(remoteAddress, connection.ref, brokerHandler.ref)
    )
    connection.expectMsgType[Tcp.Register]
    connection.expectMsg(Tcp.ResumeReading)

    val message      = Ping(RequestId.unsafe(1), TimeStamp.now())
    val messageBytes = Message.serialize(message)
  }

  it should "publish misbehavior when receive invalid message" in new Fixture {
    val invalidVersion   = WireVersion(WireVersion.currentWireVersion.value + 1)
    val (priKey, pubKey) = SignatureSchema.secureGeneratePriPub()
    val brokerInfo =
      BrokerInfo.unsafe(CliqueId(pubKey), 0, 1, new InetSocketAddress("127.0.0.1", 0))
    val handshakeMessage =
      Message(Header(invalidVersion), Hello.unsafe(brokerInfo.interBrokerInfo, priKey))
    val handshakeMessageBytes = Message.serialize(handshakeMessage)

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    connectionHandler ! Tcp.Received(handshakeMessageBytes)
    listener.expectMsg(MisbehaviorManager.SerdeError(remoteAddress))
  }

  it should "read data from connection" in new Fixture {
    connectionHandler ! Tcp.Received(messageBytes)
    brokerHandler.expectMsg(BrokerHandler.Received(message))

    connectionHandler ! Tcp.Received(messageBytes ++ messageBytes)
    brokerHandler.expectMsg(BrokerHandler.Received(message))
    brokerHandler.expectMsg(BrokerHandler.Received(message))
  }

  it should "write data to connection" in new Fixture {
    connectionHandler ! ConnectionHandler.Send(messageBytes)
    connection.expectMsg(Tcp.Write(messageBytes, Ack(1)))

    connectionHandler ! ConnectionHandler.Send(messageBytes)
    connection.expectMsg(Tcp.Write(messageBytes, Ack(2)))
  }

  it should "buffer data when writing is failing" in new Fixture {
    connectionHandler ! Tcp.CommandFailed(Tcp.Write(messageBytes, Ack(-1)))
    connection.expectMsg(Tcp.ResumeWriting)
    connectionHandler.underlyingActor.outMessageBuffer.size is 1
    connectionHandler.underlyingActor.outMessageCount is 0

    connectionHandler ! Tcp.CommandFailed(Tcp.Write(messageBytes, Ack(0)))
    connection.expectMsg(Tcp.ResumeWriting)
    connectionHandler.underlyingActor.outMessageBuffer.size is 2
    connectionHandler.underlyingActor.outMessageCount is 0

    connectionHandler ! ConnectionHandler.Send(messageBytes)
    connection.expectNoMessage()
    connectionHandler.underlyingActor.outMessageBuffer.size is 3
    connectionHandler.underlyingActor.outMessageCount is 1

    connectionHandler ! Tcp.WritingResumed
    connection.expectMsg(Tcp.Write(messageBytes, Ack(-1)))
  }

  it should "close connection" in new Fixture {
    watch(connectionHandler)
    connectionHandler ! ConnectionHandler.CloseConnection
    connection.expectMsg(Tcp.Close)
    connectionHandler ! Tcp.Closed
    expectTerminated(connectionHandler)
  }
}

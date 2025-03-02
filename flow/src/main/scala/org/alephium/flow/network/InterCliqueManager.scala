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

package org.alephium.flow.network

import java.net.InetSocketAddress

import scala.util.Random

import akka.actor.Props
import akka.event.LoggingAdapter
import akka.io.Tcp
import akka.util.ByteString
import io.prometheus.client.Gauge

import org.alephium.flow.{Utils => FlowUtils}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.broker._
import org.alephium.flow.network.interclique.{InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.Hash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.util._

object InterCliqueManager {
  // scalastyle:off parameter.number
  def props(
      selfCliqueInfo: CliqueInfo,
      blockflow: BlockFlow,
      allHandlers: AllHandlers,
      discoveryServer: ActorRefT[DiscoveryServer.Command],
      blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
      numBootstrapNodes: Int
  )(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): Props =
    Props(
      new InterCliqueManager(
        selfCliqueInfo,
        blockflow,
        allHandlers,
        discoveryServer,
        blockFlowSynchronizer,
        numBootstrapNodes
      )
    )
  //scalastyle:on

  sealed trait Command                     extends CliqueManager.Command
  final case object GetSyncStatuses        extends Command
  final case object IsSynced               extends Command
  final case object UpdateNodeSyncedStatus extends Command
  final case class BroadCastBlock(
      block: Block,
      blockMsg: ByteString,
      origin: DataOrigin
  ) extends Command
      with EventStream.Event
  final case class BroadCastTx(hashes: AVector[(ChainIndex, AVector[Hash])])
      extends Command
      with EventStream.Event

  sealed trait Event
  final case class SyncedResult(isSynced: Boolean) extends Event with EventStream.Event
  final case class SyncStatus(
      peerId: PeerId,
      address: InetSocketAddress,
      isSynced: Boolean,
      groupNumPerBroker: Int
  ) extends Event

  final case class Unreachable(remote: InetSocketAddress) extends Event with EventStream.Event

  final case class BrokerState(
      info: BrokerInfo,
      connectionType: ConnectionType,
      actor: ActorRefT[BrokerHandler.Command],
      isSynced: Boolean
  ) {
    def setSynced(): BrokerState = this.copy(isSynced = true)

    def readyFor(chainIndex: ChainIndex): Boolean = {
      isSynced && info.contains(chainIndex.from)
    }
  }

  final case class PeerDisconnected(peer: InetSocketAddress)

  val peersTotal: Gauge = Gauge
    .build("alephium_peers_total", "Number of connected peers")
    .register()

  trait NodeSyncStatus extends BaseActor with EventStream.Subscriber {
    private var nodeSynced: Boolean      = false
    private var firstTimeSynced: Boolean = true

    protected def onFirstTimeSynced(): Unit = {}

    def updateNodeSyncStatus: Receive = {
      case InterCliqueManager.SyncedResult(isSynced) =>
        if (firstTimeSynced && isSynced) {
          firstTimeSynced = false
          onFirstTimeSynced()
        }
        nodeSynced = isSynced
      case InterCliqueManager.IsSynced =>
        sender() ! InterCliqueManager.SyncedResult(isNodeSynced)
    }

    def isNodeSynced: Boolean = nodeSynced
  }
}

class InterCliqueManager(
    selfCliqueInfo: CliqueInfo,
    blockflow: BlockFlow,
    allHandlers: AllHandlers,
    discoveryServer: ActorRefT[DiscoveryServer.Command],
    blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
    numBootstrapNodes: Int
)(implicit val brokerConfig: BrokerConfig, val networkSetting: NetworkSetting)
    extends BaseActor
    with EventStream.Subscriber
    with InterCliqueManagerState {
  import InterCliqueManager._

  def selfCliqueId: CliqueId = selfCliqueInfo.id

  override def preStart(): Unit = {
    super.preStart()
    updateNodeSyncedStatus()
    schedule(self, UpdateNodeSyncedStatus, networkSetting.updateSyncedFrequency)
    discoveryServer ! DiscoveryServer.SendCliqueInfo(selfCliqueInfo)
    subscribeEvent(self, classOf[DiscoveryServer.NewPeer])
    subscribeEvent(self, classOf[InterCliqueManager.BroadCastTx])
    subscribeEvent(self, classOf[InterCliqueManager.BroadCastBlock])
  }

  override def receive: Receive = handleMessage orElse handleConnection orElse handleNewClique

  def handleNewClique: Receive = { case DiscoveryServer.NewPeer(peerInfo) =>
    connect(peerInfo)
  }

  def handleConnection: Receive = {
    case Tcp.Connected(remoteAddress, _) =>
      if (checkForInConnection(networkSetting.maxInboundConnectionsPerGroup)) {
        log.info(s"Connected to $remoteAddress")
        val props =
          InboundBrokerHandler.props(
            selfCliqueInfo,
            remoteAddress,
            networkSetting.connectionBuild(sender()),
            blockflow,
            allHandlers,
            ActorRefT[CliqueManager.Command](self),
            blockFlowSynchronizer
          )
        val in = context.actorOf(props)
        context.watchWith(in, PeerDisconnected(remoteAddress))
        ()
      } else {
        sender() ! Tcp.Close
      }
    case CliqueManager.HandShaked(brokerInfo, connectionType) =>
      connecting.remove(brokerInfo.address)
      handleNewBroker(brokerInfo, connectionType)
    case CliqueManager.Synced(brokerInfo) =>
      log.debug(s"No new blocks from $brokerInfo")
      setSynced(brokerInfo)
    case _: Tcp.ConnectionClosed => ()
  }

  def handleMessage: Receive = {
    case message: InterCliqueManager.BroadCastBlock =>
      handleBroadCastBlock(message)

    case InterCliqueManager.BroadCastTx(hashes) =>
      log.debug(s"Broadcasting txs ${FlowUtils.showChainIndexedDigest(hashes)}")
      randomIterBrokers { (peerId, brokerState) =>
        if (brokerState.isSynced) {
          val needToSend = hashes.filter { case (chainIndex, txHashes) =>
            brokerState.info.contains(chainIndex.from) && txHashes.nonEmpty
          }
          if (needToSend.nonEmpty) {
            log.debug(
              s"Send tx announcements ${FlowUtils.showChainIndexedDigest(needToSend)} to broker $peerId"
            )
            brokerState.actor ! BrokerHandler.RelayTxs(needToSend)
          }
        }
      }

    case GetSyncStatuses =>
      val syncStatuses: Seq[SyncStatus] = mapBrokers { (peerId, brokerState) =>
        SyncStatus(
          peerId,
          brokerState.info.address,
          brokerState.isSynced,
          brokerConfig.remoteGroupNum(brokerState.info)
        )
      }
      sender() ! syncStatuses

    case IsSynced =>
      sender() ! SyncedResult(isSynced())

    case UpdateNodeSyncedStatus =>
      updateNodeSyncedStatus()

    case PeerDisconnected(peer) =>
      log.info(s"Peer disconnected: $peer")
      connecting.remove(peer)
      removeBroker(peer)
      if (needOutgoingConnections(networkSetting.maxOutboundConnectionsPerGroup)) {
        discoveryServer ! DiscoveryServer.GetNeighborPeers(Some(brokerConfig))
      }

    case DiscoveryServer.NeighborPeers(sortedPeers) =>
      extractPeersToConnect(sortedPeers, networkSetting.maxOutboundConnectionsPerGroup)
        .foreach(connectUnsafe)
  }

  def handleBroadCastBlock(message: InterCliqueManager.BroadCastBlock): Unit = {
    if (lastNodeSyncedStatus.getOrElse(false)) {
      val block = message.block
      log.debug(s"Broadcasting block ${block.shortHex} for ${block.chainIndex}")
      if (message.origin.isLocal) {
        randomIterBrokers { (peerId, brokerState) =>
          if (brokerState.readyFor(block.chainIndex)) {
            log.debug(s"Send block to broker $peerId")
            brokerState.actor ! BrokerHandler.Send(message.blockMsg)
          }
        }
      } else {
        randomIterBrokers { (peerId, brokerState) =>
          if (!message.origin.isFrom(brokerState.info) && brokerState.readyFor(block.chainIndex)) {
            log.debug(s"Send announcement to broker $peerId")
            brokerState.actor ! BrokerHandler.RelayBlock(block.hash)
          }
        }
      }
    }
  }

  def isSynced(): Boolean = {
    brokerConfig.groupRange.forall { group =>
      var relavantBrokers = 0
      var syncedCount     = 0
      brokers.foreach { broker =>
        if (broker._2.info.containsRaw(group)) {
          relavantBrokers += 1
          if (broker._2.isSynced) syncedCount += 1
        }
      }
      syncedCount >= (relavantBrokers + 1) / 2 && syncedCount >= (numBootstrapNodes + 1) / 2
    }
  }

  var lastNodeSyncedStatus: Option[Boolean] = None
  def updateNodeSyncedStatus(): Unit = {
    val nodeSyncStatus = isSynced()
    lastNodeSyncedStatus match {
      case None =>
        publishNodeStatus(SyncedResult(nodeSyncStatus))
      case Some(lastStatus) =>
        if (nodeSyncStatus != lastStatus) {
          publishNodeStatus(SyncedResult(nodeSyncStatus))
        } // else we don't do anything
    }
    lastNodeSyncedStatus = Some(nodeSyncStatus)
  }

  def publishNodeStatus(result: SyncedResult): Unit = {
    blockFlowSynchronizer.ref ! result
    allHandlers.viewHandler.ref ! result
    allHandlers.txHandler.ref ! result
    allHandlers.blockHandlers.foreach(_._2.ref ! result)
  }

  def connect(broker: BrokerInfo): Unit = {
    if (checkForOutConnection(broker, networkSetting.maxOutboundConnectionsPerGroup)) {
      connectUnsafe(broker)
    }
  }

  val connecting: Cache[InetSocketAddress, Unit] = Cache.fifo(
    networkSetting.maxOutboundConnectionsPerGroup * brokerConfig.groups
  )
  private def connectUnsafe(brokerInfo: BrokerInfo): Unit = {
    if (!connecting.contains(brokerInfo.address)) {
      log.info(s"Try to connect to $brokerInfo")
      val props =
        OutboundBrokerHandler.props(
          selfCliqueInfo,
          brokerInfo,
          blockflow,
          allHandlers,
          ActorRefT(self),
          blockFlowSynchronizer
        )
      val out = context.actorOf(props)
      connecting.put(brokerInfo.address, ())
      context.watchWith(out, PeerDisconnected(brokerInfo.address))
      ()
    }
  }
}

trait InterCliqueManagerState extends BaseActor with EventStream.Publisher {
  import InterCliqueManager._

  def selfCliqueId: CliqueId
  def networkSetting: NetworkSetting

  def log: LoggingAdapter
  implicit def brokerConfig: BrokerConfig

  // The key is (CliqueId, BrokerId)
  val brokers = collection.mutable.HashMap.empty[PeerId, BrokerState]

  def addBroker(
      brokerInfo: BrokerInfo,
      connectionType: ConnectionType,
      broker: ActorRefT[BrokerHandler.Command]
  ): Unit = {
    val peerId = brokerInfo.peerId
    if (!brokers.contains(peerId)) {
      log.debug(s"Start syncing with inter-clique node: $brokerInfo")
      brokers += peerId -> BrokerState(brokerInfo, connectionType, broker, isSynced = false)
      InterCliqueManager.peersTotal.set(brokers.size.toDouble)
    } else {
      log.debug(s"Ignore another connection from $peerId")
    }
  }

  def containsBroker(brokerInfo: BrokerInfo): Boolean = {
    brokers.contains(brokerInfo.peerId)
  }

  def iterBrokers(f: (PeerId, BrokerState) => Unit): Unit = {
    brokers.foreach { case (peerId, state) =>
      f(peerId, state)
    }
  }

  def mapBrokers[A](f: (PeerId, BrokerState) => A): Seq[A] = {
    brokers.collect { case (peerId, state) =>
      f(peerId, state)
    }.toSeq
  }

  def randomIterBrokers(f: (PeerId, BrokerState) => Unit): Unit = {
    val buf = brokers.toArray
    def swap(i1: Int, i2: Int): Unit = {
      val tmp = buf(i1)
      buf(i1) = buf(i2)
      buf(i2) = tmp
    }

    for (n <- buf.length to 2 by -1) {
      val k = Random.nextInt(n)
      swap(n - 1, k)
    }
    buf.foreach { case (peerId, state) => f(peerId, state) }
  }

  def setSynced(brokerInfo: BrokerInfo): Unit = {
    val peerId = brokerInfo.peerId
    brokers.get(peerId) match {
      case Some(state) => brokers(peerId) = state.setSynced()
      case None        => log.warning(s"Unexpected message Synced from $brokerInfo")
    }
  }

  def getOutConnectionPerGroup(groupIndex: GroupIndex): Int = {
    brokers.foldLeft(0) { case (count, (_, brokerState)) =>
      if (
        brokerState.connectionType == OutboundConnection &&
        brokerState.info.contains(groupIndex)
      ) {
        count + 1
      } else {
        count
      }
    }
  }

  def getInConnectionPerGroup(groupIndex: GroupIndex): Int = {
    brokers.foldLeft(0) { case (count, (_, brokerState)) =>
      if (
        brokerState.connectionType == InboundConnection &&
        brokerState.info.contains(groupIndex)
      ) {
        count + 1
      } else {
        count
      }
    }
  }

  def removeBroker(peer: InetSocketAddress): Unit = {
    brokers.find(_._2.info.address == peer).foreach { case (peerId, state) =>
      brokers.remove(peerId)
      if (state.connectionType == OutboundConnection) {
        publishEvent(Unreachable(peer))
      }
      InterCliqueManager.peersTotal.set(brokers.size.toDouble)
    }
  }

  def checkForInConnection(maxInboundConnectionsPerGroup: Int): Boolean = {
    brokerConfig.groupRange.exists { group =>
      getInConnectionPerGroup(GroupIndex.unsafe(group)) < maxInboundConnectionsPerGroup
    }
  }

  def checkForOutConnection(
      brokerInfo: BrokerInfo,
      maxOutboundConnectionsPerGroup: Int
  ): Boolean = {
    !containsBroker(brokerInfo) && {
      brokerConfig.calIntersection(brokerInfo).exists { group =>
        getOutConnectionPerGroup(GroupIndex.unsafe(group)) < maxOutboundConnectionsPerGroup
      }
    }
  }

  def handleNewBroker(brokerInfo: BrokerInfo, connectionType: ConnectionType): Unit = {
    val range = brokerConfig.calIntersection(brokerInfo)
    if (range.nonEmpty) {
      brokers.get(brokerInfo.peerId) match {
        case None =>
          handleNewConnection(brokerInfo, connectionType)
        case Some(existedBroker) =>
          handleDoubleConnection(brokerInfo, connectionType, existedBroker)
      }
    } else {
      log.warning(s"New peer connection with invalid group info: $brokerInfo")
      publishEvent(MisbehaviorManager.InvalidGroup(brokerInfo.address))
      context.stop(sender())
    }
  }

  def handleNewConnection(brokerInfo: BrokerInfo, connectionType: ConnectionType): Unit =
    connectionType match {
      case InboundConnection =>
        handleNewInConnection(brokerInfo, networkSetting.maxInboundConnectionsPerGroup)
      case OutboundConnection =>
        handleNewOutConnection(brokerInfo, networkSetting.maxOutboundConnectionsPerGroup)
    }

  def handleNewInConnection(
      brokerInfo: BrokerInfo,
      maxInboundConnectionsPerGroup: Int
  ): Unit = {
    val range = brokerConfig.calIntersection(brokerInfo)
    val available = range.exists { group =>
      getInConnectionPerGroup(GroupIndex.unsafe(group)) < maxInboundConnectionsPerGroup
    }
    if (available) {
      addBroker(brokerInfo, InboundConnection, ActorRefT(sender()))
    } else {
      log.warning(s"Too many inbound connections, ignore the one from $brokerInfo")
      context.stop(sender())
    }
  }

  def handleNewOutConnection(
      brokerInfo: BrokerInfo,
      maxOutboundConnectionsPerGroup: Int
  ): Unit = {
    if (needOutgoingConnections(brokerInfo, maxOutboundConnectionsPerGroup)) {
      addBroker(brokerInfo, OutboundConnection, ActorRefT(sender()))
    } else {
      log.warning(s"Too many outbound connections, ignore the one from $brokerInfo")
      context.stop(sender())
    }
  }

  def needOutgoingConnections(
      brokerInfo: BrokerInfo,
      maxOutboundConnectionsPerGroup: Int
  ): Boolean = {
    val range = brokerConfig.calIntersection(brokerInfo)
    needOutgoingConnections(range, maxOutboundConnectionsPerGroup)
  }

  def needOutgoingConnections(
      maxOutboundConnectionsPerGroup: Int
  ): Boolean = {
    needOutgoingConnections(brokerConfig.groupRange, maxOutboundConnectionsPerGroup)
  }

  private def needOutgoingConnections(
      range: Range,
      maxOutboundConnectionsPerGroup: Int
  ): Boolean = {
    range.exists { group =>
      getOutConnectionPerGroup(GroupIndex.unsafe(group)) < maxOutboundConnectionsPerGroup
    }
  }

  def extractPeersToConnect(
      sortedPeers: AVector[BrokerInfo],
      maxOutboundConnectionsPerGroup: Int
  ): AVector[BrokerInfo] = {
    val peerPerGroupCount = Array.fill[Int](brokerConfig.groups)(maxOutboundConnectionsPerGroup)
    brokerConfig.groupRange.foreach { group =>
      peerPerGroupCount(group) = getOutConnectionPerGroup(GroupIndex.unsafe(group))
    }
    sortedPeers.filter { brokerInfo =>
      val range = brokerConfig.calIntersection(brokerInfo)
      val ok = {
        (brokerInfo.peerId.cliqueId != selfCliqueId) &&
        !containsBroker(brokerInfo) &&
        range.exists(peerPerGroupCount(_) < maxOutboundConnectionsPerGroup)
      }
      if (ok) range.foreach(peerPerGroupCount(_) += 1)
      ok
    }
  }

  def handleDoubleConnection(
      brokerInfo: BrokerInfo,
      connectionType: ConnectionType,
      existedBroker: BrokerState
  ): Unit = {
    assume(brokerInfo.peerId == existedBroker.info.peerId)
    if (connectionType != existedBroker.connectionType) {
      if (
        (selfCliqueId < brokerInfo.cliqueId && existedBroker.connectionType == OutboundConnection) ||
        (selfCliqueId > brokerInfo.cliqueId && existedBroker.connectionType == InboundConnection)
      ) { // keep the existed connection
        log.debug(s"Ignore valid double connection")
        context.stop(sender())
      } else { // replace the existed connection
        log.debug(s"Replace the existed connection")
        brokers.remove(existedBroker.info.peerId)
        context.stop(existedBroker.actor.ref)
        addBroker(brokerInfo, connectionType, ActorRefT(sender()))
      }
    } else {
      log.debug(s"Invalid double connection from ${brokerInfo.peerId}")
      brokers.remove(existedBroker.info.peerId)
      context.stop(existedBroker.actor.ref)
      context.stop(sender())
    }
  }
}

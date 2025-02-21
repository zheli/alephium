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

package org.alephium.flow.core

import java.math.BigInteger

import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.IOResult
import org.alephium.protocol.{ALF, BlockHash}
import org.alephium.protocol.model.Target
import org.alephium.util.{AVector, Duration, TimeStamp}

trait ChainDifficultyAdjustment {
  implicit def consensusConfig: ConsensusSetting

  def getHeight(hash: BlockHash): IOResult[Int]

  def getTimestamp(hash: BlockHash): IOResult[TimeStamp]

  def chainBackUntil(hash: BlockHash, heightUntil: Int): IOResult[AVector[BlockHash]]

  // TODO: optimize this
  final protected[core] def calTimeSpan(hash: BlockHash, height: Int): IOResult[Duration] = {
    val earlyHeight = height - consensusConfig.powAveragingWindow - 1
    assume(earlyHeight >= ALF.GenesisHeight)
    for {
      hashes        <- chainBackUntil(hash, earlyHeight)
      timestampNow  <- getTimestamp(hash)
      timestampLast <- getTimestamp(hashes.head)
    } yield timestampNow.deltaUnsafe(timestampLast)
  }

  // DigiShield DAA V3 variant
  final protected[core] def calNextHashTargetRaw(
      hash: BlockHash,
      currentTarget: Target
  ): IOResult[Target] = {
    getHeight(hash).flatMap {
      case height if height >= ALF.GenesisHeight + consensusConfig.powAveragingWindow + 1 =>
        calTimeSpan(hash, height).map { timeSpan =>
          var clippedTimeSpan =
            consensusConfig.expectedWindowTimeSpan.millis + (timeSpan.millis - consensusConfig.expectedWindowTimeSpan.millis) / 4
          if (clippedTimeSpan < consensusConfig.windowTimeSpanMin.millis) {
            clippedTimeSpan = consensusConfig.windowTimeSpanMin.millis
          } else if (clippedTimeSpan > consensusConfig.windowTimeSpanMax.millis) {
            clippedTimeSpan = consensusConfig.windowTimeSpanMax.millis
          }
          reTarget(currentTarget, clippedTimeSpan)
        }
      case _ => Right(currentTarget)
    }
  }

  final protected def reTarget(currentTarget: Target, timeSpanMs: Long): Target = {
    val nextTarget = currentTarget.value
      .multiply(BigInteger.valueOf(timeSpanMs))
      .divide(BigInteger.valueOf(consensusConfig.expectedWindowTimeSpan.millis))
    if (nextTarget.compareTo(consensusConfig.maxMiningTarget.value) <= 0) {
      Target.unsafe(nextTarget)
    } else {
      consensusConfig.maxMiningTarget
    }
  }
}

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

package org.alephium.flow.mempool

import scala.util.Random

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model._
import org.alephium.util.{AVector, LockFixture, TimeStamp}

class MemPoolSpec
    extends AlephiumFlowSpec
    with TxIndexesSpec.Fixture
    with LockFixture
    with NoIndexModelGeneratorsLike {
  def now = TimeStamp.now()

  it should "initialize an empty pool" in {
    val pool = MemPool.empty(GroupIndex.unsafe(0))
    pool.size is 0
  }

  it should "contain/add/remove for transactions" in {
    forAll(blockGen) { block =>
      val txTemplates = block.transactions.map(_.toTemplate)
      val group =
        GroupIndex.unsafe(
          brokerConfig.groupFrom + Random.nextInt(brokerConfig.groupNumPerBroker)
        )
      val pool  = MemPool.empty(group)
      val index = block.chainIndex
      if (index.from.equals(group)) {
        txTemplates.foreach(pool.contains(index, _) is false)
        pool.addToTxPool(index, txTemplates, now) is block.transactions.length
        pool.size is block.transactions.length
        block.transactions.foreach(tx => checkTx(pool.txIndexes, tx.toTemplate))
        txTemplates.foreach(pool.contains(index, _) is true)
        pool.removeFromTxPool(index, txTemplates) is block.transactions.length
        pool.size is 0
        pool.txIndexes is TxIndexes.emptySharedPool
      } else {
        assertThrows[AssertionError](txTemplates.foreach(pool.contains(index, _)))
      }
    }
  }

  it should "calculate the size of mempool" in {
    val pool = MemPool.empty(GroupIndex.unsafe(0))
    val tx0  = transactionGen().sample.get.toTemplate
    pool.addNewTx(ChainIndex.unsafe(0, 0), tx0)
    pool.size is 1
    val tx1 = transactionGen().sample.get.toTemplate
    pool.pendingPool.add(tx1, now)
    pool.size is 2
  }

  trait Fixture {
    val pool   = MemPool.empty(GroupIndex.unsafe(0))
    val index0 = ChainIndex.unsafe(0, 0)
    val index1 = ChainIndex.unsafe(0, 1)
    val tx0    = transactionGen().retryUntil(_.chainIndex equals index0).sample.get.toTemplate
    val tx1    = transactionGen().retryUntil(_.chainIndex equals index1).sample.get.toTemplate
    pool.addNewTx(index0, tx0)
    pool.pendingPool.add(tx1, now)
  }

  it should "list transactions for a specific chain" in new Fixture {
    pool.getAll(index0) is AVector(tx0)
    pool.getAll(index1) is AVector(tx1)
  }

  it should "work for utxos" in new Fixture {
    tx0.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is true)
    tx1.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is true)
    pool.isDoubleSpending(index0, tx0) is true
    pool.isDoubleSpending(index0, tx1) is true
    tx0.assetOutputRefs.foreach(output => pool.isUnspentInPool(output) is true)
    tx1.assetOutputRefs.foreach(output => pool.isUnspentInPool(output) is true)
    tx0.assetOutputRefs.foreachWithIndex((output, index) =>
      pool.getUtxo(output) is Some(tx0.getOutput(index))
    )
    tx1.assetOutputRefs.foreachWithIndex((output, index) =>
      pool.getUtxo(output) is Some(tx1.getOutput(index))
    )
  }

  it should "work for sequential txs" in new Fixture {
    val blockFlow  = isolatedBlockFlow()
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block0     = transfer(blockFlow, chainIndex)
    val tx2        = block0.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, chainIndex)
    val tx3    = block1.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow, block1)

    pool.addNewTx(chainIndex, tx2)
    pool.pendingPool.add(tx3, now)
    val tx2Outputs = tx2.assetOutputRefs
    tx2Outputs.length is 2
    pool.isUnspentInPool(tx2Outputs.head) is true
    pool.isUnspentInPool(tx2Outputs.last) is false
    pool.isSpent(tx2Outputs.last)
    tx3.assetOutputRefs.foreach(output => pool.isUnspentInPool(output) is true)
  }

  it should "clean mempool" in {
    val blockFlow = isolatedBlockFlow()

    val pool   = MemPool.empty(GroupIndex.unsafe(0))
    val index0 = ChainIndex.unsafe(0, 0)
    val index1 = ChainIndex.unsafe(0, 1)
    val index2 = ChainIndex.unsafe(0, 2)
    val tx0    = transactionGen().retryUntil(_.chainIndex equals index0).sample.get.toTemplate
    val tx1    = transactionGen().retryUntil(_.chainIndex equals index1).sample.get.toTemplate
    val block2 = transfer(blockFlow, index2)
    val tx2    = block2.nonCoinbase.head.toTemplate
    val tx3 =
      tx2.copy(unsigned = tx2.unsigned.copy(inputs = tx2.unsigned.inputs ++ tx1.unsigned.inputs))

    blockFlow.recheckInputs(index2.from, AVector(tx2, tx3)) isE AVector(tx3)

    pool.addNewTx(index0, tx0)
    pool.addNewTx(index1, tx1)
    pool.addNewTx(index2, tx2)
    pool.addNewTx(index2, tx3)
    pool.size is 4
    pool.clean(blockFlow, TimeStamp.now().plusMinutesUnsafe(1))
    pool.size is 1
    pool.contains(index2, tx2) is true
  }
}

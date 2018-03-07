package org.tron.core.net.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.core.Sha256Hash;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.BlockStore;
import org.tron.core.db.DynamicPropertiesStore;
import org.tron.core.db.Manager;
import org.tron.core.net.message.BlockMessage;
import org.tron.core.net.message.Message;
import org.tron.core.net.message.MessageTypes;
import org.tron.core.net.message.TransactionMessage;

public class NodeDelegateImpl implements NodeDelegate {

  private static final Logger logger = LoggerFactory.getLogger("NodeDelegateImpl");

  private Manager dbManager;

  public NodeDelegateImpl(Manager dbManager) {
    this.dbManager = dbManager;
  }

  protected BlockStore getBlockStoreDb() {
    return dbManager.getBlockStore();
  }

  @Override
  public void handleBlock(BlockCapsule block) {
    dbManager.processBlock(block);
    getBlockStoreDb().pushBlock(block);
    DynamicPropertiesStore dynamicPropertiesStore = dbManager.getDynamicPropertiesStore();

    //dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(block.get);
    dynamicPropertiesStore.saveLatestBlockHeaderNumber(block.getNum());
    //dynamicPropertiesStore.saveLatestBlockHeaderHash(block.getHash());
  }


  @Override
  public void handleTransaction(TransactionCapsule trx) {
    logger.info("handle transaction");
    getBlockStoreDb().pushTransactions(trx);
  }

  @Override
  public List<BlockId> getLostBlockIds(List<BlockId> blockChainSummary) {
    //todo: return the blocks it should be have.

    List<BlockId> retBlockHashes = new ArrayList<>();
    Sha256Hash lastKnownBlkHash = Sha256Hash.ZERO_HASH;

    if (!blockChainSummary.isEmpty()) {
      //todo: find a block we all know between the summary and my db.
      Collections.reverse(blockChainSummary);
      for (Sha256Hash hash : blockChainSummary) {
        if (getBlockStoreDb().containBlock(hash)) {
          lastKnownBlkHash = hash;
          break;
        }
      }

      if (lastKnownBlkHash == Sha256Hash.ZERO_HASH) {
        //todo: can not find any same block form peer's summary and my db.
      }
    }

    for (long num = getBlockStoreDb().getBlockNumById(lastKnownBlkHash);
         num <= getBlockStoreDb().getHeadBlockNum(); ++num) {
      if (num > 0) {
        retBlockHashes.add(getBlockStoreDb().getBlockIdByNum(num));
      }
    }
    return retBlockHashes;
  }

  @Override
  public List<BlockId> getBlockChainSummary(BlockId beginBLockId, List<BlockId> blockIds)  {

    List<BlockId> retSummary = new ArrayList<>();
    long highBlkNum = 0;
    long highNoForkBlkNum;
    long lowBlkNum = 0; //TODO：get this from db.

    List<BlockId> forkList = new ArrayList<>();

    if (beginBLockId != Sha256Hash.ZERO_HASH) {
      //todo: get db's head num to check local db's block status.
      if (getBlockStoreDb().containBlock(beginBLockId)) {
        highBlkNum = beginBLockId.getNum();
        highNoForkBlkNum = highBlkNum;
      } else {
        forkList = getBlockStoreDb().getBlockChainHashesOnFork(beginBLockId);
        highNoForkBlkNum = getBlockStoreDb().getBlockNumById(forkList.get(forkList.size() - 1));
        forkList.remove(forkList.get(forkList.size() - 1));
        highBlkNum = highNoForkBlkNum + forkList.size();
      }

    } else {
      highBlkNum = getBlockStoreDb().getHeadBlockNum();
      highNoForkBlkNum = highBlkNum;
      if (highBlkNum == 0) {
        return retSummary;
      }
    }

    long realHighBlkNum = highBlkNum + blockIds.size();
    do {
      if (lowBlkNum <= highNoForkBlkNum) {
        retSummary.add(getBlockStoreDb().getBlockIdByNum(lowBlkNum));
      } else if (lowBlkNum <= highBlkNum) {
        retSummary.add(forkList.get((int) (lowBlkNum - highNoForkBlkNum - 1)));
      } else {
        retSummary.add(blockIds.get((int) (lowBlkNum - highBlkNum - 1)));
      }
      lowBlkNum += (realHighBlkNum - lowBlkNum + 2) / 2;
    } while (lowBlkNum <= realHighBlkNum);
    return retSummary;
  }


  @Override
  public Message getData(Sha256Hash hash, MessageTypes type) {
    switch (type) {
      case BLOCK:
        return new BlockMessage(getBlockStoreDb().findBlockByHash(hash));
      case TRX:
        return new TransactionMessage(dbManager.getTransactionStore().findTransactionByHash(hash.getBytes()));
      default:
        logger.info("message type not block or trx.");
        return null;
    }
  }

  @Override
  public void syncToCli() {

  }

  @Override
  public void getBlockNum(byte[] hash) {

  }

  @Override
  public void getBlockTime(byte[] hash) {

  }

  @Override
  public byte[] getHeadBlockId() {
    return new byte[0];
  }

  @Override
  public boolean contain(Sha256Hash hash, MessageTypes type) {
    if (type.equals(MessageTypes.BLOCK)) {
      return getBlockStoreDb().containBlock(hash);
    } else if (type.equals(MessageTypes.TRX)) {
      //TODO: check it
      return false;
    }
    return false;
  }

  @Override
  public BlockId getGenissBlock() {
    //TODO return a genissBlock
    return new BlockCapsule.BlockId(Sha256Hash.ZERO_HASH, 0);
  }
}

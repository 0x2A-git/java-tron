package org.tron.core.consensus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.backup.BackupManager;
import org.tron.common.backup.BackupManager.BackupStatusEnum;
import org.tron.consensus.Consensus;
import org.tron.consensus.base.BlockHandle;
import org.tron.consensus.base.Param.Miner;
import org.tron.consensus.base.State;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db.Manager;
import org.tron.core.net.TronNetService;
import org.tron.core.net.message.BlockMessage;
import org.tron.protos.Protocol.Block;

@Slf4j(topic = "consensus")
@Component
public class BlockHandleImpl implements BlockHandle {

  @Autowired
  private Manager manager;

  @Autowired
  private BackupManager backupManager;

  @Autowired
  private TronNetService tronNetService;

  @Autowired
  private Consensus consensus;

  @Override
  public State getState() {
    if (!backupManager.getStatus().equals(BackupStatusEnum.MASTER)) {
      return State.BACKUP_IS_NOT_MASTER;
    }
    return State.OK;
  }

  public Object getLock() {
    return manager;
  }

  public BlockCapsule produce(Miner miner, long blockTime, long timeout) {
    BlockCapsule blockCapsule = manager.generateBlock(miner, blockTime, timeout);
    consensus.receiveBlock(blockCapsule);
    try {
      BlockMessage blockMessage = new BlockMessage(blockCapsule);
      tronNetService.fastForward(blockMessage);
      manager.pushBlock(blockCapsule);
      tronNetService.broadcast(blockMessage);
    } catch (Exception e) {
      logger.error("Push block failed.", e);
      return null;
    }
    return blockCapsule;
  }
}
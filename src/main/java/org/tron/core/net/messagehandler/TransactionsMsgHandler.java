package org.tron.core.net.messagehandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.config.args.Args;
import org.tron.core.exception.P2pException;
import org.tron.core.exception.P2pException.TypeEnum;
import org.tron.core.net.TronProxy;
import org.tron.core.net.message.TransactionMessage;
import org.tron.core.net.message.TransactionsMessage;
import org.tron.core.net.message.TronMessage;
import org.tron.core.net.peer.Item;
import org.tron.core.net.peer.PeerAdv;
import org.tron.core.net.peer.PeerConnection;
import org.tron.protos.Protocol.Inventory.InventoryType;
import org.tron.protos.Protocol.ReasonCode;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;

@Slf4j
@Component
public class TransactionsMsgHandler implements TronMsgHandler {

  @Autowired
  private TronProxy tronProxy;

  @Autowired
  private PeerAdv peerAdv;

  private static int MAX_TRX_SIZE = 50_000;

  private static int MAX_SMART_CONTRACT_SUBMIT_SIZE = 100;

//  private static int TIME_OUT = 10 * 60 * 1000;

  private BlockingQueue<TrxEvent> smartContractQueue = new LinkedBlockingQueue(MAX_TRX_SIZE);

  private BlockingQueue<Runnable> queue = new LinkedBlockingQueue();

  private int threadNum = Args.getInstance().getValidateSignThreadNum();
  private ExecutorService trxHandlePool = new ThreadPoolExecutor(threadNum, threadNum, 0L,
      TimeUnit.MILLISECONDS, queue);

  private ScheduledExecutorService smartContractExecutor = Executors.newSingleThreadScheduledExecutor();

  class TrxEvent {
    @Getter
    private PeerConnection peer;
    @Getter
    private TransactionMessage msg;
    @Getter
    private long time;

    public TrxEvent(PeerConnection peer, TransactionMessage msg) {
      this.peer = peer;
      this.msg = msg;
      this.time = System.currentTimeMillis();
    }
  }

  public void init() {
    handleSmartContract();
  }

  public void close() {
    smartContractExecutor.shutdown();
    logger.info("TransactionsMsgHandler closed.");
  }

  public boolean isBusy() {
    return queue.size() + smartContractQueue.size() > MAX_TRX_SIZE;
  }

  @Override
  public void processMessage(PeerConnection peer, TronMessage msg) throws Exception {
    TransactionsMessage transactionsMessage = (TransactionsMessage) msg;
    check (peer, transactionsMessage);
    for (Transaction trx : transactionsMessage.getTransactions().getTransactionsList()) {
      int type = trx.getRawData().getContract(0).getType().getNumber();
      if (type == ContractType.TriggerSmartContract_VALUE || type == ContractType.CreateSmartContract_VALUE) {
        if (!smartContractQueue.offer(new TrxEvent(peer, new TransactionMessage(trx)))) {
          logger.warn("Add smart contract failed, queueSize {}:{}", smartContractQueue.size(), queue.size());
        }
      } else {
        trxHandlePool.submit(() -> handleTransaction(peer, new TransactionMessage(trx)));
      }
    }
  }

  private void check(PeerConnection peer, TransactionsMessage msg) throws Exception {
    for (Transaction trx : msg.getTransactions().getTransactionsList()) {
      Item item = new Item(new TransactionMessage(trx).getMessageId(), InventoryType.TRX);
      if (!peer.getAdvInvRequest().containsKey(item)) {
        throw new P2pException(TypeEnum.BAD_MESSAGE, "trx: " + msg.getMessageId() + " without request.");
      }
      peer.getAdvInvRequest().remove(item);
    }
  }

  private void handleSmartContract() {
    smartContractExecutor.scheduleWithFixedDelay(() -> {
      try {
        while (queue.size() < MAX_SMART_CONTRACT_SUBMIT_SIZE) {
          TrxEvent event = smartContractQueue.take();
//          if (System.currentTimeMillis() - event.getTime() > TIME_OUT) {
//            logger.warn("Drop smart contract {} from peer {}.");
//            continue;
//          }
          trxHandlePool.submit(() -> handleTransaction(event.getPeer(), event.getMsg()));
        }
      } catch (Exception e) {
        logger.error("Handle smart contract exception", e);
      }
    }, 1000, 20, TimeUnit.MILLISECONDS);
  }

  private void handleTransaction (PeerConnection peer, TransactionMessage trx) {
    if (peer.isDisconnect()) {
      logger.warn("Peer {} is disconnect, drop trx {}", peer.getInetAddress(), trx.getMessageId());
      return;
    }
    try {
      tronProxy.pushTransaction(trx.getTransactionCapsule());
      peerAdv.broadcast(trx);
    }catch (P2pException e) {
      logger.warn("Trx {} from peer {} process failed. type: {}, reason: {}",
          trx.getMessageId(), peer.getInetAddress(), e.getType(), e.getMessage());
      if (e.getType().equals(TypeEnum.BAD_TRX)) {
        peer.disconnect(ReasonCode.BAD_TX);
      }
    }catch (Exception e) {
      logger.warn("Trx {} from peer {} process failed.", trx.getMessageId(), peer.getInetAddress(), e);
    }
  }
}
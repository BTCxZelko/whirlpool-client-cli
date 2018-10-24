package com.samourai.whirlpool.client.run;

import com.samourai.api.SamouraiApi;
import com.samourai.api.beans.MultiAddrResponse;
import com.samourai.api.beans.UnspentResponse;
import com.samourai.rpc.client.RpcClientService;
import com.samourai.wallet.hd.HD_Account;
import com.samourai.wallet.hd.HD_Address;
import com.samourai.wallet.hd.HD_Chain;
import com.samourai.whirlpool.client.CliUtils;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.tx0.Tx0;
import com.samourai.whirlpool.client.tx0.Tx0Service;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionOutPoint;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RunTx0VPub {
  private Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private NetworkParameters params;
  private SamouraiApi samouraiApi;
  private Optional<RpcClientService> rpcClientService;

  private static final String XPUB_SAMOURAI_FEES =
      "vpub5YS8pQgZKVbrSn9wtrmydDWmWMjHrxL2mBCZ81BDp7Z2QyCgTLZCrnBprufuoUJaQu1ZeiRvUkvdQTNqV6hS96WbbVZgweFxYR1RXYkBcKt";
  private static final long SAMOURAI_FEES = 10000; // TODO
  private static final long TX_MIX_BYTES_INITIAL = 200;
  private static final long TX_MIX_BYTES_PER_CLIENT = 50;

  public RunTx0VPub(
      NetworkParameters params,
      SamouraiApi samouraiApi,
      Optional<RpcClientService> rpcClientService) {
    this.params = params;
    this.samouraiApi = samouraiApi;
    this.rpcClientService = rpcClientService;
  }

  public Tx0 runTx0(Pool pool, VpubWallet vpubWallet, int nbOutputs) throws Exception {
    List<UnspentResponse.UnspentOutput> utxos = vpubWallet.fetchUtxos(samouraiApi);
    if (!utxos.isEmpty()) {
      log.info("Found " + utxos.size() + " utxo from premix:");
      CliUtils.printUtxos(utxos);
    } else {
      throw new NotifiableException("No utxo found from VPub.");
    }
    return runTx0(utxos, vpubWallet, pool, nbOutputs);
  }

  private long computeDestinationValue(Pool pool) throws Exception {
    int feeSatPerByte = samouraiApi.fetchFees();
    long tx0MinerFeePerMustmix =
        (TX_MIX_BYTES_INITIAL + TX_MIX_BYTES_PER_CLIENT * pool.getMixAnonymitySet())
            * feeSatPerByte;
    tx0MinerFeePerMustmix = Math.min(tx0MinerFeePerMustmix, pool.getMinerFeeMax());
    if (log.isDebugEnabled()) {
      log.debug(
          "tx0MinerFeePerMustmix="
              + tx0MinerFeePerMustmix
              + "sat ("
              + feeSatPerByte
              + "/b * "
              + TX_MIX_BYTES_PER_CLIENT
              + ")");
    }
    return WhirlpoolProtocol.computeInputBalanceMin(
        pool.getDenomination(), false, tx0MinerFeePerMustmix);
  }

  public Tx0 runTx0(
      List<UnspentResponse.UnspentOutput> utxos, VpubWallet vpubWallet, Pool pool, int nbOutputs)
      throws Exception {
    // fetch spend address info
    log.info(" • Fetching addresses for VPub...");
    MultiAddrResponse.Address address = vpubWallet.fetchAddress(samouraiApi);

    long destinationValue = computeDestinationValue(pool);

    // find utxo to spend Tx0 from
    long spendFromBalanceMin = nbOutputs * (destinationValue + SAMOURAI_FEES);
    List<UnspentResponse.UnspentOutput> tx0SpendFroms =
        utxos
            .stream()
            .filter(utxo -> utxo.value >= spendFromBalanceMin)
            .collect(Collectors.toList());

    if (tx0SpendFroms.size() > 0) {
      log.info("Found " + tx0SpendFroms.size() + " utxos to use as Tx0 input");
      CliUtils.printUtxos(tx0SpendFroms);

      UnspentResponse.UnspentOutput tx0SpendFrom = tx0SpendFroms.get(0);
      Tx0 tx0 = runTx0(tx0SpendFrom, address, vpubWallet, destinationValue, nbOutputs);
      return tx0;
    } else {
      throw new Exception("ERROR: No utxo available to spend Tx0 from");
    }
  }

  private Tx0 runTx0(
      UnspentResponse.UnspentOutput spendFrom,
      MultiAddrResponse.Address address,
      VpubWallet vpubWallet,
      long destinationValue,
      int nbOutputs)
      throws Exception {
    /*
     * SPEND FROM: BIP84[ACCOUNT_0][CHAIN_DEPOSIT][spendFrom.xpub.address]
     */
    // utxo
    TransactionOutPoint spendFromOutpoint = spendFrom.computeOutpoint(params);

    // key
    HD_Account depositAccount =
        vpubWallet.getBip84w().getAccountAt(RunVPubLoop.ACCOUNT_DEPOSIT_AND_PREMIX);
    HD_Address depositAddress =
        depositAccount
            .getChain(RunVPubLoop.CHAIN_DEPOSIT_AND_PREMIX)
            .getAddressAt(spendFrom.computePathAddressIndex());

    // change
    HD_Address changeAddress =
        depositAccount
            .getChain(RunVPubLoop.CHAIN_DEPOSIT_AND_PREMIX)
            .getAddressAt(address.change_index);
    address.change_index++;

    // destination
    HD_Chain destinationChain = depositAccount.getChain(RunVPubLoop.CHAIN_DEPOSIT_AND_PREMIX);
    int destinationIndex = address.change_index;

    // run tx0
    int feeSatPerByte = samouraiApi.fetchFees();
    Tx0 tx0 =
        new Tx0Service(params)
            .tx0(
                depositAddress,
                spendFromOutpoint,
                nbOutputs,
                destinationChain,
                destinationValue,
                destinationIndex,
                changeAddress,
                feeSatPerByte,
                XPUB_SAMOURAI_FEES,
                SAMOURAI_FEES);

    log.info("Tx0:");
    log.info(tx0.getTx().toString());

    // broadcast
    log.info(" • Broadcasting Tx0...");
    if (rpcClientService.isPresent()) {
      rpcClientService.get().broadcastTransaction(tx0.getTx());
    } else {
      final String hexTx = new String(Hex.encode(tx0.getTx().bitcoinSerialize()));
      String message =
          "A new tx0 is ready. Please broadcast the following transaction and restart the script: "
              + hexTx;
      throw new NotifiableException(message);
    }
    return tx0;
  }
}
/*
 * Copyright 2020 Blockchain Technology Partners.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.mainnet.precompiles.daml;

import com.blockchaintp.besu.daml.protobuf.DamlLogEvent;
import com.blockchaintp.besu.daml.protobuf.DamlOperation;
import com.blockchaintp.besu.daml.protobuf.DamlTransaction;
import com.daml.ledger.participant.state.kvutils.Conversions;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlLogEntry;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlLogEntryId;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlStateKey;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlStateKey.KeyCase;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlStateValue;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlSubmission;
import com.daml.ledger.participant.state.kvutils.KeyValueCommitting;
import com.daml.ledger.participant.state.kvutils.KeyValueSubmission;
import com.daml.ledger.participant.state.v1.Configuration;
import com.daml.ledger.participant.state.v1.TimeModel;
import com.digitalasset.daml.lf.data.Time.Timestamp;
import com.digitalasset.daml.lf.engine.Engine;
import com.google.common.collect.Lists;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.Timestamps;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Gas;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.Log;
import org.hyperledger.besu.ethereum.core.LogTopic;
import org.hyperledger.besu.ethereum.core.MutableAccount;
import org.hyperledger.besu.ethereum.core.WorldUpdater;
import org.hyperledger.besu.ethereum.mainnet.AbstractPrecompiledContract;
import org.hyperledger.besu.ethereum.vm.GasCalculator;
import org.hyperledger.besu.ethereum.vm.MessageFrame;
import scala.Option;
import scala.Tuple2;

public class DamlPublicPrecompiledContract extends AbstractPrecompiledContract {
  private static final Logger LOG = LogManager.getLogger();

  private static final String DAML_PUBLIC = "DamlPublic";

  private static final LogTopic DAML_LOG_TOPIC = LogTopic.create(
      Namespace.getHash("daml/log-event".getBytes(Charset.defaultCharset())));

  private static final int DEFAULT_MAX_TTL = 80;        // 4x TimeKeeper period
  private static final int DEFAULT_MAX_CLOCK_SKEW = 40; // 2x TimeKeeper period

  private final Committer committer;

  public DamlPublicPrecompiledContract(final GasCalculator gasCalculator) {
    super(DAML_PUBLIC, gasCalculator);

    committer = new DamlCommitter(new Engine());
  }

  @Override
  public Gas gasRequirement(final Bytes input) {
    return Gas.of(0L);
  }

  @Override
  public Bytes compute(final Bytes input, final MessageFrame messageFrame) {
    final WorldUpdater updater = messageFrame.getWorldState();
    final MutableAccount account =
        updater.getOrCreate(Address.DAML_PUBLIC).getMutable();

    final LedgerState ledgerState = new DamlLedgerState(account);
    try {
      DamlOperation operation = DamlOperation.parseFrom(input.toArray());
      if (operation.hasTransaction()) {
        DamlTransaction tx = operation.getTransaction();

        DamlSubmission submission =
            KeyValueSubmission.unpackDamlSubmission(tx.getSubmission());
        String participantId = operation.getSubmittingParticipant();
        DamlLogEntryId entryId =
            KeyValueCommitting.unpackDamlLogEntryId(tx.getLogEntryId());

        DamlLogEntry logEntry =
            processTransaction(ledgerState, submission, participantId, entryId);

        DamlLogEvent logEvent =
            DamlLogEvent.newBuilder()
                .setLogEntry(KeyValueCommitting.packDamlLogEntry(logEntry))
                .setLogEntryId(tx.getLogEntryId())
                .build();
        String logHash =
            Hash.hash(Bytes.of(logEvent.toByteArray())).toHexString();
        LOG.info(String.format("Recording log entry under topic %s hash=%s",
                               DAML_LOG_TOPIC.toHexString(), logHash));
        messageFrame.addLog(new Log(Address.DAML_PUBLIC,
                                    Bytes.of(logEvent.toByteArray()),
                                    Lists.newArrayList(DAML_LOG_TOPIC)));
        for (Entry<UInt256, UInt256> e :
             account.getUpdatedStorage().entrySet()) {
          messageFrame.storageWasUpdated(e.getKey(), e.getValue().toBytes());
        }
        return Bytes.of(logEvent.toByteArray());
      } else {
        LOG.debug("DamlOperation DOES NOT contain a transaction, ignoring ...");
      }
    } catch (InvalidTransactionException e) {
      // exception called and consumed
    } catch (final InvalidProtocolBufferException ipbe) {
      Exception e = new RuntimeException(
          String.format(
              "Payload is unparseable and not a valid DamlSubmission %s",
              ipbe.getMessage().getBytes(Charset.defaultCharset())),
          ipbe);
      LOG.error("Failed to parse DamlSubmission protocol buffer:", e);
    }

    return Bytes.EMPTY;
  }

  private DamlLogEntry processTransaction(final LedgerState ledgerState,
                                          final DamlSubmission submission,
                                          final String participantId,
                                          final DamlLogEntryId entryId)
      throws InternalError, InvalidTransactionException {

    long fetchStateStart = System.currentTimeMillis();
    Map<DamlStateKey, Option<DamlStateValue>> stateMap =
        buildStateMap(ledgerState, submission);

    if (stateMap.isEmpty()) {
      LOG.debug("No ledger states for submission");
    } else {
      stateMap.forEach(
          (k, v) -> LOG.debug(String.format("  State %s=%s", k, v)));
    }

    long recordStateStart = System.currentTimeMillis();
    DamlLogEntry logEntry =
        recordState(ledgerState, submission, participantId, stateMap, entryId);
    long processFinished = System.currentTimeMillis();

    long recordStateTime = processFinished - recordStateStart;
    long fetchStateTime = recordStateStart - fetchStateStart;
    LOG.info(String.format(
        "Finished processing transaction, times=[fetch=%s,record=%s]",
        fetchStateTime, recordStateTime));

    return logEntry;
  }

  private Map<DamlStateKey, Option<DamlStateValue>>
  buildStateMap(final LedgerState ledgerState, final DamlSubmission submission)
      throws InvalidTransactionException, InternalError {

    LOG.debug(String.format("Fetching DamlState for this transaction"));
    List<DamlStateKey> inputDamlStateKeys = submission.getInputDamlStateList();

    LOG.debug(String.format("Fetching DAML state values for this submission"));
    Map<DamlStateKey, DamlStateValue> inputStates =
        ledgerState.getDamlStates(inputDamlStateKeys);
    if (inputStates.isEmpty()) {
      LOG.debug("No DAML state values for this submission");
    } else {
      inputStates.forEach(
          (k,
           v) -> LOG.debug(String.format("state key=[%s], value=[%s]", k, v)));
    }

    Map<DamlStateKey, Option<DamlStateValue>> inputStatesWithOption =
        new HashMap<>();
    inputDamlStateKeys.forEach(key -> {
      KeyCase keyCase = key.getKeyCase();
      UInt256 address = Namespace.makeDamlStateKeyAddress(key);
      DamlStateValue keyValue = inputStates.get(key);
      if (keyValue != null) {
        Option<DamlStateValue> option = Option.apply(keyValue);
        int keySize = keyValue.toByteString().size();
        if (keySize == 0) {
          LOG.debug(
              String.format("Fetched key=[%s] (%s), address=[%s], size=empty",
                            key, keyCase, address.toHexString()));
        } else {
          LOG.debug(
              String.format("Fetched key=[%s] (%s), address=[%s], size=%d", key,
                            keyCase, address.toHexString(), keySize));
        }
        inputStatesWithOption.put(key, option);
      } else {
        LOG.debug(String.format(
            "Fetched key=[%s] (%s), address=[%s], size=empty (not found in input states)",
            key, keyCase, address.toHexString()));
        inputStatesWithOption.put(key, Option.empty());
      }
    });
    return inputStatesWithOption;
  }

  private DamlLogEntry
  recordState(final LedgerState ledgerState, final DamlSubmission submission,
              final String participantId,
              final Map<DamlStateKey, Option<DamlStateValue>> stateMap,
              final DamlLogEntryId entryId)
      throws InternalError, InvalidTransactionException {

    long processStart = System.currentTimeMillis();
    String ledgerEffectiveTime = null;
    String maxRecordTime = null;
    if (submission.hasTransactionEntry()) {
      ledgerEffectiveTime =
          Conversions
              .parseTimestamp(
                  submission.getTransactionEntry().getLedgerEffectiveTime())
              .toString();
      maxRecordTime = Conversions
                          .parseTimestamp(submission.getTransactionEntry()
                                              .getSubmitterInfo()
                                              .getMaximumRecordTime())
                          .toString();
    }
    LOG.info(String.format(
        "Processing submission, recordTime=%s, ledgerEffectiveTime=%s, maxRecordTime=%s",
        getRecordTime(ledgerState), ledgerEffectiveTime, maxRecordTime));
    Tuple2<DamlLogEntry, Map<DamlStateKey, DamlStateValue>>
        processedSubmission =
            committer.processSubmission(getDefaultConfiguration(), entryId,
                                        getRecordTime(ledgerState), submission,
                                        participantId, stateMap);

    long recordStart = System.currentTimeMillis();
    DamlLogEntry newLogEntry = processedSubmission._1;
    Map<DamlStateKey, DamlStateValue> newState = processedSubmission._2;
    ledgerState.setDamlStates(newState.entrySet());

    LOG.debug(String.format("Recording log at %s, size=%d", entryId,
                            newLogEntry.toByteString().size()));
    ledgerState.addDamlLogEntry(entryId, newLogEntry);

    long recordFinish = System.currentTimeMillis();
    long processTime = recordStart - processStart;
    long setStateTime = recordFinish - recordStart;
    long totalTime = recordFinish - processStart;
    LOG.info(String.format(
        "Record state timings [ total=%s, process=%s, setState=%s ]", totalTime,
        processTime, setStateTime));

    return newLogEntry;
  }

  private Timestamp getRecordTime(final LedgerState ledgerState)
      throws InternalError {
    com.google.protobuf.Timestamp recordTime = ledgerState.getRecordTime();
    long micros = Timestamps.toMicros(recordTime);
    return new Timestamp(micros);
  }

  private Configuration getDefaultConfiguration()
      throws InternalError, InvalidTransactionException {
    TimeModel tm = new TimeModel(Duration.ofSeconds(1),
                                 Duration.ofSeconds(DEFAULT_MAX_CLOCK_SKEW),
                                 Duration.ofSeconds(DEFAULT_MAX_TTL));
    LOG.debug(String.format("Default TimeModel set to %s", tm));
    Configuration blankConfiguration = new Configuration(0, tm);
    return blankConfiguration;
  }
}

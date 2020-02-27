/*
 * Copyright 2020 Blockchain Technology Partners.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.mainnet.precompiles.daml;

import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Gas;
import org.hyperledger.besu.ethereum.core.Log;
import org.hyperledger.besu.ethereum.core.MutableAccount;
import org.hyperledger.besu.ethereum.core.WorldUpdater;
import org.hyperledger.besu.ethereum.mainnet.AbstractPrecompiledContract;
import org.hyperledger.besu.ethereum.vm.Code;
import org.hyperledger.besu.ethereum.vm.GasCalculator;
import org.hyperledger.besu.ethereum.vm.MessageFrame;
import org.hyperledger.besu.ethereum.vm.MessageFrame.Type;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.Timestamps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import scala.Option;
import scala.Tuple2;

public class DamlPublicPrecompiledContract extends AbstractPrecompiledContract {
  private static final Logger LOG = LogManager.getLogger();

  private static final String DAML_PUBLIC = "DamlPublic";

  private static final int DEFAULT_MAX_TTL = 80; // 4x TimeKeeper period
  private static final int DEFAULT_MAX_CLOCK_SKEW = 40; // 2x TimeKeeper period

  private final Committer committer;

  public DamlPublicPrecompiledContract(final GasCalculator gasCalculator) {
    super(DAML_PUBLIC, gasCalculator);

    committer = new DamlCommitter(new Engine());
  }

  @Override
  public Gas gasRequirement(final Bytes input) {
    LOG.trace(
        String.format(
            "In gasRequirement(input=%s) %s",
            input.toHexString(), stackTrace(Thread.currentThread().getStackTrace())));
    return Gas.ZERO;
  }

  @Override
  @SuppressWarnings("unused")
  public Bytes compute(final Bytes input, final MessageFrame messageFrame) {
    final WorldUpdater updater = messageFrame.getWorldState();
    final MutableAccount account = updater.getAccount(Address.DAML_PUBLIC).getMutable();
    final LedgerState ledgerState = new DamlLedgerState(account);
    final Blockchain blockchain = messageFrame.getBlockchain();
    final Code code = messageFrame.getCode();
    final List<Log> logs = messageFrame.getLogs();
    final Type type = messageFrame.getType();

    LOG.trace(
        String.format(
            "In compute(input=%s, target-account=%s, type=%s) %s",
            input.toHexString(),
            account,
            type,
            stackTrace(Thread.currentThread().getStackTrace())));
    try {
      byte[] data = Base64.getDecoder().decode(String.valueOf(input));
      DamlOperation operation = DamlOperation.parseFrom(data);
      if (operation.hasTransaction()) {
        DamlTransaction tx = operation.getTransaction();
        DamlSubmission submission = KeyValueSubmission.unpackDamlSubmission(tx.getSubmission());
        DamlLogEntryId entryId = KeyValueCommitting.unpackDamlLogEntryId(tx.getLogEntryId());

        LOG.debug(
            String.format(
                "Parsed DamlOperation protobuf %s [%s] from input [%s]",
                JsonFormat.printer().print(operation),
                Bytes.of(operation.toByteArray()).toHexString(),
                input.toHexString()));
        String participantId = operation.getSubmittingParticipant();
        LOG.debug(String.format("Participant id=[%s]", participantId));
        LOG.debug(
            String.format(
                "Parsed DamlTransaction protobuf %s [%s]",
                JsonFormat.printer().print(tx), Bytes.of(tx.toByteArray()).toHexString()));
        LOG.debug(
            String.format(
                "Parsed DamlLogEntryId protobuf %s [%s]",
                JsonFormat.printer().print(entryId),
                Bytes.of(entryId.toByteArray()).toHexString()));
        LOG.debug(
            String.format(
                "Parsed DamlSubmission protobuf %s [%s]",
                JsonFormat.printer().print(submission),
                Bytes.of(submission.toByteArray()).toHexString()));
        processTransaction(ledgerState, submission, participantId, entryId, updater);
      } else {
        LOG.debug("DamlOperation DOES NOT contain a transaction, ignoring ...");
      }
    } catch (InvalidTransactionException e) {
      // exception called and consumed
    } catch (final InvalidProtocolBufferException ipbe) {
      Exception e =
          new RuntimeException(
              String.format(
                  "Payload is unparseable and not a valid DamlSubmission %s",
                  ipbe.getMessage().getBytes(Charset.defaultCharset())),
              ipbe);
      LOG.error("Failed to parse DamlSubmission protocol buffer:", e);
    }

    // TODO return bytes representation of entire log entry (not the log entry id)
    return Bytes.EMPTY;
  }

  private void processTransaction(
      final LedgerState ledgerState,
      final DamlSubmission submission,
      final String participantId,
      final DamlLogEntryId entryId,
      final WorldUpdater updater)
      throws InternalError, InvalidTransactionException {

    long fetchStateStart = System.currentTimeMillis();
    Map<DamlStateKey, Option<DamlStateValue>> stateMap = buildStateMap(ledgerState, submission);

    if (stateMap.isEmpty()) {
      LOG.debug("No ledger states for submission");
    } else {
      stateMap.forEach((k, v) -> LOG.debug(String.format("  State %s=%s", k, v)));
    }

    long recordStateStart = System.currentTimeMillis();
    recordState(ledgerState, submission, participantId, stateMap, entryId, updater);

    long processFinished = System.currentTimeMillis();
    long recordStateTime = processFinished - recordStateStart;
    long fetchStateTime = recordStateStart - fetchStateStart;
    LOG.info(
        String.format(
            "Finished processing transaction, times=[fetch=%s,record=%s]",
            fetchStateTime, recordStateTime));
  }

  private Map<DamlStateKey, Option<DamlStateValue>> buildStateMap(
      final LedgerState ledgerState, final DamlSubmission submission)
      throws InvalidTransactionException, InternalError {

    LOG.debug(String.format("Fetching DamlState for this transaction"));
    Map<DamlStateKey, String> inputDamlStateKeys =
        KeyValueUtils.submissionToDamlStateAddress(submission);
    if (inputDamlStateKeys.isEmpty()) {
      LOG.debug("No input DAML state keys");
    }

    Map<DamlStateKey, DamlStateValue> inputStates =
        ledgerState.getDamlStates(inputDamlStateKeys.keySet());
    if (inputStates.isEmpty()) {
      LOG.debug("No ledger DAML state keys/values");
    }

    Map<DamlStateKey, Option<DamlStateValue>> inputStatesWithOption = new HashMap<>();
    inputDamlStateKeys
        .keySet()
        .forEach(
            key -> {
              KeyCase keyCase = key.getKeyCase();
              String address = Namespace.makeDamlStateAddress(key);
              DamlStateValue keyValue = inputStates.get(key);
              if (keyValue != null) {
                Option<DamlStateValue> option = Option.apply(keyValue);
                int keySize = keyValue.toByteString().size();
                if (keySize == 0) {
                  LOG.debug(
                      String.format(
                          "Fetched %s(%s), address=%s, size=empty", key, keyCase, address));
                } else {
                  LOG.debug(
                      String.format(
                          "Fetched %s(%s), address=%s, size=%s", key, keyCase, address, keySize));
                }
                inputStatesWithOption.put(key, option);
              } else {
                LOG.debug(
                    String.format(
                        "Fetched %s(%s), address=%s, size=empty (not found in input states)",
                        key, keyCase, address));
                inputStatesWithOption.put(key, Option.empty());
              }
            });
    return inputStatesWithOption;
  }

  private void recordState(
      final LedgerState ledgerState,
      final DamlSubmission submission,
      final String participantId,
      final Map<DamlStateKey, Option<DamlStateValue>> stateMap,
      final DamlLogEntryId entryId,
      final WorldUpdater updater)
      throws InternalError, InvalidTransactionException {

    long processStart = System.currentTimeMillis();
    String ledgerEffectiveTime = null;
    String maxRecordTime = null;
    if (submission.hasTransactionEntry()) {
      ledgerEffectiveTime =
          Conversions.parseTimestamp(submission.getTransactionEntry().getLedgerEffectiveTime())
              .toString();
      maxRecordTime =
          Conversions.parseTimestamp(
                  submission.getTransactionEntry().getSubmitterInfo().getMaximumRecordTime())
              .toString();
    }
    LOG.info(
        String.format(
            "Processing submission, recordTime=%s, ledgerEffectiveTime=%s, maxRecordTime=%s",
            getRecordTime(ledgerState), ledgerEffectiveTime, maxRecordTime));
    Tuple2<DamlLogEntry, Map<DamlStateKey, DamlStateValue>> processedSubmission =
        committer.processSubmission(
            getDefaultConfiguration(),
            entryId,
            getRecordTime(ledgerState),
            submission,
            participantId,
            stateMap);

    long recordStart = System.currentTimeMillis();
    DamlLogEntry newLogEntry = processedSubmission._1;
    Map<DamlStateKey, DamlStateValue> newState = processedSubmission._2;
    ledgerState.setDamlStates(newState.entrySet());

    LOG.debug(
        String.format("Recording log at %s, size=%d", entryId, newLogEntry.toByteString().size()));
    ledgerState.addDamlLogEntry(entryId, newLogEntry);

    updater.commit();
    @SuppressWarnings("unused")
    Log log = new Log(null, Bytes.of(newLogEntry.toByteArray()), null);

    long recordFinish = System.currentTimeMillis();
    long processTime = recordStart - processStart;
    long setStateTime = recordFinish - recordStart;
    long totalTime = recordFinish - processStart;
    LOG.info(
        String.format(
            "Record state timings [ total=%s, process=%s, setState=%s ]",
            totalTime, processTime, setStateTime));
  }

  private Timestamp getRecordTime(final LedgerState ledgerState) throws InternalError {
    com.google.protobuf.Timestamp recordTime = ledgerState.getRecordTime();
    long micros = Timestamps.toMicros(recordTime);
    return new Timestamp(micros);
  }

  private Configuration getDefaultConfiguration()
      throws InternalError, InvalidTransactionException {
    TimeModel tm =
        new TimeModel(
            Duration.ofSeconds(1),
            Duration.ofSeconds(DEFAULT_MAX_CLOCK_SKEW),
            Duration.ofSeconds(DEFAULT_MAX_TTL));
    LOG.debug(String.format("Default TimeModel set to %s", tm));
    Configuration blankConfiguration = new Configuration(0, tm);
    return blankConfiguration;
  }

  private static String stackTrace(final StackTraceElement[] stes) {
    final String PREFIX = "\n    at ";
    return String.format(
        "%s%s",
        PREFIX,
        Arrays.stream(stes)
            .skip(1)
            .map(StackTraceElement::toString)
            .collect(Collectors.joining(PREFIX)));
  }
}

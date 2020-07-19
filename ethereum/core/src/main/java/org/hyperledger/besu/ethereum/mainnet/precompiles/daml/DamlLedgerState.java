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

import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlLogEntry;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlLogEntryId;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlStateKey;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlStateValue;
import com.daml.ledger.participant.state.kvutils.KeyValueCommitting;
import com.daml.ledger.participant.state.v1.TimeModel;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.ethereum.core.MutableAccount;
import org.hyperledger.besu.ethereum.rlp.RLP;

public class DamlLedgerState implements LedgerState {
  private static final Logger LOG = LogManager.getLogger();

  private final MutableAccount account;

  public DamlLedgerState(final MutableAccount theAccount) {
    this.account = theAccount;
  }

  @Override
  public DamlStateValue getDamlState(final DamlStateKey key)
      throws InternalError {
    LOG.debug(String.format("Getting DAML state for key=[%s]", key));

    final UInt256 address = Namespace.makeDamlStateKeyAddress(key);
    LOG.debug(
        String.format("DAML state key address=[%s]", address.toHexString()));
    final ByteBuffer buf = getLedgerEntry(address);
    if (buf == null) {
      LOG.debug(String.format("No ledger entry for DAML state key address=[%s]",
                              address.toHexString()));
      return null;
    }
    try {
      return DamlStateValue.parseFrom(buf);
    } catch (final InvalidProtocolBufferException ipbe) {
      throw new InternalError("Failed to parse DAML state", ipbe);
    }
  }

  @Override
  public Map<DamlStateKey, DamlStateValue>
  getDamlStates(final Collection<DamlStateKey> keys) throws InternalError {
    final Map<DamlStateKey, DamlStateValue> states = new LinkedHashMap<>();
    keys.forEach(key -> {
      try {
        DamlStateValue val = getDamlState(key);
        if (val != null) {
          states.put(key, val);
        }
      } catch (final InternalError e) {
        LOG.error("Failed to parse DAML state:", e);
      }
    });
    return states;
  }

  @Override
  public Map<DamlStateKey, DamlStateValue>
  getDamlStates(final DamlStateKey... keys) throws InternalError {
    return getDamlStates(Lists.newArrayList(keys));
  }

  @Override
  public DamlLogEntry getDamlLogEntry(final DamlLogEntryId entryId)
      throws InternalError {
    LOG.debug(String.format("Getting DAML log entry for id=[%s]", entryId));
    final UInt256 address = Namespace.makeDamlLogEntryIdAddress(entryId);
    LOG.debug(
        String.format("DAML log entry id address=[%s]", address.toHexString()));
    final ByteBuffer buf = getLedgerEntry(address);
    if (buf == null) {
      LOG.debug(
          String.format("No ledger entry for DAML log entry id address=[%s]",
                        address.toHexString()));
      return null;
    }
    try {
      return DamlLogEntry.parseFrom(buf);
    } catch (final InvalidProtocolBufferException ipbe) {
      throw new InternalError("Failed to parse daml log entry", ipbe);
    }
  }

  private ByteBuffer getLedgerEntry(final UInt256 key) {
    // reconstitute RLP bytes from all ethereum slices created for this ledger
    // entry
    MutableBytes32 slot = key.toBytes().mutableCopy();
    LOG.debug(String.format("Will fetch slices starting at rootKey=%s",
                            slot.toHexString()));
    UInt256 data = account.getOriginalStorageValue(UInt256.fromBytes(slot));
    LOG.trace(String.format("Fetched data=%s", data.toHexString()));
    int slices = 1;
    Bytes rawRlp = Bytes.EMPTY;
    while (!data.isZero()) {
      rawRlp = Bytes.concatenate(rawRlp, data.toBytes());
      LOG.trace(String.format("So far rawRlp=%s", rawRlp.toHexString()));
      slot.increment();
      slices += 1;
      data = account.getOriginalStorageValue(UInt256.fromBytes(slot));
      LOG.trace(String.format("Fetched data=%s", data.toHexString()));
    }
    LOG.debug(String.format("Fetched from rootKey=%s slices=%s size=%s",
                            key.toHexString(), slices, rawRlp.size()));
    if (rawRlp.size() != 0) {
      final Bytes entry = RLP.decodeOne(rawRlp);
      return ByteBuffer.wrap(entry.toArray());
    } else {
      return null;
    }
  }

  @Override
  public Map<DamlLogEntryId, DamlLogEntry>
  getDamlLogEntries(final Collection<DamlLogEntryId> ids) throws InternalError {
    final Map<DamlLogEntryId, DamlLogEntry> logs = new LinkedHashMap<>();
    ids.forEach(id -> {
      try {
        DamlLogEntry entry = getDamlLogEntry(id);
        if (entry != null) {
          logs.put(id, getDamlLogEntry(id));
        }
      } catch (final InternalError e) {
        LOG.error("Failed to parse daml log entry:", e);
      }
    });
    return logs;
  }

  @Override
  public Map<DamlLogEntryId, DamlLogEntry>
  getDamlLogEntries(final DamlLogEntryId... ids) throws InternalError {
    return getDamlLogEntries(Lists.newArrayList(ids));
  }

  /**
   * Add the supplied data to the ledger, starting at the supplied ethereum
   * storage slot address.
   *
   * @param rootAddress 256-bit ethereum storage slot address
   * @param entry       value to store in the ledger
   */
  private void addLedgerEntry(final UInt256 rootAddress,
                              final ByteString entry) {
    // RLP-encode the entry
    final Bytes encoded = RLP.encodeOne(Bytes.of(entry.toByteArray()));
    MutableBytes32 slot = rootAddress.toBytes().mutableCopy();
    LOG.debug(String.format("Writing starting at address=%s bytes=%s",
                            rootAddress.toHexString(), encoded.size()));
    // store the first part of the entry
    int sliceSz = Math.min(Namespace.STORAGE_SLOT_SIZE, encoded.size());
    Bytes data = encoded.slice(0, sliceSz);
    UInt256 part = UInt256.fromBytes(data);
    int slices = 0;
    account.setStorageValue(UInt256.fromBytes(slot), part);
    LOG.trace(String.format("Wrote to address=%s slice=%s total bytes=%s",
                            UInt256.fromBytes(slot).toHexString(), slices,
                            part.toHexString()));

    // Store remaining parts, if any. We ensure that the data is stored in
    // consecutive
    // ethereum storage slots by incrementing the slot by one each time
    int offset = Namespace.STORAGE_SLOT_SIZE;
    while (offset < encoded.size()) {
      final int length =
          Math.min(Namespace.STORAGE_SLOT_SIZE, encoded.size() - offset);
      data = encoded.slice(offset, length);
      part = UInt256.fromBytes(data);
      slot.increment();
      slices++;
      account.setStorageValue(UInt256.fromBytes(slot), UInt256.fromBytes(data));
      LOG.trace(String.format("Wrote to address=%s slice=%s bytes=%s",
                              UInt256.fromBytes(slot).toHexString(), slices,
                              part.toHexString()));
      offset += Namespace.STORAGE_SLOT_SIZE;
    }
    LOG.debug(String.format("Wrote to address=%s slices=%s total size=%s",
                            rootAddress.toHexString(), slices, encoded.size()));
  }

  @Override
  public void setDamlState(final DamlStateKey key, final DamlStateValue value)
      throws InternalError {
    final ByteString packedKey = KeyValueCommitting.packDamlStateKey(key);
    final ByteString packedValue =
        key.getKeyCase().equals(DamlStateKey.KeyCase.COMMAND_DEDUP)
            ? packedKey
            : KeyValueCommitting.packDamlStateValue(value);
    final UInt256 rootAddress =
        Namespace.makeAddress(Namespace.DamlKeyType.STATE, packedKey);
    addLedgerEntry(rootAddress, packedValue);
  }

  @Override
  public void
  setDamlStates(final Collection<Entry<DamlStateKey, DamlStateValue>> entries)
      throws InternalError {
    entries.forEach(e -> setDamlState(e.getKey(), e.getValue()));
  }

  @Override
  public UInt256 addDamlLogEntry(final DamlLogEntryId entryId,
                                 final DamlLogEntry entry)
      throws InternalError {
    final ByteString packedEntryId =
        KeyValueCommitting.packDamlLogEntryId(entryId);
    final UInt256 rootAddress =
        Namespace.makeAddress(Namespace.DamlKeyType.LOG, packedEntryId);
    addLedgerEntry(rootAddress, KeyValueCommitting.packDamlLogEntry(entry));
    return rootAddress;
  }

  @Override
  public void sendLogEvent(final DamlLogEntryId entryId,
                           final DamlLogEntry entry) throws InternalError {
    throw new InternalError("Method not implemented");
  }

  @Override
  public Timestamp getRecordTime() throws InternalError {
    Instant time = Instant.now();
    // Reducing resolution to 10s for now
    long ts = time.getEpochSecond() / 100L;
    ts = ts * 100L;

    Timestamp timestamp =
        Timestamp.newBuilder().setSeconds(ts).setNanos(0).build();
    LOG.debug(String.format("Record Time = %s", timestamp.toString()));
    return timestamp;
  }

  @Override
  public void updateLogEntryIndex(final List<String> addresses)
      throws InternalError {
    throw new InternalError("Method not implemented");
  }

  @Override
  public List<String> getLogEntryIndex() throws InternalError {
    throw new InternalError("Method not implemented");
  }

  @Override
  public TimeModel getTimeModel() throws InternalError {
    throw new InternalError("Method not implemented");
  }

  @Override
  public void setTimeModel(final TimeModel tm) throws InternalError {
    throw new InternalError("Method not implemented");
  }
}

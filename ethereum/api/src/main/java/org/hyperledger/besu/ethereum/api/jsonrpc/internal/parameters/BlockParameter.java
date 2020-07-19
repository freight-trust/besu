/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Objects;
import java.util.OptionalLong;
import org.hyperledger.besu.ethereum.core.BlockHeader;

// Represents a block parameter that can be a special value ("pending",
// "earliest", "latest") or a number formatted as a hex string. See:
// https://github.com/ethereum/wiki/wiki/JSON-RPC#the-default-block-parameter
public class BlockParameter {

  private final BlockParameterType type;
  private final OptionalLong number;
  public static final BlockParameter EARLIEST = new BlockParameter("earliest");
  public static final BlockParameter LATEST = new BlockParameter("latest");
  public static final BlockParameter PENDING = new BlockParameter("pending");

  @JsonCreator
  public BlockParameter(final String value) {
    final String normalizedValue = value.toLowerCase();

    if (Objects.equals(normalizedValue, "earliest")) {
      type = BlockParameterType.EARLIEST;
      number = OptionalLong.of(BlockHeader.GENESIS_BLOCK_NUMBER);
    } else if (Objects.equals(normalizedValue, "latest")) {
      type = BlockParameterType.LATEST;
      number = OptionalLong.empty();
    } else if (Objects.equals(normalizedValue, "pending")) {
      type = BlockParameterType.PENDING;
      number = OptionalLong.empty();
    } else {
      type = BlockParameterType.NUMERIC;
      number = OptionalLong.of(Long.decode(value));
    }
  }

  public BlockParameter(final long value) {
    type = BlockParameterType.NUMERIC;
    number = OptionalLong.of(value);
  }

  public OptionalLong getNumber() { return number; }

  public boolean isPending() { return this.type == BlockParameterType.PENDING; }

  public boolean isLatest() { return this.type == BlockParameterType.LATEST; }

  public boolean isEarliest() {
    return this.type == BlockParameterType.EARLIEST;
  }

  public boolean isNumeric() { return this.type == BlockParameterType.NUMERIC; }

  @Override
  public String toString() {
    return "BlockParameter{"
        + "type=" + type + ", number=" + number + '}';
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
    BlockParameter that = (BlockParameter)o;
    return type == that.type && number.equals(that.number);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, number);
  }

  private enum BlockParameterType { EARLIEST, LATEST, PENDING, NUMERIC }
}

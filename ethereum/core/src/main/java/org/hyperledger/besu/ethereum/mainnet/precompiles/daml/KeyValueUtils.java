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

import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlStateKey;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlSubmission;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * A class providing utility static methods for use with DAML KeyValue
 * participant-state.
 */
public final class KeyValueUtils {

  /**
   * Take a DamlSubmission and return the mapping of the DamlStateKeys to
   * ethereum addresses which will be used as state input to this submission.
   *
   * @param submission the DamlSubmission to be analyzed
   * @return a mapping of DamlStateKey to ethereum address
   */
  public static Map<DamlStateKey, UInt256>
  submissionToDamlStateKeyAddress(final DamlSubmission submission) {
    Map<DamlStateKey, UInt256> inputKeys = new LinkedHashMap<>();
    submission.getInputDamlStateList().forEach(
        k -> inputKeys.put(k, Namespace.makeDamlStateKeyAddress(k)));
    return inputKeys;
  }

  private KeyValueUtils() {}
}

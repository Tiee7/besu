/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.consensus.merge.MergeProtocolSchedule;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.chain.GenesisState;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.forkid.ForkId;
import org.hyperledger.besu.ethereum.forkid.ForkIdManager;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.code.BonsaiCodeCache;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class EphemeryForkScheduleTest {
  private static final long BASE_TIMESTAMP = 1720119600L;
  private static final long PERIOD = 2419200L;
  private static final long ITERATION_164_TIMESTAMP = 1790276400L;
  private static final long BPO1_164 = 1791063432L;
  private static final long BPO2_164 = 1791849864L;

  @ParameterizedTest
  @ValueSource(ints = {152, 153, 163, 164, 165})
  void schedulesBpoForksAtThePublishedOffsets(final int iteration) {
    final long timestamp = BASE_TIMESTAMP + (iteration - 135L) * PERIOD;
    final GenesisConfig genesis = updateAt(timestamp, new TreeMap<>());
    assertThat(genesis.getTimestamp()).isEqualTo(timestamp);
    assertThat(genesis.getConfigOptions().getChainId())
        .hasValue(BigInteger.valueOf(39438000L + iteration));
    assertThat(genesis.getConfigOptions().getBpo1Time()).hasValue(timestamp + 787032L);
    assertThat(genesis.getConfigOptions().getBpo2Time()).hasValue(timestamp + 1573464L);
    assertThat(genesis.getConfigOptions().getShanghaiTime()).hasValue(0L);
    assertThat(genesis.getConfigOptions().getCancunTime()).hasValue(0L);
    assertThat(genesis.getConfigOptions().getPragueTime()).hasValue(0L);
    assertThat(genesis.getConfigOptions().getOsakaTime()).hasValue(0L);
  }

  @Test
  void preservesFutureBpoActivationBeforeTheFirstBpoIteration() {
    final GenesisConfig genesis = updateAt(BASE_TIMESTAMP + 16 * PERIOD, new TreeMap<>());
    assertThat(genesis.getConfigOptions().getBpo1Time()).hasValue(1762033032L);
    assertThat(genesis.getConfigOptions().getBpo2Time()).hasValue(1762819464L);
  }

  @Test
  void preservesExplicitForkOverrides() {
    final Map<String, String> overrides = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    overrides.put("BPO1TIME", "0");
    overrides.put("bpo2Time", "1792000000");
    final GenesisConfig genesis = updateAt(ITERATION_164_TIMESTAMP, overrides);
    assertThat(genesis.getConfigOptions().getBpo1Time()).hasValue(0L);
    assertThat(genesis.getConfigOptions().getBpo2Time()).hasValue(1792000000L);
  }

  @Test
  void preservesAnExplicitlyDisabledFork() {
    final Map<String, String> overrides = new TreeMap<>();
    overrides.put("bpo1Time", "");
    final GenesisConfig genesis = updateAt(ITERATION_164_TIMESTAMP, overrides);
    assertThat(genesis.getConfigOptions().getBpo1Time()).isEmpty();
    assertThat(genesis.getConfigOptions().getBpo2Time()).hasValue(BPO2_164);
  }

  @Test
  void recalculatesDefaultsWhenTheSameOverridesAreReusedAfterReset() {
    final Map<String, String> overrides = new TreeMap<>();
    final GenesisConfig previous = updateAt(ITERATION_164_TIMESTAMP, overrides);
    assertThat(previous.getConfigOptions().getBpo1Time()).hasValue(BPO1_164);
    final GenesisConfig next = updateAt(ITERATION_164_TIMESTAMP + PERIOD, overrides);
    assertThat(next.getConfigOptions().getBpo1Time()).hasValue(BPO1_164 + PERIOD);
    assertThat(next.getConfigOptions().getBpo2Time()).hasValue(BPO2_164 + PERIOD);
    assertThat(overrides).doesNotContainKeys("bpo1Time", "bpo2Time");
  }

  @Test
  void usesThePublishedForkIdsAndBlobLimitsAcrossBpoTransitions() {
    final GenesisConfig genesis = updateAt(ITERATION_164_TIMESTAMP, new TreeMap<>());
    final ProtocolSchedule schedule =
        MergeProtocolSchedule.create(
            genesis.getConfigOptions(),
            false,
            MiningConfiguration.MINING_DISABLED,
            new BadBlockManager(),
            false,
            BalConfiguration.DEFAULT,
            new NoOpMetricsSystem(),
            EvmConfiguration.DEFAULT);
    final Blockchain blockchain = mock(Blockchain.class);
    when(blockchain.getGenesisBlock())
        .thenReturn(GenesisState.fromConfig(genesis, schedule, new BonsaiCodeCache()).getBlock());
    final ForkIdManager forkIdManager =
        new ForkIdManager(blockchain, genesis.getForkBlockNumbers(), genesis.getForkTimestamps());
    assertForkAndBlobLimits(
        blockchain, forkIdManager, schedule, BPO1_164 - 1, 0xaa3371beL, BPO1_164, 6, 9);
    assertForkAndBlobLimits(
        blockchain, forkIdManager, schedule, BPO1_164, 0x21e55fe6L, BPO2_164, 8, 12);
    assertForkAndBlobLimits(
        blockchain, forkIdManager, schedule, BPO2_164 - 1, 0x21e55fe6L, BPO2_164, 8, 12);
    assertForkAndBlobLimits(blockchain, forkIdManager, schedule, BPO2_164, 0x33ef7281L, 0, 10, 15);
  }

  private static void assertForkAndBlobLimits(
      final Blockchain blockchain,
      final ForkIdManager forkIdManager,
      final ProtocolSchedule schedule,
      final long timestamp,
      final long crc,
      final long next,
      final int targetBlobs,
      final int maxBlobs) {
    final var header = new BlockHeaderTestFixture().number(1).timestamp(timestamp).buildHeader();
    when(blockchain.getChainHeadHeader()).thenReturn(header);
    assertThat(forkIdManager.getForkIdForChainHead())
        .isEqualTo(new ForkId(Bytes.ofUnsignedInt(crc), next));
    final var gasLimits = schedule.getByBlockHeader(header).getGasLimitCalculator();
    assertThat(gasLimits.getTargetBlobGasPerBlock()).isEqualTo(targetBlobs * 131072L);
    assertThat(gasLimits.currentBlobGasLimit()).isEqualTo(maxBlobs * 131072L);
  }

  private static GenesisConfig updateAt(final long timestamp, final Map<String, String> overrides) {
    final Instant now = Instant.ofEpochSecond(timestamp);
    try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
      clock.when(Instant::now).thenReturn(now);
      return EphemeryGenesisUpdater.updateGenesis(overrides);
    }
  }
}

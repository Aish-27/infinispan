package org.infinispan.notifications.cachelistener;

import static org.infinispan.test.TestingUtil.crashCacheManagers;
import static org.infinispan.test.TestingUtil.installNewView;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.infinispan.commons.util.IntSet;
import org.infinispan.commons.util.IntSets;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.distribution.LocalizedCacheTopology;
import org.infinispan.distribution.MagicKey;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.SegmentsLost;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;
import org.infinispan.notifications.cachelistener.event.SegmentsLostEvent;
import org.infinispan.partitionhandling.PartitionHandling;
import org.infinispan.remoting.transport.Address;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestDataSCI;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.CleanupAfterMethod;
import org.infinispan.topology.ClusterTopologyManager;
import org.testng.annotations.Test;

/**
 * Tests that {@link SegmentsLost} listeners are notified when a topology change causes one or more segments
 * to lose all of their owners (and therefore their data), both for clustered and local listeners.
 *
 * @see SegmentsLost
 */
@Test(groups = "functional", testName = "notifications.cachelistener.SegmentsLostListenerTest")
@CleanupAfterMethod
public class SegmentsLostListenerTest extends MultipleCacheManagersTest {

   @Override
   protected void createCacheManagers() throws Throwable {
      ConfigurationBuilder cb = new ConfigurationBuilder();
      cb.clustering().cacheMode(CacheMode.DIST_SYNC)
            .hash().numOwners(2)
            .partitionHandling().whenSplit(PartitionHandling.ALLOW_READ_WRITES);
      createClusteredCaches(4, TestDataSCI.INSTANCE, cb);
   }

   public void testClusteredListenerNotifiedOnSegmentsLost() {
      doTest(true);
   }

   public void testLocalListenerNotifiedOnSegmentsLost() {
      doTest(false);
   }

   private void doTest(boolean clustered) {
      SegmentsLostCollector collector = clustered ? new ClusteredCollector() : new LocalCollector();
      cache(0).addListener(collector);

      // A key owned exclusively by nodes 2 and 3, so node 0 and node 1 hold no copy of it.
      MagicKey lostKey = getKeyForCache(cache(2), cache(3));
      cache(0).put(lostKey, "value");

      // Clustered listeners should also receive the creation event forwarded from the owners.
      if (clustered) {
         eventually(() -> collector.created.contains(lostKey));
      }

      // Disable rebalancing so the segments are not recovered/reassigned with state after the crash.
      ClusterTopologyManager ctm = TestingUtil.extractGlobalComponent(manager(0), ClusterTopologyManager.class);
      ctm.setRebalancingEnabled(false).toCompletableFuture().join();

      IntSet expectedLost = computeFullyOwnedSegments(address(2), address(3));
      assertFalse("Test setup expects at least one segment owned only by nodes 2 and 3", expectedLost.isEmpty());
      assertTrue(expectedLost.contains(lostKey.getSegment()));

      // Crash both owners of the key simultaneously.
      crashCacheManagers(manager(2), manager(3));
      installNewView(manager(0), manager(1));

      eventually(() -> !collector.lostEvents.isEmpty(), 30, TimeUnit.SECONDS);

      IntSet reportedLost = IntSets.mutableEmptySet();
      for (SegmentsLostEvent<?, ?> event : collector.lostEvents) {
         assertFalse(event.isPre());
         assertFalse(event.getSegments().isEmpty());
         assertTrue(event.getNewTopologyId() > 0);
         reportedLost.addAll(event.getSegments());
      }

      assertEquals(expectedLost, reportedLost);

      // The data for the lost segments is gone.
      assertNull(cache(0).get(lostKey));
   }

   public void testNoNotificationWhenSegmentRetainsAnOwner() {
      SegmentsLostCollector collector = new LocalCollector();
      cache(0).addListener(collector);

      MagicKey key = getKeyForCache(cache(2), cache(3));
      cache(0).put(key, "value");

      // Kill a single owner. With numOwners=2 the segment still has one surviving owner, so nothing is lost.
      killMember(3);

      TestingUtil.waitForNoRebalance(caches());

      assertTrue("No segment should be reported as lost, but got: " + collector.lostEvents,
            collector.lostEvents.isEmpty());
   }

   private IntSet computeFullyOwnedSegments(Address... owners) {
      LocalizedCacheTopology topology = advancedCache(0).getDistributionManager().getCacheTopology();
      ConsistentHash readCH = topology.getReadConsistentHash();
      List<Address> expectedOwners = List.of(owners);
      IntSet result = IntSets.mutableEmptySet(readCH.getNumSegments());
      for (int segment = 0; segment < readCH.getNumSegments(); segment++) {
         List<Address> segmentOwners = readCH.locateOwnersForSegment(segment);
         if (!segmentOwners.isEmpty() && expectedOwners.containsAll(segmentOwners)) {
            result.set(segment);
         }
      }
      return result;
   }

   public abstract static class SegmentsLostCollector {
      final List<SegmentsLostEvent<Object, Object>> lostEvents = new CopyOnWriteArrayList<>();
      final List<Object> created = new CopyOnWriteArrayList<>();

      @SegmentsLost
      public void onSegmentsLost(SegmentsLostEvent<Object, Object> event) {
         lostEvents.add(event);
      }

      @CacheEntryCreated
      public void onCreated(CacheEntryCreatedEvent<Object, Object> event) {
         if (!event.isPre()) {
            created.add(event.getKey());
         }
      }
   }

   @Listener(clustered = true)
   public static class ClusteredCollector extends SegmentsLostCollector {
   }

   @Listener
   public static class LocalCollector extends SegmentsLostCollector {
   }
}

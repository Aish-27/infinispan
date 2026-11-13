package org.infinispan.notifications.cachelistener.event;

import org.infinispan.commons.util.IntSet;
import org.infinispan.notifications.cachelistener.annotation.SegmentsLost;

/**
 * An event passed in to methods annotated with {@link SegmentsLost}.
 * <p>
 * The event reports the set of segments that were lost as a result of the topology change identified by
 * {@link #getNewTopologyId()}. A segment is lost when every owner that held its data in the previous topology
 * has left the cache.
 *
 * @see SegmentsLost
 * @since 16.4
 */
public interface SegmentsLostEvent<K, V> extends Event<K, V> {

   /**
    * @return the segments that were lost. The returned set is never {@code null} and never empty.
    */
   IntSet getSegments();

   /**
    * @return the id of the new topology in which the segments were detected as lost.
    */
   int getNewTopologyId();
}

package org.infinispan.notifications.cachelistener.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.infinispan.notifications.IncorrectListenerException;
import org.infinispan.notifications.cachelistener.event.SegmentsLostEvent;

/**
 * This annotation should be used on methods that need to be notified when one or more segments are lost.
 * <p>
 * A segment is considered lost when, after a topology change, all of the owners that held the data for that
 * segment in the previous topology are no longer members of the cache. When this happens the data that was
 * stored in those segments is gone, but no {@link CacheEntryRemoved} events are fired because no explicit
 * removal took place. This notification allows applications that maintain state derived from the cache (near
 * caches, search indexes, CQRS projections, ...) to detect the loss and reconcile or invalidate their local
 * state for the affected segments.
 * <p>
 * Unlike {@link DataRehashed} and {@link TopologyChanged}, this annotation is also allowed on
 * {@linkplain org.infinispan.notifications.Listener#clustered() clustered} listeners. Each surviving node fires
 * the notification locally for the segments it detects as lost, so a clustered listener registered on a surviving
 * node is notified without any additional remote forwarding.
 * <p>
 * Methods annotated with this annotation should accept a single parameter, a {@link SegmentsLostEvent}, otherwise
 * an {@link IncorrectListenerException} will be thrown when registering your listener.
 * <p>
 * Any exceptions thrown by the listener will abort the call. Any other listeners not yet called will not be
 * called.
 *
 * @see org.infinispan.notifications.Listener
 * @since 16.4
 */
// ensure this annotation is available at runtime.
@Retention(RetentionPolicy.RUNTIME)
// ensure that this annotation is applied to methods.
@Target(ElementType.METHOD)
public @interface SegmentsLost {
}

/**
 * The MIT License
 * Copyright (c) 2016 Coveo
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.coveo.spillway.storage;

import com.coveo.spillway.limit.LimitKey;
import com.coveo.spillway.storage.utils.AddAndGetRequest;
import com.coveo.spillway.storage.utils.Capacity;
import com.coveo.spillway.storage.utils.OverrideKeyRequest;
import com.google.common.collect.Lists;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Implementation of {@link LimitUsageStorage} using memory.
 * <p>
 * Not recommended as a distributed storage solution since sharing memory
 * can be complicated. Perfect for local usages.
 *
 * @author Guillaume Simard
 * @author Emile Fugulin
 * @since 1.0.0
 */
public class InMemoryStorage implements LimitUsageStorage {

  Map<Instant, Map<LimitKey, Capacity>> map = new ConcurrentHashMap<>();
  private Clock clock = Clock.systemDefaultZone();

  @Override
  public Map<LimitKey, Integer> addAndGet(Collection<AddAndGetRequest> requests) {
    Map<LimitKey, Integer> updatedEntries = new HashMap<>();

    for (AddAndGetRequest request : requests) {
      Instant expirationDate = request.getBucket().plus(request.getExpiration());

      LimitKey limitKey = LimitKey.fromRequest(request);

      Map<LimitKey, Capacity> mapWithThisExpiration =
          map.computeIfAbsent(expirationDate, (key) -> new ConcurrentHashMap<>());
      Capacity counter = mapWithThisExpiration.computeIfAbsent(limitKey, (key) -> new Capacity());
      updatedEntries.put(limitKey, counter.addAndGet(request.getCost()));
    }
    removeExpiredEntries();

    return updatedEntries;
  }

  @Override
  public Map<LimitKey, Integer> debugCurrentLimitCounters() {
    removeExpiredEntries();
    return map.values()
        .stream()
        .flatMap(m -> m.entrySet().stream())
        .collect(Collectors.toMap(Map.Entry::getKey, kvp -> kvp.getValue().get()));
  }

  @Override
  public void close() {}

  public void overrideKeys(List<OverrideKeyRequest> overrides) {
    for (OverrideKeyRequest override : overrides) {
      Map<LimitKey, Capacity> mapWithThisExpiration =
          map.computeIfAbsent(override.getExpirationDate(), k -> new ConcurrentHashMap<>());
      mapWithThisExpiration.put(override.getLimitKey(), new Capacity(override.getNewValue()));
    }
    removeExpiredEntries();
  }

  public void applyOnEach(Consumer<Entry<Instant, Map<LimitKey, Capacity>>> action) {
    map.entrySet().forEach(action);
    removeExpiredEntries();
  }

  @Override
  public Map<LimitKey, Integer> getCurrentLimitCounters(String resource) {
    removeExpiredEntries();
    return filterLimitCountersBy(e -> e.getKey().getResource().equals(resource));
  }

  @Override
  public Map<LimitKey, Integer> getCurrentLimitCounters(String resource, String limit) {
    removeExpiredEntries();
    return filterLimitCountersBy(
        e -> e.getKey().getResource().equals(resource),
        e -> e.getKey().getLimitName().equals(limit));
  }

  @Override
  public Map<LimitKey, Integer> getCurrentLimitCounters(
      String resource, String limit, String property) {
    removeExpiredEntries();
    return filterLimitCountersBy(
        e -> e.getKey().getResource().equals(resource),
        e -> e.getKey().getLimitName().equals(limit),
        e -> e.getKey().getProperty().equals(property));
  }

  @SafeVarargs
  private final Map<LimitKey, Integer> filterLimitCountersBy(
      Predicate<Map.Entry<LimitKey, Capacity>>... predicates) {
    return map.values()
        .stream()
        .flatMap(m -> m.entrySet().stream())
        .filter(Lists.newArrayList(predicates).stream().reduce(Predicate::and).orElse(x -> true))
        .collect(Collectors.toMap(Map.Entry::getKey, kvp -> kvp.getValue().get()));
  }

  private void removeExpiredEntries() {
    Instant now = Instant.now(clock);
    Set<Instant> expiredDates =
        map.keySet()
            .stream()
            .filter(expiration -> now.isAfter(expiration))
            .collect(Collectors.toSet());
    map.keySet().removeAll(expiredDates);
  }
}

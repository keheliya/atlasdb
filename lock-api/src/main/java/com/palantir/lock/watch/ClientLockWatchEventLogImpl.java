/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.lock.watch;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.palantir.logsafe.Preconditions;

final class ClientLockWatchEventLogImpl implements ClientLockWatchEventLog {
    private static final boolean INCLUSIVE = true;

    private final ClientLockWatchSnapshotUpdater snapshotUpdater;
    private final TreeMap<Long, LockWatchEvent> eventMap = new TreeMap<>();

    private Optional<IdentifiedVersion> latestVersion = Optional.empty();

    static ClientLockWatchEventLog create() {
        return create(ClientLockWatchSnapshotUpdaterImpl.create());
    }

    @VisibleForTesting
    static ClientLockWatchEventLog create(ClientLockWatchSnapshotUpdater snapshotUpdater) {
        return new ClientLockWatchEventLogImpl(snapshotUpdater);
    }

    private ClientLockWatchEventLogImpl(ClientLockWatchSnapshotUpdater snapshotUpdater) {
        this.snapshotUpdater = snapshotUpdater;
    }

    @Override
    public Optional<IdentifiedVersion> processUpdate(LockWatchStateUpdate update) {
        final ProcessingVisitor visitor;
        if (!latestVersion.isPresent() || !update.logId().equals(latestVersion.get().id())) {
            visitor = new NewLeaderVisitor();
        } else {
            visitor = new ProcessingVisitor();
        }
        update.accept(visitor);
        return latestVersion;
    }

    @Override
    public void removeOldEntries(IdentifiedVersion earliestVersion) {
        Set<Map.Entry<Long, LockWatchEvent>> eventsToBeRemoved =
                eventMap.headMap(earliestVersion.version()).entrySet();
        snapshotUpdater.processEvents(
                eventsToBeRemoved.stream().map(Map.Entry::getValue).collect(Collectors.toList()));
        eventsToBeRemoved.clear();
    }

    /**
     * @param startVersion latest version that the client knows about; should be before timestamps in the mapping;
     * @param endVersion   mapping from timestamp to identified version from client-side event cache;
     * @return lock watch events that occurred from (exclusive) the provided version, up to (inclusive) the latest
     * version in the timestamp to version map.
     */
    @Override
    public ClientLogEvents getEventsBetweenVersions(
            Optional<IdentifiedVersion> startVersion,
            IdentifiedVersion endVersion) {
        Optional<IdentifiedVersion> versionInclusive = startVersion.map(this::createInclusiveVersion);
        IdentifiedVersion currentVersion = getLatestVersionAndVerify(endVersion);
        ClientLogEvents.Builder eventBuilder = new ClientLogEvents.Builder();
        final long fromSequence;

        if (!versionInclusive.isPresent() || differentLeaderOrTooFarBehind(currentVersion, versionInclusive.get())) {
            eventBuilder.addEvents(LockWatchCreatedEvent.fromSnapshot(snapshotUpdater.getSnapshot(currentVersion)));
            fromSequence = eventMap.firstKey();
            eventBuilder.clearCache(true);
        } else {
            fromSequence = versionInclusive.get().version();
            eventBuilder.clearCache(false);
        }

        eventBuilder.addAllEvents(eventMap.subMap(fromSequence, INCLUSIVE, endVersion.version(), INCLUSIVE).values());
        return eventBuilder.build();
    }

    @Override
    public Optional<IdentifiedVersion> getLatestKnownVersion() {
        return latestVersion;
    }

    private boolean differentLeaderOrTooFarBehind(IdentifiedVersion currentVersion,
            IdentifiedVersion startVersion) {
        return !startVersion.id().equals(currentVersion.id()) || eventMap.floorKey(startVersion.version()) == null;
    }

    private IdentifiedVersion createInclusiveVersion(IdentifiedVersion startVersion) {
        return IdentifiedVersion.of(startVersion.id(), startVersion.version() + 1);
    }

    private IdentifiedVersion getLatestVersionAndVerify(IdentifiedVersion endVersion) {
        Preconditions.checkState(latestVersion.isPresent(), "Cannot get events when log does not know its version");
        IdentifiedVersion currentVersion = latestVersion.get();
        Preconditions.checkArgument(IdentifiedVersion.comparator().compare(endVersion, currentVersion) > -1,
                "Transactions' view of the world is more up-to-date than the log");
        return currentVersion;
    }

    private void processSuccess(LockWatchStateUpdate.Success success) {
        Preconditions.checkState(latestVersion.isPresent(), "Must have a known version to process successful updates");

        if (success.lastKnownVersion() > latestVersion.get().version()) {
            success.events().forEach(event ->
                    eventMap.put(event.sequence(), event));
            latestVersion = Optional.of(IdentifiedVersion.of(success.logId(), eventMap.lastKey()));
        }
    }

    private void processSnapshot(LockWatchStateUpdate.Snapshot snapshot) {
        eventMap.clear();
        snapshotUpdater.resetWithSnapshot(snapshot);
        latestVersion = Optional.of(IdentifiedVersion.of(snapshot.logId(), snapshot.lastKnownVersion()));
    }

    private void processFailed() {
        eventMap.clear();
        snapshotUpdater.reset();
        latestVersion = Optional.empty();
    }

    private class ProcessingVisitor implements LockWatchStateUpdate.Visitor<Void> {
        @Override
        public Void visit(LockWatchStateUpdate.Failed _failed) {
            processFailed();
            return null;
        }

        @Override
        public Void visit(LockWatchStateUpdate.Success success) {
            processSuccess(success);
            return null;
        }

        @Override
        public Void visit(LockWatchStateUpdate.Snapshot snapshot) {
            processSnapshot(snapshot);
            return null;
        }
    }

    private final class NewLeaderVisitor extends ProcessingVisitor {
        @Override
        public Void visit(LockWatchStateUpdate.Success _success) {
            processFailed();
            return null;
        }
    }

}

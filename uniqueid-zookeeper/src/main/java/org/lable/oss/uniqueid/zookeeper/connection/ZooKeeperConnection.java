/**
 * Copyright (C) 2014 Lable (info@lable.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lable.oss.uniqueid.zookeeper.connection;


import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Singleton for a ZooKeeper connection object instance.
 */
public enum ZooKeeperConnection {
    INSTANCE;

    final static Logger logger = LoggerFactory.getLogger(ZooKeeperConnection.class);

    /**
     * Connection timeout, in seconds.
     */
    final static int CONNECTION_TIMEOUT = 10;

    final Queue<ZooKeeperConnectionObserver> observers = new ConcurrentLinkedQueue<>();

    ZooKeeper zookeeper = null;
    static String quorumAddresses = null;

    /**
     * Get the ZooKeeper connection object. If none exists, connect to the quorum and return the new object.
     *
     * @return The ZooKeeper object.
     * @throws IOException Thrown when connecting to the ZooKeeper quorum fails.
     */
    public static ZooKeeper get() throws IOException {
        if (quorumAddresses == null) {
            throw new RuntimeException("ZooKeeper quorum addresses were never configured.");
        }

        if (INSTANCE.zookeeper == null) {
            connect();
        }

        return INSTANCE.zookeeper;
    }

    public static void shutdown() {
        if (INSTANCE.zookeeper == null) return;

        try {
            INSTANCE.zookeeper.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            INSTANCE.zookeeper = null;
        }
    }

    /**
     * Connect to the ZooKeeper quorum, or timeout if it is unreachable.
     *
     * @throws IOException Thrown when connecting to the ZooKeeper quorum fails.
     */
    private static void connect() throws IOException {
        final CountDownLatch latch = new CountDownLatch(1);
        ZooKeeper zookeeper;

        // Connect to the quorum and wait for the successful connection callback.;
        zookeeper = new ZooKeeper(quorumAddresses, (int) SECONDS.toMillis(10), new Watcher() {
            @Override
            public void process(WatchedEvent watchedEvent) {
                if (watchedEvent.getState() == Event.KeeperState.SyncConnected) {
                    // Signal that the Zookeeper connection is established.
                    latch.countDown();
                }
            }
        });

        boolean successfullyConnected = false;
        try {
            successfullyConnected = latch.await(11, SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!successfullyConnected) {
            throw new IOException(String.format(
                    "Connection to ZooKeeper quorum timed out after %d seconds.", CONNECTION_TIMEOUT));
        }

        zookeeper.register(new ConnectionWatcher());

        INSTANCE.zookeeper = zookeeper;
    }

    public static void registerObserver(ZooKeeperConnectionObserver observer) {
        INSTANCE.observers.add(observer);
    }

    public static void deregisterObserver(ZooKeeperConnectionObserver observer) {
        INSTANCE.observers.remove(observer);
    }

    /**
     * Remove any preexisting ZooKeeper connection instance.
     * <p/>
     * This method should be called when the connection to the ZooKeeper is expired, so a subsequent call to
     * {@link #get()} will establish a new connection.
     */
    public static void reset() {
        INSTANCE.zookeeper = null;
    }

    /**
     * Configure the ZooKeeper quorum addresses.
     *
     * @param quorumAddresses The server address string expected by {@link org.apache.zookeeper.ZooKeeper}.
     */
    public static void configure(String quorumAddresses) {
        ZooKeeperConnection.quorumAddresses = quorumAddresses;
    }

    static class ConnectionWatcher implements Watcher {

        @Override
        public void process(WatchedEvent event) {
            switch (event.getState()) {
                case Disconnected:
                    logger.warn("Disconnected from ZooKeeper quorum.");
                    for (ZooKeeperConnectionObserver observer : INSTANCE.observers) {
                        observer.disconnected();
                    }
                    break;
                case Expired:
                    ZooKeeperConnection.reset();
                    break;
                case SyncConnected:
                    for (ZooKeeperConnectionObserver observer : INSTANCE.observers) {
                        observer.connected();
                    }
                    break;
                case AuthFailed:
                case ConnectedReadOnly:
                case SaslAuthenticated:
                    break;
            }
        }
    }
}

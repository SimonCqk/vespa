// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationLockException;
import com.yahoo.config.provision.Deployer;
import com.yahoo.config.provision.Deployment;
import com.yahoo.config.provision.TransientException;
import com.yahoo.jdisc.Metric;

import java.util.Objects;
import java.util.logging.Level;
import com.yahoo.transaction.Mutex;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.yolean.Exceptions;

import java.io.Closeable;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * A wrapper of a deployment suitable for maintenance.
 * This is a single-use, single-thread object.
 *
 * @author bratseth
 */
class MaintenanceDeployment implements Closeable {

    private static final Logger log = Logger.getLogger(MaintenanceDeployment.class.getName());

    private final ApplicationId application;
    private final Metric metric;
    private final Optional<Mutex> lock;
    private final Optional<Deployment> deployment;

    private boolean closed = false;

    public MaintenanceDeployment(ApplicationId application,
                                 Deployer deployer,
                                 Metric metric,
                                 NodeRepository nodeRepository) {
        this.application = application;
        this.metric = metric;
        Optional<Mutex> lock = tryLock(application, nodeRepository);
        try {
            deployment = tryDeployment(lock, application, deployer, nodeRepository);
            this.lock = lock;
            lock = Optional.empty();
        } finally {
            lock.ifPresent(Mutex::close);
        }
    }

    /** Return whether this is - as yet - functional and can be used to carry out the deployment */
    public boolean isValid() {
        return deployment.isPresent();
    }

    /**
     * Returns the application lock held by this, or empty if it is not held.
     *
     * @throws IllegalStateException id this is called when closed
     */
    public Optional<Mutex> applicationLock() {
        if (closed) throw new IllegalStateException(this + " is closed");
        return lock;
    }

    public boolean prepare() {
        return doStep(() -> deployment.get().prepare());
    }

    /**
     * Attempts to activate this deployment
     *
     * @return whether it was successfully activated
     */
    public boolean activate() {
        return doStep(() -> deployment.get().activate());
    }

    private boolean doStep(Runnable action) {
        if (closed) throw new IllegalStateException(this + "' is closed");
        if ( ! isValid()) return false;
        try {
            action.run();
            return true;
        } catch (TransientException e) {
            metric.add("maintenanceDeployment.transientFailure", 1, metric.createContext(Map.of()));
            log.log(Level.INFO, "Failed to maintenance deploy " + application + " with a transient error: " +
                                   Exceptions.toMessageString(e));
            return false;
        } catch (RuntimeException e) {
            metric.add("maintenanceDeployment.failure", 1, metric.createContext(Map.of()));
            log.log(Level.WARNING, "Exception on maintenance deploy of " + application, e);
            return false;
        }
    }

    private Optional<Mutex> tryLock(ApplicationId application, NodeRepository nodeRepository) {
        try {
            // Use a short lock to avoid interfering with change deployments
            return Optional.of(nodeRepository.lock(application, Duration.ofSeconds(1)));
        }
        catch (ApplicationLockException e) {
            return Optional.empty();
        }
    }

    private Optional<Deployment> tryDeployment(Optional<Mutex> lock,
                                               ApplicationId application,
                                               Deployer deployer,
                                               NodeRepository nodeRepository) {
        if (lock.isEmpty()) return Optional.empty();
        if (nodeRepository.getNodes(application, Node.State.active).isEmpty()) return Optional.empty();
        return deployer.deployFromLocalActive(application);
    }

    @Override
    public void close() {
        lock.ifPresent(l -> l.close());
        closed = true;
    }

    @Override
    public String toString() {
        return "deployment of " + application;
    }

    public static class Move {

        private final Node node;
        private final Node fromHost, toHost;

        Move(Node node, Node fromHost, Node toHost) {
            this.node = node;
            this.fromHost = fromHost;
            this.toHost = toHost;
        }

        public Node node() { return node; }
        public Node fromHost() { return fromHost; }
        public Node toHost() { return toHost; }

        /**
         * Try to deploy to make this move.
         *
         * @param verifyTarget true to only make this move if the node ends up at the expected target host,
         *                     false if we should perform it as long as it moves from the source host
         * @return true if the move was done, false if it couldn't be
         */
        public boolean execute(boolean verifyTarget,
                               Agent agent, Deployer deployer, Metric metric, NodeRepository nodeRepository) {
            if (isEmpty()) return false;
            ApplicationId application = node.allocation().get().owner();
            try (MaintenanceDeployment deployment = new MaintenanceDeployment(application, deployer, metric, nodeRepository)) {
                if ( ! deployment.isValid()) return false;

                boolean couldMarkRetiredNow = markWantToRetire(node, true, agent, nodeRepository);
                if ( ! couldMarkRetiredNow) return false;

                Optional<Node> expectedNewNode = Optional.empty();
                try {
                    if ( ! deployment.prepare()) return false;
                    if (verifyTarget) {
                        expectedNewNode =
                                nodeRepository.getNodes(application, Node.State.reserved).stream()
                                              .filter(n -> !n.hostname().equals(node.hostname()))
                                              .filter(n -> n.allocation().get().membership().cluster().id().equals(node.allocation().get().membership().cluster().id()))
                                              .findAny();
                        if (expectedNewNode.isEmpty()) return false;
                        if (!expectedNewNode.get().hasParent(toHost.hostname())) return false;
                    }
                    if ( ! deployment.activate()) return false;

                    log.info(agent + " redeployed " + application + " to " +
                             ( verifyTarget ? this : "move " + (node.hostname() + " from " + fromHost)));
                    return true;
                }
                finally {
                    markWantToRetire(node, false, agent, nodeRepository); // Necessary if this failed, no-op otherwise

                    // Immediately clean up if we reserved the node but could not activate or reserved a node on the wrong host
                    expectedNewNode.flatMap(node -> nodeRepository.getNode(node.hostname(), Node.State.reserved))
                                   .ifPresent(node -> nodeRepository.setDirty(node, agent, "Expired by " + agent));
                }
            }
        }

        /** Returns true only if this operation changes the state of the wantToRetire flag */
        private boolean markWantToRetire(Node node, boolean wantToRetire, Agent agent, NodeRepository nodeRepository) {
            try (Mutex lock = nodeRepository.lock(node)) {
                Optional<Node> nodeToMove = nodeRepository.getNode(node.hostname());
                if (nodeToMove.isEmpty()) return false;
                if (nodeToMove.get().state() != Node.State.active) return false;

                if (nodeToMove.get().status().wantToRetire() == wantToRetire) return false;

                nodeRepository.write(nodeToMove.get().withWantToRetire(wantToRetire, agent, nodeRepository.clock().instant()), lock);
                return true;
            }
        }

        public boolean isEmpty() { return node == null; }

        @Override
        public int hashCode() {
            return Objects.hash(node, fromHost, toHost);
        }

        public boolean equals(Object o) {
            if (o == this) return true;
            if (o == null || o.getClass() != this.getClass()) return false;

            Move other = (Move)o;
            if ( ! Objects.equals(other.node, this.node)) return false;
            if ( ! Objects.equals(other.fromHost, this.fromHost)) return false;
            if ( ! Objects.equals(other.toHost, this.toHost)) return false;
            return true;
        }

        @Override
        public String toString() {
            return "move " +
                   ( isEmpty() ? "none" : (node.hostname() + " from " + fromHost + " to " + toHost));
        }

        public static Move empty() { return new Move(null, null, null); }

    }

}

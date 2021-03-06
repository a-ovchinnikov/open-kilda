package org.openkilda.wfm.isl;

import org.openkilda.messaging.model.DiscoveryNode;
import org.openkilda.wfm.topology.stats.StatsTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * The DiscoveryManager holds the core logic for managing ISLs. This includes all of the
 * business rules related to Switch Up/Down, Port Up/Down, failure counts and limits, etc.
 * Comments on the business logic and rules are embedded in the rest of this class.
 *
 * TODO: Refactor DiscoveryManager in the following ways:
 *      1) Integrate any remaining business logic for storm code into this class so that the logic
 *          is concentrated in one place
 *      2) The timers/counters are a little bit out of whack and should be cleaned up:
 *          - how frequently to emit is clear .. but could be better to base it on seconds, not ticks
 *          - when to send a Failure if it is an ISL and we are not getting a response - currently
 *              using islConsecutiveFailureLimit, but the name could be better
 *          - when to stop - using forlornLimit - but would like a better name and more clarity
 *          - there are a few other fields that may be worthwhile .. ie better, more advance
 *              policy mechanism for behavior around ISL
 *      3) Ensure some separation between lifetime failure counts, current failure counts, and
 *          whether an ISL discovery packet should be sent. As an example, if we've stopped sending,
 *          and want to send discovery again, is there a clean way to do this?
 */
public class DiscoveryManager {
    private final Logger logger = LoggerFactory.getLogger(StatsTopology.class);

    private final IIslFilter filter;
    /** the frequency with which we should check if the ISL is healthy or existant */
    private final Integer islHealthCheckInterval;
    private final Integer islConsecutiveFailureLimit;
    private final Integer forlornLimit;
    private final LinkedList<DiscoveryNode> pollQueue;

    /**
     * @param filter - a list of nodes we should not do discovery on, if any.
     * @param persistentQueue - the persistent queue to use.
     * @param islHealthCheckInterval - how frequently (in ticks) to check.
     * @param islConsecutiveFailureLimit - the threshold for sending ISL down, if it is an ISL
     * @param forlornLimit - the threshold for stopping all checks.
     */
    public DiscoveryManager(IIslFilter filter, LinkedList<DiscoveryNode> persistentQueue,
                            Integer islHealthCheckInterval, Integer islConsecutiveFailureLimit,
                            Integer forlornLimit) {
        this.filter = filter;
        this.islHealthCheckInterval = islHealthCheckInterval;
        this.islConsecutiveFailureLimit = islConsecutiveFailureLimit;
        this.forlornLimit = forlornLimit;
        this.pollQueue = persistentQueue;
    }

    /**
     * The discovery plan takes into consideration multiple metrics to determine what should be
     * discovered.
     *
     * At present, we want to send Discovery health checks on every ISL every x period.
     * And, if the Discovery fails (either isn't an ISL or ISL is down) then we may want to give up
     * checking.
     *
     * General algorithm:
     * 1) if the node is an ISL (isFoundIsl) .. and is UP .. keep checking
     * 2) if the node is not an ISL (ie !isFoundIsl), then check less frequently
     * 3) if the node is an ISL .. and is DOWN .. keep checking
     */
    public Plan makeDiscoveryPlan() {
        Plan result = new Plan();

        for (DiscoveryNode subject : pollQueue) {

            if (!checkForIsl(subject)){
                continue;
            }

            /*
             * If we get a response from FL, we clear the attempts. Otherwise, no response, and
             * number of attempts grows.
             *
             * Further, consecutivefailures = attempts - failure limit (we wait until attempt limit before increasing)
             */
            Node node = new Node(subject.getSwitchId(), subject.getPortId());
            if (subject.maxAttempts(islConsecutiveFailureLimit)) {
                // We've attempted to get the health multiple times, with no response.
                // Time to mark it as a failure and send a failure notice ** if ** it was an ISL.
                if (subject.isFoundIsl() && subject.getConsecutiveFailure() == 0) {
                    // It is a discovery failure if it was previously a success.
                    // NB:
                    result.discoveryFailure.add(node);
                    logger.info("ISL IS DOWN (NO RESPONSE): {}", subject);
                }
                // Increment Failure = 1 after maxAttempts failure, then increases every attempt.
                subject.incConsecutiveFailure();
                // NB: this node can be in both discoveryFailure and needDiscovery
            }

            /*
             * If you get here, the following are true:
             *  - it isn't in some filter
             *  - it hasn't reached failure limit (forlorn)
             *  - it is either time to send discovery or not
             *  - NB: we'll keep trying to send discovery, even if we don't get a response.
             */
            if (subject.timeToCheck()) {
                subject.incAttempts();
                subject.resetTickCounter();
                result.needDiscovery.add(node);
            } else {
                subject.incTick();
            }

        }

        return result;
    }

    /**
     * ISL Discovery Event
     * @return true if this is a new event (ie first time discovered or prior failure)
     */
    public boolean handleDiscovered(String switchId, String portId) {
        boolean stateChanged = false;
        Node node = new Node(switchId, portId);
        List<DiscoveryNode> subjectList = filterQueue(node);

        if (subjectList.size() == 0) {
            logger.warn("Ignore \"AVAIL\" request for {}: node not found", node);
        } else {
            DiscoveryNode subject = subjectList.get(0);
            if (!subject.isFoundIsl()){
                // "forever" mark this port as part of an ISL
                // "forever" changes if we get another Switch UP message - this is documented
                //      elsewhere .. in short, be conservative - maybe ports changed, or TE state
                //      has been deleted.
                //  TODO: is marking foundIsl false the right way to do this? All we want is "resend to TE if it is an ISL"
                subject.setFoundIsl(true);
                stateChanged = true;
                logger.info("FOUND ISL: {}", subject);
            } else if (subject.getConsecutiveFailure()>0){
                // We've found failures, but now we've had success, so that is a state change.
                // To repeat, current model for state change is just 1 failure. If we change this
                // policy, then change the test above.
                stateChanged = true;
                logger.info("ISL IS UP: {}", subject);
            }
            subject.renew();
            subject.incConsecutiveSuccess();
            subject.clearConsecutiveFailure();
            // If one of the logs above wasn't reachd, don't log anything .. ISL was up and is still up
        }

        if (stateChanged) {
            // Add logic to ensure we send a discovery packet for the opposite direction.
            // TODO: in order to do this here, we need more information (ie the other end of the ISL)
            //      Since that isn't passed in and isn't available in our state, have to rely on the
            //      calling function.

        }
        return stateChanged;
    }

    /**
     * ISL Failure Event
     * @return true if this is new .. ie this isn't a consecutive failure.
     */
    public boolean handleFailed(String switchId, String portId) {
        boolean stateChanged = false;
        Node node = new Node(switchId, portId);
        List<DiscoveryNode> subjectList = filterQueue(node);

        if (subjectList.size() == 0) {
            logger.warn("Ignoring \"FAILED\" request for {}: node not found", node);
        } else {
            DiscoveryNode subject = subjectList.get(0);
            if (subject.isFoundIsl() && subject.getConsecutiveFailure() == 0){
                // This is the first failure for an ISL. That is a state change.
                // IF this isn't an ISL and we receive a failure, that isn't a state change.
                stateChanged = true;
                logger.info("ISL IS DOWN (GOT RESPONSE): {}", subject);
            }
            subject.renew();
            subject.incConsecutiveFailure();
            subject.clearConsecutiveSuccess();
        }
        return stateChanged;
    }

    public void handleSwitchUp(String switchId) {
        logger.info("Register switch {} into ISL discovery manager", switchId);
        // TODO: this method *use to not* do anything .. but it should register the switch.
        //          At least, it seems like it should do something to register a switch, even
        //          though this can be lazily done when the first port event arrives.

        /*
         * If a switch comes up, clear any "isFoundIsl" flags, in case something has changed,
         * and/or if the TE has cleared it's state .. this will pass along the ISL.
         */
        Node node = new Node(switchId, null);
        List<DiscoveryNode> subjectList = filterQueue(node, false);

        if (subjectList.size() > 0) {
            logger.info("Received SWITCH UP (id:{}) with EXISTING NODES.  Clearing isFoundISL flags", switchId);
            for (DiscoveryNode subject : subjectList) {
                subject.setFoundIsl(false);
                subject.clearConsecutiveFailure(); // ensure we bypass forlorn
            }
        }
    }

    public void handleSwitchDown(String switchId) {
        Node node = new Node(switchId, null);
        List<DiscoveryNode> subjectList = filterQueue(node, true);

        logger.info("Deregister switch {} from ISL discovery manager", switchId);
        for (DiscoveryNode subject : subjectList) {
            logger.info("Del {}", subject);
        }
    }

    public void handlePortUp(String switchId, String portId) {
        DiscoveryNode subject;
        Node node = new Node(switchId, portId);
        List<DiscoveryNode> subjectList = filterQueue(node);

        if (subjectList.size() != 0) {
            // Similar to SwitchUp, if we have a PortUp on an existing port, either we are receiving
            // a duplicate, or we missed the port down, or a new discovery has occurred.
            // NB: this should cause an ISL discovery packet to be sent.
            // TODO: we should probably separate "port up" from "do discovery". ATM, one would call
            //          this function just to get the "do discovery" functionality.
            subject = subjectList.get(0);
            logger.info("Port UP on existing node {};  clear failures and ISLFound", subject);
            subject.setFoundIsl(false);
            subject.clearConsecutiveFailure(); // ensure we bypass forlorn
            return;
        }

        subject = new DiscoveryNode(
                node.switchId, node.portId,
                this.islHealthCheckInterval,
                this.forlornLimit
        );
        pollQueue.add(subject);
        logger.info("New {}", subject);
    }

    public void handlePortDown(String switchId, String portId) {
        DiscoveryNode subject;
        Node node = new Node(switchId, portId);
        List<DiscoveryNode> subjectList = filterQueue(node, true);

        if (subjectList.size() == 0) {
            logger.warn("Can't update discovery {} -> node not found", node);
            return;
        }

        subject = subjectList.get(0);
        logger.info("Del {}", subject);
    }

    /**
     * Filter the list of nodes based on switch, or switch and port.
     *
     * @param subject The switch (if port is null), or switch and port, to match
     * @return a list of any matched nodes.
     */
    public List<DiscoveryNode> filterQueue(Node subject) {
        return filterQueue(subject, false);
    }

    private List<DiscoveryNode> filterQueue(Node subject, boolean extract) {
        List<DiscoveryNode> result = new LinkedList<>();
        for (ListIterator<DiscoveryNode> it = pollQueue.listIterator(); it.hasNext(); ) {
            DiscoveryNode node = it.next();
            if (!subject.matchDiscoveryNode(node)) {
                continue;
            }

            if (extract) {
                it.remove();
            }
            result.add(node);
        }

        return result;
    }

    /**
     * The "ISL" could be down if it is:
     * - not an ISL
     * - has timed out (forlorned)
     *
     * @return true if not an ISL or is forlorned
     */
    public boolean checkForIsl(String switchId, String portId) {
        List<DiscoveryNode> subjectList = filterQueue(new Node(switchId, portId));

        if (subjectList.size() != 0) {
            return checkForIsl(subjectList.get(0));
        }
        // We don't know about this node .. definitely not testing for ISL.
        return false;
    }

    public boolean checkForIsl(DiscoveryNode subject) {
        if (filter.isMatch(subject)) {
            // skip checks on what is in the Filter:
            // TODO: what is in the FILTER? Is this the external filter (ie known ISL's?) Still want health check in this scenario..
            logger.debug("Skip {} due to ISL filter match", subject);
            subject.renew();
            subject.resetTickCounter();
            return false;
        }
        return !subject.forlorn();
    }


    public class Plan {
        public final List<Node> needDiscovery;
        public final List<Node> discoveryFailure;

        private Plan() {
            this.needDiscovery = new LinkedList<>();
            this.discoveryFailure = new LinkedList<>();
        }
    }

    public static class Node {
        public final String switchId;
        public final String portId;

        public Node(String switchId, String portId) {
            this.switchId = switchId;
            this.portId = portId;
        }

        public Node(DiscoveryNode node) {
            this.switchId = node.getSwitchId();
            this.portId = node.getPortId();
        }

        boolean matchDiscoveryNode(DiscoveryNode target) {
            if (! switchId.equals(target.getSwitchId())) {
                return false;
            }
            if (portId == null) {
                return true;
            }
            return portId.equals(target.getPortId());
        }

        @Override
        public String toString() {
            return "Node{" +
                    "switchId='" + switchId + '\'' +
                    ", portId='" + portId + '\'' +
                    '}';
        }
    }
}

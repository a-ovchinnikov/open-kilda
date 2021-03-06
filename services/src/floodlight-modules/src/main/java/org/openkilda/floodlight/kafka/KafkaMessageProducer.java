/* Copyright 2017 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.floodlight.kafka;

import org.openkilda.messaging.Message;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Created by jonv on 6/3/17.
 */
public class KafkaMessageProducer implements IFloodlightModule, IFloodlightService {
    private Producer producer;
    private HeartBeat heartBeat;

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return Collections.singletonList(KafkaMessageProducer.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return Collections.singletonMap(KafkaMessageProducer.class, this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        return Collections.singletonList(IFloodlightProviderService.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(FloodlightModuleContext modueleContext) throws FloodlightModuleException {
        Context context = new Context(modueleContext, this);

        initProducer(context);
        initHeartBeat(context);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startUp(FloodlightModuleContext floodlightModuleContext) throws FloodlightModuleException {
    }

    /**
     * Send the message to Kafka.
     *
     * @param topic   topic to post the message to
     * @param message message to pose
     */
    public void postMessage(final String topic, final Message message) {
        producer.handle(topic, message);
        heartBeat.reschedule();
    }

    private void initProducer(Context context) {
        if (! "YES".equals(context.configLookup("testing-mode"))) {
            producer = new Producer(context);
        } else {
            producer = new TestAwareProducer(context);
        }
    }

    private void initHeartBeat(Context context) throws FloodlightModuleException {
        final String option = "heart-beat-interval";
        String value = context.configLookup(option);

        try {
            Float interval = Float.valueOf(context.configLookup(option));
            if (interval < 1) {
                throw new FloodlightModuleException(String.format(
                        "Invalid value for option %s: %s < 1", option, value));
            }
            heartBeat = new HeartBeat(producer, (long)(interval * 1000));
        } catch (NumberFormatException e) {
            throw new FloodlightModuleException(String.format(
                    "Invalid value for option %s=\"%s\", expect number", option, value));
        }
    }

    public Producer getProducer() {
        return producer;
    }
}

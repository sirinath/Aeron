/*
 * Copyright 2014 - 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.driver;

import uk.co.real_logic.aeron.CommonContext;
import uk.co.real_logic.aeron.command.CorrelatedMessageFlyweight;
import uk.co.real_logic.aeron.command.PublicationMessageFlyweight;
import uk.co.real_logic.aeron.command.RemoveMessageFlyweight;
import uk.co.real_logic.aeron.command.SubscriptionMessageFlyweight;
import uk.co.real_logic.aeron.driver.MediaDriver.Context;
import uk.co.real_logic.aeron.driver.buffer.RawLog;
import uk.co.real_logic.aeron.driver.buffer.RawLogFactory;
import uk.co.real_logic.aeron.driver.cmd.DriverConductorCmd;
import uk.co.real_logic.aeron.driver.event.EventCode;
import uk.co.real_logic.aeron.driver.event.EventLogger;
import uk.co.real_logic.aeron.driver.exceptions.ControlProtocolException;
import uk.co.real_logic.aeron.driver.media.ReceiveChannelEndpoint;
import uk.co.real_logic.aeron.driver.media.SendChannelEndpoint;
import uk.co.real_logic.aeron.driver.media.UdpChannel;
import uk.co.real_logic.aeron.logbuffer.FrameDescriptor;
import uk.co.real_logic.aeron.logbuffer.LogBufferDescriptor;
import uk.co.real_logic.aeron.protocol.DataHeaderFlyweight;
import uk.co.real_logic.agrona.BitUtil;
import uk.co.real_logic.agrona.MutableDirectBuffer;
import uk.co.real_logic.agrona.concurrent.*;
import uk.co.real_logic.agrona.concurrent.ringbuffer.RingBuffer;
import uk.co.real_logic.agrona.concurrent.status.Position;
import uk.co.real_logic.agrona.concurrent.status.UnsafeBufferPosition;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toList;
import static uk.co.real_logic.aeron.ErrorCode.*;
import static uk.co.real_logic.aeron.command.ControlProtocolEvents.*;
import static uk.co.real_logic.aeron.driver.Configuration.*;
import static uk.co.real_logic.aeron.driver.event.EventConfiguration.EVENT_READER_FRAME_LIMIT;

/**
 * Driver Conductor to take commands from publishers and subscribers as well as determining if loss has occurred.
 */
public class DriverConductor implements Agent
{
    private final long imageLivenessTimeoutNs;
    private final long clientLivenessTimeoutNs;
    private final int mtuLength;
    private final int termBufferLength;
    private final int initialWindowLength;
    private int nextSessionId = BitUtil.generateRandomisedId();

    private final RawLogFactory rawLogFactory;
    private final ReceiverProxy receiverProxy;
    private final SenderProxy senderProxy;
    private final ClientProxy clientProxy;
    private final DriverConductorProxy fromReceiverConductorProxy;
    private final RingBuffer toDriverCommands;
    private final RingBuffer toEventReader;
    private final OneToOneConcurrentArrayQueue<DriverConductorCmd> fromReceiverDriverConductorCmdQueue;
    private final OneToOneConcurrentArrayQueue<DriverConductorCmd> fromSenderDriverConductorCmdQueue;
    private final Supplier<FlowControl> unicastFlowControl;
    private final Supplier<FlowControl> multicastFlowControl;
    private final HashMap<String, SendChannelEndpoint> sendChannelEndpointByChannelMap = new HashMap<>();
    private final HashMap<String, ReceiveChannelEndpoint> receiveChannelEndpointByChannelMap = new HashMap<>();
    private final ArrayList<PublicationLink> publicationLinks = new ArrayList<>();
    private final ArrayList<NetworkPublication> networkPublications = new ArrayList<>();
    private final ArrayList<SubscriptionLink> subscriptionLinks = new ArrayList<>();
    private final ArrayList<PublicationImage> publicationImages = new ArrayList<>();
    private final ArrayList<AeronClient> clients = new ArrayList<>();
    private final ArrayList<DirectPublication> directPublications = new ArrayList<>();

    private final PublicationMessageFlyweight publicationMsgFlyweight = new PublicationMessageFlyweight();
    private final SubscriptionMessageFlyweight subscriptionMsgFlyweight = new SubscriptionMessageFlyweight();
    private final CorrelatedMessageFlyweight correlatedMsgFlyweight = new CorrelatedMessageFlyweight();
    private final RemoveMessageFlyweight removeMsgFlyweight = new RemoveMessageFlyweight();

    private final EpochClock epochClock;
    private final NanoClock nanoClock;
    private final SystemCounters systemCounters;
    private final UnsafeBuffer countersBuffer;
    private final CountersManager countersManager;
    private final EventLogger logger;
    private final Consumer<DriverConductorCmd> onDriverConductorCmdFunc = this::onDriverConductorCmd;
    private final MessageHandler onClientCommandFunc = this::onClientCommand;
    private final MessageHandler onEventFunc;
    private final LossGenerator dataLossGenerator;
    private final LossGenerator controlLossGenerator;

    private long timeOfLastTimeoutCheck;

    public DriverConductor(final Context ctx)
    {
        imageLivenessTimeoutNs = ctx.imageLivenessTimeoutNs();
        clientLivenessTimeoutNs = ctx.clientLivenessTimeoutNs();
        fromReceiverDriverConductorCmdQueue = ctx.toConductorFromReceiverCommandQueue();
        fromSenderDriverConductorCmdQueue = ctx.toConductorFromSenderCommandQueue();
        receiverProxy = ctx.receiverProxy();
        senderProxy = ctx.senderProxy();
        rawLogFactory = ctx.rawLogBuffersFactory();
        mtuLength = ctx.mtuLength();
        initialWindowLength = ctx.initialWindowLength();
        termBufferLength = ctx.termBufferLength();
        unicastFlowControl = ctx.unicastSenderFlowControl();
        multicastFlowControl = ctx.multicastSenderFlowControl();
        countersManager = ctx.countersManager();
        countersBuffer = ctx.counterValuesBuffer();
        epochClock = ctx.epochClock();
        nanoClock = ctx.nanoClock();
        toDriverCommands = ctx.toDriverCommands();
        toEventReader = ctx.toEventReader();
        clientProxy = ctx.clientProxy();
        fromReceiverConductorProxy = ctx.fromReceiverDriverConductorProxy();
        logger = ctx.eventLogger();
        systemCounters = ctx.systemCounters();
        dataLossGenerator = ctx.dataLossGenerator();
        controlLossGenerator = ctx.controlLossGenerator();

        final Consumer<String> eventConsumer = ctx.eventConsumer();
        onEventFunc =
            (typeId, buffer, offset, length) -> eventConsumer.accept(EventCode.get(typeId).decode(buffer, offset, length));

        final AtomicBuffer buffer = toDriverCommands.buffer();
        publicationMsgFlyweight.wrap(buffer, 0);
        subscriptionMsgFlyweight.wrap(buffer, 0);
        correlatedMsgFlyweight.wrap(buffer, 0);
        removeMsgFlyweight.wrap(buffer, 0);

        toDriverCommands.consumerHeartbeatTime(epochClock.time());
        timeOfLastTimeoutCheck = nanoClock.nanoTime();
    }

    public void onClose()
    {
        rawLogFactory.close();
        networkPublications.forEach(NetworkPublication::close);
        publicationImages.forEach(PublicationImage::close);
        directPublications.forEach(DirectPublication::close);
        sendChannelEndpointByChannelMap.values().forEach(SendChannelEndpoint::close);
        receiveChannelEndpointByChannelMap.values().forEach(ReceiveChannelEndpoint::close);
    }

    public String roleName()
    {
        return "driver-conductor";
    }

    public SendChannelEndpoint senderChannelEndpoint(final UdpChannel channel)
    {
        return sendChannelEndpointByChannelMap.get(channel.canonicalForm());
    }

    public ReceiveChannelEndpoint receiverChannelEndpoint(final UdpChannel channel)
    {
        return receiveChannelEndpointByChannelMap.get(channel.canonicalForm());
    }

    public DirectPublication getDirectPublication(final long streamId)
    {
        return findDirectPublication(directPublications, streamId);
    }

    public int doWork() throws Exception
    {
        int workCount = 0;

        workCount += toDriverCommands.read(onClientCommandFunc);
        workCount += fromReceiverDriverConductorCmdQueue.drain(onDriverConductorCmdFunc);
        workCount += fromSenderDriverConductorCmdQueue.drain(onDriverConductorCmdFunc);
        workCount += toEventReader.read(onEventFunc, EVENT_READER_FRAME_LIMIT);

        final long now = nanoClock.nanoTime();
        workCount += processTimers(now);

        final ArrayList<PublicationImage> publicationImages = this.publicationImages;
        for (int i = 0, size = publicationImages.size(); i < size; i++)
        {
            final PublicationImage image = publicationImages.get(i);
            workCount += image.trackRebuild(now);
        }

        final ArrayList<NetworkPublication> networkPublications = this.networkPublications;
        for (int i = 0, size = networkPublications.size(); i < size; i++)
        {
            final NetworkPublication publication = networkPublications.get(i);
            workCount += publication.updatePublishersLimit() + publication.cleanLogBuffer();
        }

        final ArrayList<DirectPublication> directPublications = this.directPublications;
        for (int i = 0, size = directPublications.size(); i < size; i++)
        {
            final DirectPublication directPublication = directPublications.get(i);
            workCount += directPublication.updatePublishersLimit() + directPublication.cleanLogBuffer();
        }

        return workCount;
    }

    public void onCreatePublicationImage(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final int activeTermId,
        final int initialTermOffset,
        final int termBufferLength,
        final int senderMtuLength,
        final InetSocketAddress controlAddress,
        final InetSocketAddress sourceAddress,
        final ReceiveChannelEndpoint channelEndpoint)
    {
        channelEndpoint.validateSenderMtuLength(senderMtuLength);
        channelEndpoint.validateWindowMaxLength(initialWindowLength);

        final UdpChannel udpChannel = channelEndpoint.udpChannel();
        final String channel = udpChannel.originalUriString();
        final long imageCorrelationId = nextImageCorrelationId();

        final long joiningPosition = LogBufferDescriptor.computePosition(
            activeTermId, initialTermOffset, Integer.numberOfTrailingZeros(termBufferLength), initialTermId);

        final List<SubscriberPosition> subscriberPositions = listSubscriberPositions(
            sessionId, streamId, channelEndpoint, channel, joiningPosition);

        if (subscriberPositions.size() > 0)
        {
            final RawLog rawLog = newPublicationImageLog(
                sessionId, streamId, initialTermId, termBufferLength, senderMtuLength, udpChannel, imageCorrelationId);

            final PublicationImage image = new PublicationImage(
                imageCorrelationId,
                imageLivenessTimeoutNs,
                channelEndpoint,
                controlAddress,
                sessionId,
                streamId,
                initialTermId,
                activeTermId,
                initialTermOffset,
                initialWindowLength,
                rawLog,
                Configuration.doNotSendNaks() ? NO_NAK_DELAY_GENERATOR :
                    udpChannel.isMulticast() ? NAK_MULTICAST_DELAY_GENERATOR : NAK_UNICAST_DELAY_GENERATOR,
                subscriberPositions.stream().map(SubscriberPosition::position).collect(toList()),
                newPosition("receiver hwm", channel, sessionId, streamId, imageCorrelationId),
                nanoClock,
                systemCounters,
                sourceAddress);

            subscriberPositions.forEach(
                (subscriberPosition) -> subscriberPosition.subscription().addImage(image, subscriberPosition.position()));

            publicationImages.add(image);
            receiverProxy.newPublicationImage(channelEndpoint, image);

            clientProxy.onImageReady(
                streamId,
                sessionId,
                joiningPosition,
                rawLog,
                imageCorrelationId,
                subscriberPositions,
                generateSourceIdentity(sourceAddress));
        }
    }

    public void onCloseResource(final AutoCloseable resource)
    {
        try
        {
            resource.close();
        }
        catch (final Exception ex)
        {
            logger.logException(ex);
        }
    }

    public void cleanupPublication(final NetworkPublication publication)
    {
        final SendChannelEndpoint channelEndpoint = publication.sendChannelEndpoint();

        logger.logPublicationRemoval(
            channelEndpoint.originalUriString(), publication.sessionId(), publication.streamId());

        senderProxy.removeNetworkPublication(publication);

        if (channelEndpoint.sessionCount() == 0)
        {
            sendChannelEndpointByChannelMap.remove(channelEndpoint.udpChannel().canonicalForm());
            senderProxy.closeSendChannelEndpoint(channelEndpoint);
        }
    }

    public void cleanupSubscriptionLink(final SubscriptionLink link)
    {
        final ReceiveChannelEndpoint channelEndpoint = link.channelEndpoint();

        if (null != channelEndpoint)
        {
            final int streamId = link.streamId();

            logger.logSubscriptionRemoval(
                channelEndpoint.originalUriString(), link.streamId(), link.registrationId());

            if (0 == channelEndpoint.decRefToStream(link.streamId()))
            {
                receiverProxy.removeSubscription(channelEndpoint, streamId);
            }

            if (channelEndpoint.streamCount() == 0)
            {
                receiveChannelEndpointByChannelMap.remove(channelEndpoint.udpChannel().canonicalForm());
                receiverProxy.closeReceiveChannelEndpoint(channelEndpoint);
            }
        }
    }

    public void imageTransitionToLinger(final PublicationImage image)
    {
        clientProxy.onInactiveImage(
            image.correlationId(),
            image.sessionId(),
            image.streamId(),
            image.rebuildPosition(),
            image.channelUriString());

        receiverProxy.removeCoolDown(image.channelEndpoint(), image.sessionId(), image.streamId());
    }

    public void cleanupImage(final PublicationImage image)
    {
        logger.logImageRemoval(
            image.channelUriString(), image.sessionId(), image.streamId(), image.correlationId());

        subscriptionLinks.stream()
            .filter((link) -> image.matches(link.channelEndpoint(), link.streamId()))
            .forEach((subscriptionLink) -> subscriptionLink.removeImage(image));
    }

    private List<SubscriberPosition> listSubscriberPositions(
        final int sessionId,
        final int streamId,
        final ReceiveChannelEndpoint channelEndpoint,
        final String channel,
        final long joiningPosition)
    {
        return subscriptionLinks
            .stream()
            .filter((subscription) -> subscription.matches(channelEndpoint, streamId))
            .map(
                (subscription) ->
                {
                    final Position position = newPosition(
                        "subscriber pos", channel, sessionId, streamId, subscription.registrationId());

                    position.setOrdered(joiningPosition);

                    return new SubscriberPosition(subscription, position);
                })
            .collect(toList());
    }

    private <T extends DriverManagedResource> void onCheckManagedResources(final ArrayList<T> list, final long time)
    {
        for (int i = list.size() - 1; i >= 0; i--)
        {
            final DriverManagedResource resource = list.get(i);

            resource.onTimeEvent(time, this);

            if (resource.hasReachedEndOfLife())
            {
                list.remove(i);
                resource.delete();
            }
        }
    }

    private void onHeartbeatCheckTimeouts(final long nanoTimeNow)
    {
        toDriverCommands.consumerHeartbeatTime(epochClock.time());

        onCheckManagedResources(clients, nanoTimeNow);
        onCheckManagedResources(publicationLinks, nanoTimeNow);
        onCheckManagedResources(networkPublications, nanoTimeNow);
        onCheckManagedResources(subscriptionLinks, nanoTimeNow);
        onCheckManagedResources(publicationImages, nanoTimeNow);
        onCheckManagedResources(directPublications, nanoTimeNow);
    }

    private void onClientCommand(final int msgTypeId, final MutableDirectBuffer buffer, final int index, final int length)
    {
        CorrelatedMessageFlyweight flyweight = null;

        try
        {
            switch (msgTypeId)
            {
                case ADD_PUBLICATION:
                {
                    logger.log(EventCode.CMD_IN_ADD_PUBLICATION, buffer, index, length);

                    final PublicationMessageFlyweight publicationMessageFlyweight = publicationMsgFlyweight;
                    publicationMessageFlyweight.offset(index);
                    flyweight = publicationMessageFlyweight;

                    final String channel = publicationMessageFlyweight.channel();
                    final int streamId = publicationMessageFlyweight.streamId();
                    final long correlationId = publicationMessageFlyweight.correlationId();
                    final long clientId = publicationMessageFlyweight.clientId();

                    if (CommonContext.IPC_CHANNEL.equals(channel))
                    {
                        onAddDirectPublication(streamId, correlationId, clientId);
                    }
                    else
                    {
                        onAddNetworkPublication(channel, streamId, correlationId, clientId);
                    }
                    break;
                }

                case REMOVE_PUBLICATION:
                {
                    logger.log(EventCode.CMD_IN_REMOVE_PUBLICATION, buffer, index, length);

                    final RemoveMessageFlyweight removeMessageFlyweight = removeMsgFlyweight;
                    removeMessageFlyweight.offset(index);
                    flyweight = removeMessageFlyweight;
                    onRemovePublication(removeMessageFlyweight.registrationId(), removeMessageFlyweight.correlationId());
                    break;
                }

                case ADD_SUBSCRIPTION:
                {
                    logger.log(EventCode.CMD_IN_ADD_SUBSCRIPTION, buffer, index, length);

                    final SubscriptionMessageFlyweight subscriptionMessageFlyweight = subscriptionMsgFlyweight;
                    subscriptionMessageFlyweight.offset(index);
                    flyweight = subscriptionMessageFlyweight;

                    final String channel = subscriptionMessageFlyweight.channel();
                    final int streamId = subscriptionMessageFlyweight.streamId();
                    final long correlationId = subscriptionMessageFlyweight.correlationId();
                    final long clientId = subscriptionMessageFlyweight.clientId();

                    if (CommonContext.IPC_CHANNEL.equals(channel))
                    {
                        onAddDirectPublicationSubscription(streamId, correlationId, clientId);
                    }
                    else
                    {
                        onAddNetworkPublicationSubscription(channel, streamId, correlationId, clientId);
                    }
                    break;
                }

                case REMOVE_SUBSCRIPTION:
                {
                    logger.log(EventCode.CMD_IN_REMOVE_SUBSCRIPTION, buffer, index, length);

                    final RemoveMessageFlyweight removeMessageFlyweight = removeMsgFlyweight;
                    removeMessageFlyweight.offset(index);
                    flyweight = removeMessageFlyweight;
                    onRemoveSubscription(removeMessageFlyweight.registrationId(), removeMessageFlyweight.correlationId());
                    break;
                }

                case CLIENT_KEEPALIVE:
                {
                    logger.log(EventCode.CMD_IN_KEEPALIVE_CLIENT, buffer, index, length);

                    final CorrelatedMessageFlyweight correlatedMessageFlyweight = correlatedMsgFlyweight;
                    correlatedMessageFlyweight.offset(index);
                    flyweight = correlatedMessageFlyweight;
                    onClientKeepalive(correlatedMessageFlyweight.clientId());
                    break;
                }
            }
        }
        catch (final ControlProtocolException ex)
        {
            clientProxy.onError(ex.errorCode(), ex.getMessage(), flyweight);
            logger.logException(ex);
        }
        catch (final Exception ex)
        {
            clientProxy.onError(GENERIC_ERROR, ex.getMessage(), flyweight);
            logger.logException(ex);
        }
    }

    private int processTimers(final long now)
    {
        int workCount = 0;

        if (now > (timeOfLastTimeoutCheck + HEARTBEAT_TIMEOUT_NS))
        {
            onHeartbeatCheckTimeouts(now);
            timeOfLastTimeoutCheck = now;
            workCount = 1;
        }

        return workCount;
    }

    private void onAddNetworkPublication(
        final String channel,
        final int streamId,
        final long registrationId,
        final long clientId)
    {
        final UdpChannel udpChannel = UdpChannel.parse(channel);
        final SendChannelEndpoint channelEndpoint = getOrCreateSendChannelEndpoint(udpChannel);

        NetworkPublication publication = channelEndpoint.getPublication(streamId);
        if (null == publication)
        {
            final int sessionId = nextSessionId + nextSessionId();
            final int initialTermId = BitUtil.generateRandomisedId();

            final RetransmitHandler retransmitHandler = new RetransmitHandler(
                nanoClock,
                systemCounters,
                RETRANSMIT_UNICAST_DELAY_GENERATOR,
                RETRANSMIT_UNICAST_LINGER_GENERATOR,
                initialTermId,
                termBufferLength);

            publication = new NetworkPublication(
                channelEndpoint,
                nanoClock,
                newNetworkPublicationLog(sessionId, streamId, initialTermId, udpChannel, registrationId),
                newPosition("sender pos", channel, sessionId, streamId, registrationId),
                newPosition("publisher limit", channel, sessionId, streamId, registrationId),
                sessionId,
                streamId,
                initialTermId,
                mtuLength,
                systemCounters,
                udpChannel.isMulticast() ? multicastFlowControl.get() : unicastFlowControl.get(),
                retransmitHandler);

            channelEndpoint.addPublication(publication);
            networkPublications.add(publication);
            senderProxy.newNetworkPublication(publication);
        }

        linkPublication(registrationId, publication, getOrAddClient(clientId));
        publication.incRef();

        clientProxy.onPublicationReady(
            streamId,
            publication.sessionId(),
            publication.rawLog(),
            registrationId,
            publication.publisherLimitId());
    }

    private void onAddDirectPublication(final int streamId, final long registrationId, final long clientId)
    {
        final DirectPublication directPublication = getOrAddDirectPublication(streamId);
        final AeronClient client = getOrAddClient(clientId);

        final PublicationLink publicationLink = new PublicationLink(registrationId, directPublication, client);
        publicationLinks.add(publicationLink);

        clientProxy.onPublicationReady(
            streamId,
            directPublication.sessionId(),
            directPublication.rawLog(),
            registrationId,
            directPublication.publisherLimitId());
    }

    private int nextSessionId()
    {
        return ++nextSessionId;
    }

    private void linkPublication(final long registrationId, final NetworkPublication publication, final AeronClient client)
    {
        if (null != findPublicationLink(publicationLinks, registrationId))
        {
            throw new ControlProtocolException(GENERIC_ERROR, "registration id already in use.");
        }

        publicationLinks.add(new PublicationLink(registrationId, publication, client));
    }

    private RawLog newNetworkPublicationLog(
        final int sessionId, final int streamId, final int initialTermId, final UdpChannel udpChannel, final long registrationId)
    {
        final String canonicalForm = udpChannel.canonicalForm();
        final RawLog rawLog = rawLogFactory.newPublication(canonicalForm, sessionId, streamId, registrationId);

        final MutableDirectBuffer header = DataHeaderFlyweight.createDefaultHeader(sessionId, streamId, initialTermId);
        final UnsafeBuffer logMetaData = rawLog.logMetaData();
        LogBufferDescriptor.storeDefaultFrameHeaders(logMetaData, header);
        LogBufferDescriptor.initialTermId(logMetaData, initialTermId);
        LogBufferDescriptor.activeTermId(logMetaData, initialTermId);
        LogBufferDescriptor.mtuLength(logMetaData, mtuLength);

        return rawLog;
    }

    private RawLog newPublicationImageLog(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final int termBufferLength,
        final int senderMtuLength,
        final UdpChannel udpChannel,
        final long correlationId)
    {
        final String canonicalForm = udpChannel.canonicalForm();
        final RawLog rawLog = rawLogFactory.newImage(canonicalForm, sessionId, streamId, correlationId, termBufferLength);

        final MutableDirectBuffer header = DataHeaderFlyweight.createDefaultHeader(sessionId, streamId, initialTermId);
        final UnsafeBuffer logMetaData = rawLog.logMetaData();
        LogBufferDescriptor.storeDefaultFrameHeaders(logMetaData, header);
        LogBufferDescriptor.initialTermId(logMetaData, initialTermId);
        LogBufferDescriptor.activeTermId(logMetaData, initialTermId);
        LogBufferDescriptor.mtuLength(logMetaData, senderMtuLength);

        return rawLog;
    }

    private RawLog newDirectPublicationLog(
        final int sessionId, final int streamId, final int initialTermId, final long registrationId)
    {
        final RawLog rawLog = rawLogFactory.newShared(sessionId, streamId, registrationId);

        final MutableDirectBuffer header = DataHeaderFlyweight.createDefaultHeader(sessionId, streamId, initialTermId);
        final UnsafeBuffer logMetaData = rawLog.logMetaData();
        LogBufferDescriptor.storeDefaultFrameHeaders(logMetaData, header);
        LogBufferDescriptor.initialTermId(logMetaData, initialTermId);
        LogBufferDescriptor.activeTermId(logMetaData, initialTermId);

        final int mtuLength = FrameDescriptor.computeMaxMessageLength(termBufferLength);
        LogBufferDescriptor.mtuLength(logMetaData, mtuLength);

        return rawLog;
    }

    private SendChannelEndpoint getOrCreateSendChannelEndpoint(final UdpChannel udpChannel)
    {
        SendChannelEndpoint channelEndpoint = sendChannelEndpointByChannelMap.get(udpChannel.canonicalForm());
        if (null == channelEndpoint)
        {
            logger.logChannelCreated(udpChannel.description());

            channelEndpoint = new SendChannelEndpoint(
                udpChannel,
                logger,
                controlLossGenerator,
                systemCounters);

            sendChannelEndpointByChannelMap.put(udpChannel.canonicalForm(), channelEndpoint);
            senderProxy.registerSendChannelEndpoint(channelEndpoint);
        }

        return channelEndpoint;
    }

    private void onRemovePublication(final long registrationId, final long correlationId)
    {
        PublicationLink publicationLink = null;
        final ArrayList<PublicationLink> publicationLinks = this.publicationLinks;
        for (int i = 0, size = publicationLinks.size(); i < size; i++)
        {
            final PublicationLink link = publicationLinks.get(i);
            if (registrationId == link.registrationId())
            {
                publicationLink = link;
                publicationLinks.remove(i);
                break;
            }
        }

        if (null == publicationLink)
        {
            throw new ControlProtocolException(UNKNOWN_PUBLICATION, "Unknown publication: " + registrationId);
        }

        publicationLink.close();

        clientProxy.operationSucceeded(correlationId);
    }

    private void onAddNetworkPublicationSubscription(
        final String channel,
        final int streamId,
        final long registrationId,
        final long clientId)
    {
        final ReceiveChannelEndpoint channelEndpoint = getOrCreateReceiveChannelEndpoint(UdpChannel.parse(channel));

        final int refCount = channelEndpoint.incRefToStream(streamId);
        if (1 == refCount)
        {
            receiverProxy.addSubscription(channelEndpoint, streamId);
        }

        final AeronClient client = getOrAddClient(clientId);
        final SubscriptionLink subscription = new SubscriptionLink(registrationId, channelEndpoint, streamId, client);

        subscriptionLinks.add(subscription);
        clientProxy.operationSucceeded(registrationId);

        publicationImages
            .stream()
            .filter((image) -> image.matches(channelEndpoint, streamId) && (image.subscriberCount() > 0))
            .forEach(
                (image) ->
                {
                    final Position position = newPosition(
                        "subscriber pos", channel, image.sessionId(), streamId, registrationId);

                    image.addSubscriber(position);
                    subscription.addImage(image, position);

                    clientProxy.onImageReady(
                        streamId,
                        image.sessionId(),
                        image.rebuildPosition(),
                        image.rawLog(),
                        image.correlationId(),
                        Collections.singletonList(new SubscriberPosition(subscription, position)),
                        generateSourceIdentity(image.sourceAddress()));
                });
    }

    private void onAddDirectPublicationSubscription(
        final int streamId,
        final long registrationId,
        final long clientId)
    {
        final DirectPublication directPublication = getOrAddDirectPublication(streamId);
        final AeronClient client = getOrAddClient(clientId);

        final Position position = newPosition(
            "subscriber pos", CommonContext.IPC_CHANNEL, directPublication.sessionId(), streamId, registrationId);

        final SubscriptionLink subscriptionLink = new SubscriptionLink(
            registrationId, streamId, directPublication, position, client);

        subscriptionLinks.add(subscriptionLink);
        directPublication.addSubscription(position);

        clientProxy.operationSucceeded(registrationId);

        final List<SubscriberPosition> subscriberPositions = new ArrayList<>();
        subscriberPositions.add(new SubscriberPosition(subscriptionLink, position));

        clientProxy.onImageReady(
            streamId,
            directPublication.sessionId(),
            directPublication.joiningPosition(),
            directPublication.rawLog(),
            directPublication.correlationId(),
            subscriberPositions,
            CommonContext.IPC_CHANNEL);
    }

    private ReceiveChannelEndpoint getOrCreateReceiveChannelEndpoint(final UdpChannel udpChannel)
    {
        ReceiveChannelEndpoint channelEndpoint = receiveChannelEndpointByChannelMap.get(udpChannel.canonicalForm());
        if (null == channelEndpoint)
        {
            channelEndpoint = new ReceiveChannelEndpoint(
                udpChannel,
                new DataPacketDispatcher(fromReceiverConductorProxy, receiverProxy.receiver()),
                logger,
                systemCounters,
                dataLossGenerator);

            receiveChannelEndpointByChannelMap.put(udpChannel.canonicalForm(), channelEndpoint);
            receiverProxy.registerReceiveChannelEndpoint(channelEndpoint);
        }

        return channelEndpoint;
    }

    private void onRemoveSubscription(final long registrationId, final long correlationId)
    {
        final SubscriptionLink link = removeSubscriptionLink(subscriptionLinks, registrationId);
        if (null == link)
        {
            throw new ControlProtocolException(UNKNOWN_SUBSCRIPTION, "Unknown subscription link: " + registrationId);
        }

        link.close();
        final ReceiveChannelEndpoint channelEndpoint = link.channelEndpoint();

        if (null != channelEndpoint)
        {
            final int refCount = channelEndpoint.decRefToStream(link.streamId());
            if (0 == refCount)
            {
                receiverProxy.removeSubscription(channelEndpoint, link.streamId());
            }

            if (0 == channelEndpoint.streamCount())
            {
                receiveChannelEndpointByChannelMap.remove(channelEndpoint.udpChannel().canonicalForm());
                receiverProxy.closeReceiveChannelEndpoint(channelEndpoint);

                while (!channelEndpoint.isClosed())
                {
                    Thread.yield();
                }
            }
        }

        clientProxy.operationSucceeded(correlationId);
    }

    private void onClientKeepalive(final long clientId)
    {
        systemCounters.clientKeepAlives().addOrdered(1);

        final AeronClient client = findClient(clients, clientId);
        if (null != client)
        {
            client.timeOfLastKeepalive(nanoClock.nanoTime());
        }
    }

    private void onDriverConductorCmd(final DriverConductorCmd cmd)
    {
        cmd.execute(this);
    }

    private AeronClient getOrAddClient(final long clientId)
    {
        AeronClient client = findClient(clients, clientId);
        if (null == client)
        {
            client = new AeronClient(clientId, clientLivenessTimeoutNs, nanoClock.nanoTime());
            clients.add(client);
        }

        return client;
    }

    private DirectPublication getOrAddDirectPublication(final int streamId)
    {
        DirectPublication directPublication = findDirectPublication(directPublications, streamId);

        if (null == directPublication)
        {
            final long imageCorrelationId = nextImageCorrelationId();
            final int sessionId = nextSessionId + nextSessionId();
            final int initialTermId = BitUtil.generateRandomisedId();
            final RawLog rawLog = newDirectPublicationLog(sessionId, streamId, initialTermId, imageCorrelationId);

            final Position publisherLimit =
                newPosition("publisher limit", CommonContext.IPC_CHANNEL, sessionId, streamId, imageCorrelationId);

            directPublication = new DirectPublication(imageCorrelationId, sessionId, streamId, publisherLimit, rawLog);

            directPublications.add(directPublication);
        }

        return directPublication;
    }

    private Position newPosition(
        final String name, final String channel, final int sessionId, final int streamId, final long correlationId)
    {
        final int positionId = allocateCounter(name, channel, sessionId, streamId, correlationId);
        return new UnsafeBufferPosition(countersBuffer, positionId, countersManager);
    }

    private int allocateCounter(
        final String type, final String channel, final int sessionId, final int streamId, final long correlationId)
    {
        return countersManager.allocate(String.format("%s: %s %d %d %d", type, channel, sessionId, streamId, correlationId));
    }

    private long nextImageCorrelationId()
    {
        return toDriverCommands.nextCorrelationId();
    }

    private static AeronClient findClient(final ArrayList<AeronClient> clients, final long clientId)
    {
        AeronClient aeronClient = null;

        for (int i = 0, size = clients.size(); i < size; i++)
        {
            final AeronClient client = clients.get(i);
            if (client.clientId() == clientId)
            {
                aeronClient = client;
                break;
            }
        }

        return aeronClient;
    }

    private static PublicationLink findPublicationLink(
        final ArrayList<PublicationLink> publicationLinks, final long registrationId)
    {
        PublicationLink publicationLink = null;

        for (int i = 0, size = publicationLinks.size(); i < size; i++)
        {
            final PublicationLink link = publicationLinks.get(i);
            if (registrationId == link.registrationId())
            {
                publicationLink = link;
                break;
            }
        }

        return publicationLink;
    }

    private static SubscriptionLink removeSubscriptionLink(
        final ArrayList<SubscriptionLink> subscriptionLinks, final long registrationId)
    {
        SubscriptionLink subscriptionLink = null;

        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink link = subscriptionLinks.get(i);
            if (link.registrationId() == registrationId)
            {
                subscriptionLink = link;
                subscriptionLinks.remove(i);
                break;
            }
        }

        return subscriptionLink;
    }

    private static DirectPublication findDirectPublication(
        final ArrayList<DirectPublication> directPublications, final long streamId)
    {
        DirectPublication directPublication = null;

        for (int i = 0, size = directPublications.size(); i < size; i++)
        {
            final DirectPublication log = directPublications.get(i);
            if (log.streamId() == streamId)
            {
                directPublication = log;
                break;
            }
        }

        return directPublication;
    }

    private static String generateSourceIdentity(final InetSocketAddress address)
    {
        return String.format("%s:%d", address.getHostString(), address.getPort());
    }
}

/*
 * Copyright 2009 Thomas Bocek
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package net.tomp2p.rpc;

import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

import net.tomp2p.connection.ChannelCreator;
import net.tomp2p.connection.ConnectionBean;
import net.tomp2p.connection.PeerBean;
import net.tomp2p.connection.PeerConnection;
import net.tomp2p.connection.RequestHandler;
import net.tomp2p.connection.Responder;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.message.DataMap;
import net.tomp2p.message.KeyCollection;
import net.tomp2p.message.KeyMap640;
import net.tomp2p.message.KeyMapByte;
import net.tomp2p.message.Message;
import net.tomp2p.message.Message.Type;
import net.tomp2p.p2p.builder.AddBuilder;
import net.tomp2p.p2p.builder.DigestBuilder;
import net.tomp2p.p2p.builder.GetBuilder;
import net.tomp2p.p2p.builder.PutBuilder;
import net.tomp2p.p2p.builder.RemoveBuilder;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number320;
import net.tomp2p.peers.Number640;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.storage.Data;
import net.tomp2p.storage.StorageLayer.PutStatus;
import net.tomp2p.utils.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RPC that deals with storage.
 * 
 * @author Thomas Bocek
 * 
 */
public class StorageRPC extends DispatchHandler {
    private static final Logger LOG = LoggerFactory.getLogger(StorageRPC.class);
    private static final Random RND = new Random();

    public static final byte PUT_COMMAND = 1;
    public static final byte GET_COMMAND = 2;
    public static final byte ADD_COMMAND = 3;
    public static final byte REMOVE_COMMAND = 4;
    public static final byte DIGEST_COMMAND = 11;

    private final BloomfilterFactory factory;

    /**
     * Register the store rpc for put, compare put, get, add, and remove.
     * 
     * @param peerBean
     *            The peer bean
     * @param connectionBean
     *            The connection bean
     */
    public StorageRPC(final PeerBean peerBean, final ConnectionBean connectionBean) {
        super(peerBean, connectionBean, PUT_COMMAND, GET_COMMAND, ADD_COMMAND, REMOVE_COMMAND, DIGEST_COMMAND);
        this.factory = peerBean.bloomfilterFactory();
    }

    /**
     * Stores data on a remote peer. Overwrites data if the data already exists. This is an RPC.
     * 
     * @param remotePeer
     *            The remote peer to store the data
     * @param locationKey
     *            The location of the data
     * @param domainKey
     *            The domain of the data
     * @param dataMap
     *            The map with the content key and data
     * @param protectDomain
     *            Set to true if the domain should be set to protected. This means that this domain is flagged an a
     *            public key is stored for this entry. An update or removal can only be made with the matching private
     *            key.
     * @param protectEntry
     *            Set to true if the entry should be set to protected. This means that this domain is flagged an a
     *            public key is stored for this entry. An update or removal can only be made with the matching private
     *            key.
     * @param signMessage
     *            Set to true if the message should be signed. For protecting an entry, this needs to be set to true.
     * @param channelCreator
     *            The channel creator
     * @param forceUDP
     *            Set to true if the communication should be UDP, default is TCP
     * @return FutureResponse that stores which content keys have been stored.
     */
    public FutureResponse put(final PeerAddress remotePeer, final PutBuilder putBuilder,
            final ChannelCreator channelCreator) {
        final Type request = putBuilder.isProtectDomain() ? Type.REQUEST_2 : Type.REQUEST_1;
        return put(remotePeer, putBuilder, request, channelCreator);
    }

    /**
     * Stores data on a remote peer. Only stores data if the data does not already exist. This is an RPC.
     * 
     * @param remotePeer
     *            The remote peer to store the data
     * @param locationKey
     *            The location of the data
     * @param domainKey
     *            The domain of the data
     * @param dataMap
     *            The map with the content key and data
     * @param protectDomain
     *            Set to true if the domain should be set to protected. This means that this domain is flagged an a
     *            public key is stored for this entry. An update or removal can only be made with the matching private
     *            key.
     * @param protectEntry
     *            Set to true if the entry should be set to protected. This means that this domain is flagged an a
     *            public key is stored for this entry. An update or removal can only be made with the matching private
     *            key.
     * @param signMessage
     *            Set to true if the message should be signed. For protecting an entry, this needs to be set to true.
     * @param channelCreator
     *            The channel creator
     * @param forceUDP
     *            Set to true if the communication should be UDP, default is TCP
     * @return FutureResponse that stores which content keys have been stored.
     */
    public FutureResponse putIfAbsent(final PeerAddress remotePeer, final PutBuilder putBuilder,
            final ChannelCreator channelCreator) {
        final Type request;
        if (putBuilder.isProtectDomain()) {
            request = Type.REQUEST_4;
        } else {
            request = Type.REQUEST_3;
        }
        return put(remotePeer, putBuilder, request, channelCreator);
    }

    /**
     * Stores the data either via put or putIfAbsent. This is an RPC.
     * 
     * @param remotePeer
     *            The remote peer to store the data
     * @param locationKey
     *            The location key
     * @param domainKey
     *            The domain key
     * @param dataMap
     *            The map with the content key and data
     * @param type
     *            The type of put request, this depends on put/putIfAbsent/protected/not-protected
     * @param signMessage
     *            Set to true to sign message
     * @param channelCreator
     *            The channel creator
     * @param forceUDP
     *            Set to true if the communication should be UDP, default is TCP
     * @return FutureResponse that stores which content keys have been stored.
     */
    /*
     * private FutureResponse put(final PeerAddress remotePeer, final Number160 locationKey, final Number160 domainKey,
     * final Map<Number160, Data> dataMap, final Type type, boolean signMessage, ChannelCreator channelCreator, boolean
     * forceUDP, SenderCacheStrategy senderCacheStrategy) {
     */
    private FutureResponse put(final PeerAddress remotePeer, final PutBuilder putBuilder, final Type type,
            final ChannelCreator channelCreator) {

        Utils.nullCheck(remotePeer);

        final DataMap dataMap;
        if (putBuilder.getDataMap() != null) {
            dataMap = new DataMap(putBuilder.getDataMap());
        } else {
            dataMap = new DataMap(putBuilder.getLocationKey(), putBuilder.getDomainKey(),
                    putBuilder.getVersionKey(), putBuilder.getDataMapContent());
        }

        final Message message = createMessage(remotePeer, PUT_COMMAND, type);

        if (putBuilder.isSignMessage()) {
            message.setPublicKeyAndSign(peerBean().getKeyPair());
        }

        message.setDataMap(dataMap);

        final FutureResponse futureResponse = new FutureResponse(message);
        final RequestHandler<FutureResponse> request = new RequestHandler<FutureResponse>(futureResponse,
                peerBean(), connectionBean(), putBuilder);

        if (!putBuilder.isForceUDP()) {
            return request.sendTCP(channelCreator);
        } else {
            return request.sendUDP(channelCreator);
        }

    }

    /**
     * Adds data on a remote peer. The main difference to
     * {@link #put(PeerAddress, Number160, Number160, Map, Type, boolean, ChannelCreator, boolean)} and
     * {@link #putIfAbsent(PeerAddress, Number160, Number160, Map, boolean, boolean, boolean, ChannelCreator, boolean)}
     * is that it will convert the data collection to map. The key for the map will be the SHA-1 hash of the data. This
     * is an RPC.
     * 
     * @param remotePeer
     *            The remote peer to store the data
     * @param locationKey
     *            The location key
     * @param domainKey
     *            The domain key
     * @param dataSet
     *            The set with data. This will be converted to a map. The key for the map is the SHA-1 of the data.
     * @param protectDomain
     *            Set to true if the domain should be set to protected. This means that this domain is flagged an a
     *            public key is stored for this entry. An update or removal can only be made with the matching private
     *            key.
     * @param signMessage
     *            Set to true if the message should be signed. For protecting an entry, this needs to be set to true.
     * @param channelCreator
     *            The channel creator
     * @param forceUDP
     *            Set to true if the communication should be UDP, default is TCP
     * @return FutureResponse that stores which content keys have been stored.
     */
    public FutureResponse add(final PeerAddress remotePeer, final AddBuilder addBuilder,
            ChannelCreator channelCreator) {
        Utils.nullCheck(remotePeer, addBuilder.getLocationKey(), addBuilder.getDomainKey());
        final Type type;
        if (addBuilder.isProtectDomain()) {
            if (addBuilder.isList()) {
                type = Type.REQUEST_4;
            } else {
                type = Type.REQUEST_2;
            }
        } else {
            if (addBuilder.isList()) {
                type = Type.REQUEST_3;
            } else {
                type = Type.REQUEST_1;
            }
        }

        // convert the data
        Map<Number160, Data> dataMap = new HashMap<Number160, Data>(addBuilder.getDataSet().size());
        if (addBuilder.getDataSet() != null) {
            for (Data data : addBuilder.getDataSet()) {
                if (addBuilder.isList()) {
                    Number160 hash = new Number160(addBuilder.random());
                    while (dataMap.containsKey(hash)) {
                        hash = new Number160(addBuilder.random());
                    }
                    dataMap.put(hash, data);
                } else {
                    dataMap.put(data.hash(), data);
                }
            }
        }

        final Message message = createMessage(remotePeer, ADD_COMMAND, type);

        if (addBuilder.isSignMessage()) {
            message.setPublicKeyAndSign(peerBean().getKeyPair());
        }

        message.setDataMap(new DataMap(addBuilder.getLocationKey(), addBuilder.getDomainKey(), addBuilder
                .getVersionKey(), dataMap));

        final FutureResponse futureResponse = new FutureResponse(message);
        final RequestHandler<FutureResponse> request = new RequestHandler<FutureResponse>(futureResponse,
                peerBean(), connectionBean(), addBuilder);
        if (!addBuilder.isForceUDP()) {
            return request.sendTCP(channelCreator);
        } else {
            return request.sendUDP(channelCreator);
        }

    }

    //TODO: clean up DRY
    public FutureResponse digest(final PeerAddress remotePeer, final DigestBuilder getBuilder,
            final ChannelCreator channelCreator) {
        Type type;
        if (getBuilder.isAscending() && !getBuilder.isReturnBloomFilter()) {
            type = Type.REQUEST_1;
        } else if (getBuilder.isAscending() && getBuilder.isReturnBloomFilter()) {
            type = Type.REQUEST_2;
        } else if (getBuilder.isDescending() && !getBuilder.isReturnBloomFilter()) {
            type = Type.REQUEST_3;
        } else {
            type = Type.REQUEST_4;
        }
        final Message message = createMessage(remotePeer, DIGEST_COMMAND, type);

        if (getBuilder.isSignMessage()) {
            message.setPublicKeyAndSign(peerBean().getKeyPair());
        }

        if (getBuilder.to() != null && getBuilder.from() != null) {
            final Collection<Number640> keys = new ArrayList<Number640>(2);
            keys.add(getBuilder.from());
            keys.add(getBuilder.to());
            message.setInteger(getBuilder.returnNr());
            message.setKeyCollection(new KeyCollection(keys));
        } else if (getBuilder.keys() == null) {

            if (getBuilder.getLocationKey() == null || getBuilder.getDomainKey() == null) {
                throw new IllegalArgumentException("Null not allowed in location or domain");
            }
            message.setKey(getBuilder.getLocationKey());
            message.setKey(getBuilder.getDomainKey());

            if (getBuilder.getContentKeys() != null) {
                message.setKeyCollection(new KeyCollection(getBuilder.getLocationKey(), getBuilder
                        .getDomainKey(), getBuilder.getVersionKey(), getBuilder.getContentKeys()));
            } else {
                message.setInteger(getBuilder.returnNr());
                if (getBuilder.getKeyBloomFilter() != null || getBuilder.getContentBloomFilter() != null) {
                    if (getBuilder.getKeyBloomFilter() != null) {
                        message.setBloomFilter(getBuilder.getKeyBloomFilter());
                    }
                    if (getBuilder.getContentBloomFilter() != null) {
                        message.setBloomFilter(getBuilder.getContentBloomFilter());
                    }
                }
            }
        } else {
            message.setKeyCollection(new KeyCollection(getBuilder.keys()));
        }

        final FutureResponse futureResponse = new FutureResponse(message);
        final RequestHandler<FutureResponse> request = new RequestHandler<FutureResponse>(futureResponse,
                peerBean(), connectionBean(), getBuilder);
        if (!getBuilder.isForceUDP()) {
            return request.sendTCP(channelCreator);
        } else {
            return request.sendUDP(channelCreator);
        }
    }

    public FutureResponse get(final PeerAddress remotePeer, final GetBuilder getBuilder,
            final ChannelCreator channelCreator) {
        Type type;
        if (getBuilder.isAscending() && !getBuilder.isReturnBloomFilter()) {
            type = Type.REQUEST_1;
        } else if (getBuilder.isAscending() && getBuilder.isReturnBloomFilter()) {
            type = Type.REQUEST_2;
        } else if (getBuilder.isDescending() && !getBuilder.isReturnBloomFilter()) {
            type = Type.REQUEST_3;
        } else {
            type = Type.REQUEST_4;
        }
        final Message message = createMessage(remotePeer, GET_COMMAND, type);

        if (getBuilder.isSignMessage()) {
            message.setPublicKeyAndSign(peerBean().getKeyPair());
        }

        if (getBuilder.to() != null && getBuilder.from() != null) {
            final Collection<Number640> keys = new ArrayList<Number640>(2);
            keys.add(getBuilder.from());
            keys.add(getBuilder.to());
            message.setInteger(getBuilder.returnNr());
            message.setKeyCollection(new KeyCollection(keys));
        } else if (getBuilder.keys() == null) {

            if (getBuilder.getLocationKey() == null || getBuilder.getDomainKey() == null) {
                throw new IllegalArgumentException("Null not allowed in location or domain");
            }
            message.setKey(getBuilder.getLocationKey());
            message.setKey(getBuilder.getDomainKey());

            if (getBuilder.getContentKeys() != null) {
                message.setKeyCollection(new KeyCollection(getBuilder.getLocationKey(), getBuilder
                        .getDomainKey(), getBuilder.getVersionKey(), getBuilder.getContentKeys()));
            } else {
                message.setInteger(getBuilder.returnNr());
                if (getBuilder.getKeyBloomFilter() != null || getBuilder.getContentBloomFilter() != null) {
                    if (getBuilder.getKeyBloomFilter() != null) {
                        message.setBloomFilter(getBuilder.getKeyBloomFilter());
                    }
                    if (getBuilder.getContentBloomFilter() != null) {
                        message.setBloomFilter(getBuilder.getContentBloomFilter());
                    }
                }
            }
        } else {
            message.setKeyCollection(new KeyCollection(getBuilder.keys()));
        }

        final FutureResponse futureResponse = new FutureResponse(message);
        final RequestHandler<FutureResponse> request = new RequestHandler<FutureResponse>(futureResponse,
                peerBean(), connectionBean(), getBuilder);
        if (!getBuilder.isForceUDP()) {
            return request.sendTCP(channelCreator);
        } else {
            return request.sendUDP(channelCreator);
        }
    }

    /**
     * Removes data from a peer. This is an RPC.
     * 
     * @param remotePeer
     *            The remote peer to send this request
     * @param locationKey
     *            The location key
     * @param domainKey
     *            The domain key
     * @param contentKeys
     *            The content keys or null if requested all
     * @param sendBackResults
     *            Set to true if the removed data should be sent back
     * @param signMessage
     *            Adds a public key and signs the message. For protected entry and domains, this needs to be provided.
     * @param channelCreator
     *            The channel creator that creates connections
     * @param forceUDP
     *            Set to true if the communication should be UDP, default is TCP
     * @return The future response to keep track of future events
     */
    public FutureResponse remove(final PeerAddress remotePeer, final RemoveBuilder removeBuilder,
            final ChannelCreator channelCreator) {
        final Message message = createMessage(remotePeer, REMOVE_COMMAND,
                removeBuilder.isReturnResults() ? Type.REQUEST_2 : Type.REQUEST_1);

        if (removeBuilder.isSignMessage()) {
            message.setPublicKeyAndSign(peerBean().getKeyPair());
        }

        if (removeBuilder.getKeys() == null) {

            if (removeBuilder.getLocationKey() == null || removeBuilder.getDomainKey() == null) {
                throw new IllegalArgumentException("Null not allowed in location or domain");
            }
            message.setKey(removeBuilder.getLocationKey());
            message.setKey(removeBuilder.getDomainKey());

            if (removeBuilder.getContentKeys() != null) {
                message.setKeyCollection(new KeyCollection(removeBuilder.getLocationKey(), removeBuilder
                        .getDomainKey(), removeBuilder.getVersionKey(), removeBuilder.getContentKeys()));
            }
        } else {
            message.setKeyCollection(new KeyCollection(removeBuilder.getKeys()));
        }

        final FutureResponse futureResponse = new FutureResponse(message);

        final RequestHandler<FutureResponse> request = new RequestHandler<FutureResponse>(futureResponse,
                peerBean(), connectionBean(), removeBuilder);
        if (!removeBuilder.isForceUDP()) {
            return request.sendTCP(channelCreator);
        } else {
            return request.sendUDP(channelCreator);
        }
    }

    @Override
    public void handleResponse(final Message message, PeerConnection peerConnection, final boolean sign,
            Responder responder) throws Exception {

        if (!(message.getCommand() == ADD_COMMAND || message.getCommand() == PUT_COMMAND
                || message.getCommand() == GET_COMMAND || message.getCommand() == REMOVE_COMMAND || message.getCommand() == DIGEST_COMMAND)) {
            throw new IllegalArgumentException("Message content is wrong");
        }
        final Message responseMessage = createResponseMessage(message, Type.OK);

        switch (message.getCommand()) {
        case ADD_COMMAND:
            handleAdd(message, responseMessage, isDomainProtected(message));
            break;
        case PUT_COMMAND:
            handlePut(message, responseMessage, isStoreIfAbsent(message), isDomainProtected(message));
            break;
        case GET_COMMAND:
            handleGet(message, responseMessage);
            break;
        case DIGEST_COMMAND:
            handleDigest(message, responseMessage);
            break;
        case REMOVE_COMMAND:
            handleRemove(message, responseMessage, message.getType() == Type.REQUEST_2);
            break;
        default:
            throw new IllegalArgumentException("Message content is wrong");
        }
        if (sign) {
            responseMessage.setPublicKeyAndSign(peerBean().getKeyPair());
        }
        responder.response(responseMessage);
    }

    private boolean isDomainProtected(final Message message) {
        boolean protectDomain = message.getPublicKey() != null
                && (message.getType() == Type.REQUEST_2 || message.getType() == Type.REQUEST_4);
        return protectDomain;
    }

    private boolean isStoreIfAbsent(final Message message) {
        boolean absent = message.getType() == Type.REQUEST_3 || message.getType() == Type.REQUEST_4;
        return absent;
    }

    private boolean isList(final Message message) {
        boolean partial = message.getType() == Type.REQUEST_3 || message.getType() == Type.REQUEST_4;
        return partial;
    }

    private boolean isAscending(final Message message) {
        boolean partial = message.getType() == Type.REQUEST_1 || message.getType() == Type.REQUEST_2;
        return partial;
    }

    private boolean isReturnBloomfilter(final Message message) {
        boolean partial = message.getType() == Type.REQUEST_2 || message.getType() == Type.REQUEST_4;
        return partial;
    }

    private Message handlePut(final Message message, final Message responseMessage,
            final boolean putIfAbsent, final boolean protectDomain) throws IOException {
        final PublicKey publicKey = message.getPublicKey();
        final DataMap toStore = message.getDataMap(0);
        final int dataSize = toStore.size();
        final Map<Number640, Byte> result = new HashMap<Number640, Byte>(dataSize);
        for (Map.Entry<Number640, Data> entry : toStore.dataMap().entrySet()) {
            Enum<?> putStatus = doPut(putIfAbsent, protectDomain, publicKey, entry.getKey(), entry.getValue());
            result.put(entry.getKey(), (byte) putStatus.ordinal());
            // check the responsibility of the newly added data, do something
            // (notify) if we are responsible
            if (putStatus == PutStatus.OK && peerBean().replicationStorage() != null) {
                peerBean().replicationStorage().updateAndNotifyResponsibilities(
                        entry.getKey().getLocationKey());
            }
        }

        responseMessage.setType(result.size() == dataSize ? Type.OK : Type.PARTIALLY_OK);
        responseMessage.setKeyMapByte(new KeyMapByte(result));
        return responseMessage;
    }

    private Message handleAdd(final Message message, final Message responseMessage,
            final boolean protectDomain) {

        Utils.nullCheck(message.getDataMap(0));

        final Map<Number640, Byte> result = new HashMap<Number640, Byte>();
        final DataMap dataMap = message.getDataMap(0);
        final PublicKey publicKey = message.getPublicKey();
        final boolean list = isList(message);
        // here we set the map with the close peers. If we get data by a
        // sender and the sender is closer than us, we assume that the sender has
        // the data and we don't need to transfer data to the closest (sender)
        // peer.

        for (Map.Entry<Number640, Data> entry : dataMap.dataMap().entrySet()) {
            Enum<?> status = doAdd(protectDomain, entry, publicKey, list, peerBean());
            result.put(entry.getKey(), (byte) status.ordinal());

            // check the responsibility of the newly added data, do something
            // (notify) if we are responsible
            if (status == PutStatus.OK && peerBean().replicationStorage() != null) {
                peerBean().replicationStorage().updateAndNotifyResponsibilities(
                        entry.getKey().getLocationKey());
            }

        }
        responseMessage.setKeyMapByte(new KeyMapByte(result));
        return responseMessage;
    }

    private Enum<?> doPut(final boolean putIfAbsent, final boolean protectDomain, final PublicKey publicKey,
            final Number640 key, final Data value) {
        LOG.debug("put data with key {} on {} with data {}", key, peerBean().serverPeerAddress(), value);
        return peerBean().storage().put(key, value, publicKey, putIfAbsent, protectDomain);
    }

    private static Enum<?> doAdd(final boolean protectDomain, final Map.Entry<Number640, Data> entry,
            final PublicKey publicKey, final boolean list, final PeerBean peerBean) {

        LOG.debug("add list data with key {} on {}", entry.getKey(), peerBean.serverPeerAddress());
        if (list) {
            Number160 contentKey2 = new Number160(RND);
            Enum<?> status;
            Number640 key = new Number640(entry.getKey().getLocationKey(), entry.getKey().getDomainKey(),
                    contentKey2, entry.getKey().getVersionKey());
            while ((status = peerBean.storage().put(key, entry.getValue(), publicKey, true, protectDomain)) == PutStatus.FAILED_NOT_ABSENT) {
                contentKey2 = new Number160(RND);
            }
            return status;
        } else {
            return peerBean.storage().put(entry.getKey(), entry.getValue(), publicKey, false, protectDomain);
        }
    }

    private Message handleGet(final Message message, final Message responseMessage) {
        final Number160 locationKey = message.getKey(0);
        LOG.debug("get data with key {} on {}", locationKey, peerBean().serverPeerAddress());
        final Number160 domainKey = message.getKey(1);
        final KeyCollection contentKeys = message.getKeyCollection(0);
        final SimpleBloomFilter<Number160> keyBloomFilter = message.getBloomFilter(0);
        final SimpleBloomFilter<Number160> contentBloomFilter = message.getBloomFilter(1);
        final Integer returnNr = message.getInteger(0);
        final int limit = returnNr == null ? -1 : returnNr;
        final boolean ascending = isAscending(message);
        final boolean isRange = contentKeys != null && returnNr != null;
        final boolean isCollection = contentKeys != null && returnNr == null;

        final Map<Number640, Data> result;
        if (isCollection) {
            result = new HashMap<Number640, Data>();
            for (Number640 key : contentKeys.keys()) {
                Data data = peerBean().storage().get(key);
                if (data != null) {
                    result.put(key, data);
                }
            }
        } else if (isRange) {
            // get min/max
            Iterator<Number640> iterator = contentKeys.keys().iterator();
            Number640 min = iterator.next();
            Number640 max = iterator.next();
            result = peerBean().storage().get(min, max, limit, ascending);

        } else if (keyBloomFilter != null || contentBloomFilter != null) {
            Number640 min = new Number640(locationKey, domainKey, Number160.ZERO, Number160.ZERO);
            Number640 max = new Number640(locationKey, domainKey, Number160.MAX_VALUE, Number160.MAX_VALUE);
            result = peerBean().storage().get(min, max, keyBloomFilter, contentBloomFilter, limit, ascending);
        } else {
            // get all
            Number640 min = new Number640(locationKey, domainKey, Number160.ZERO, Number160.ZERO);
            Number640 max = new Number640(locationKey, domainKey, Number160.MAX_VALUE, Number160.MAX_VALUE);
            result = peerBean().storage().get(min, max, limit, ascending);
        }
        responseMessage.setDataMap(new DataMap(result));
        return responseMessage;
    }

    private Message handleDigest(final Message message, final Message responseMessage) {

        final Number160 locationKey = message.getKey(0);
        LOG.debug("get data with key {} on {}", locationKey, peerBean().serverPeerAddress());
        final Number160 domainKey = message.getKey(1);
        final KeyCollection contentKeys = message.getKeyCollection(0);
        final SimpleBloomFilter<Number160> keyBloomFilter = message.getBloomFilter(0);
        final SimpleBloomFilter<Number160> contentBloomFilter = message.getBloomFilter(1);
        final Integer returnNr = message.getInteger(0);
        final int limit = returnNr == null ? -1 : returnNr;
        final boolean ascending = isAscending(message);
        final boolean isRange = contentKeys != null && returnNr != null;
        final boolean isCollection = contentKeys != null && returnNr == null;
        final boolean isReturnBloomfilter = isReturnBloomfilter(message);
        

        final DigestInfo digestInfo;
        if (isCollection) {
            digestInfo = peerBean().storage().digest(contentKeys.keys());
        } else if (isRange) {
            // get min/max
            Iterator<Number640> iterator = contentKeys.keys().iterator();
            Number640 min = iterator.next();
            Number640 max = iterator.next();
            digestInfo = peerBean().storage().digest(min, max, limit, ascending);
        } else if (keyBloomFilter != null || contentBloomFilter != null) {
            final Number320 locationAndDomainKey = new Number320(locationKey, domainKey);
            digestInfo = peerBean().storage().digest(locationAndDomainKey, keyBloomFilter,
                    contentBloomFilter, limit, ascending);
        } else {
            // get all
            Number640 min = new Number640(locationKey, domainKey, Number160.ZERO, Number160.ZERO);
            Number640 max = new Number640(locationKey, domainKey, Number160.MAX_VALUE, Number160.MAX_VALUE);
            digestInfo = peerBean().storage().digest(min, max, limit, ascending);
        }

        if (isReturnBloomfilter) {
            if (locationKey == null && domainKey == null) {
                responseMessage.setBloomFilter(digestInfo.getLocationKeyBloomFilter(factory));
                responseMessage.setBloomFilter(digestInfo.getDomainKeyBloomFilter(factory));
            }
            responseMessage.setBloomFilter(digestInfo.getContentKeyBloomFilter(factory));
            responseMessage.setBloomFilter(digestInfo.getVersionKeyBloomFilter(factory));
        } else {
            responseMessage.setKeyMap640(new KeyMap640(digestInfo.getDigests()));
        }
        return responseMessage;

    }

    private Message handleRemove(final Message message, final Message responseMessage,
            final boolean sendBackResults) {
        final Number160 locationKey = message.getKey(0);
        final Number160 domainKey = message.getKey(1);
        final KeyCollection keys = message.getKeyCollection(0);
        final PublicKey publicKey = message.getPublicKey();
        final Map<Number640, Data> result;
        if (keys != null) {
            result = new HashMap<Number640, Data>(keys.size());
            for (Number640 key : keys.keys()) {
                Data data = peerBean().storage().remove(key, publicKey);
                if (data != null) {
                    result.put(key, data);
                }
            }
        } else if (locationKey != null && domainKey != null) {
            Number640 min = new Number640(locationKey, domainKey, Number160.ZERO, Number160.ZERO);
            Number640 max = new Number640(locationKey, domainKey, Number160.MAX_VALUE, Number160.MAX_VALUE);
            result = peerBean().storage().remove(min, max, publicKey);
        } else {
            throw new IllegalArgumentException("Either two keys or a key set are necessary");
        }
        if (!sendBackResults) {
            // make a copy, so the iterator in the codec wont conflict with
            // concurrent calls
            responseMessage.setKeyCollection(new KeyCollection(result.keySet()));
        } else {
            // make a copy, so the iterator in the codec wont conflict with
            // concurrent calls
            responseMessage.setDataMap(new DataMap(result));
        }
        return responseMessage;
    }
}

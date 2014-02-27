package net.tomp2p.relay;

import net.tomp2p.futures.BaseFutureImpl;
import net.tomp2p.futures.FuturePeerConnection;
import net.tomp2p.peers.PeerAddress;

/**
 * PeerConnection can be retrieved from this future after it has successfully
 * been established
 * 
 * @author Raphael Voellmy
 * 
 */
public class RelayConnectionFuture extends BaseFutureImpl<RelayConnectionFuture> {

    private final PeerAddress relayAddress;
    FuturePeerConnection futurePeerConnection = null;

    public RelayConnectionFuture(PeerAddress relayAddress) {
        this.relayAddress = relayAddress;
    }

    public PeerAddress relayAddress() {
        return relayAddress;
    }

    public void futurePeerConnection(FuturePeerConnection futurePeerConnection) {
        this.futurePeerConnection = futurePeerConnection;
    }

    public FuturePeerConnection futurePeerConnection() {
        return futurePeerConnection;
    }

    public void setSuccess() {
        type = FutureType.OK;
    }

    public void done() {
        type = futurePeerConnection == null ? FutureType.FAILED : FutureType.OK;
        synchronized (lock) {
            setCompletedAndNotify();
        }
        notifyListeners();
    }

}
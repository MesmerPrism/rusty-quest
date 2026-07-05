package io.github.mesmerprism.rustyquest.qcl041;

import java.net.Socket;

final class ReceiverSocketCandidate {
    final Socket socket;
    final boolean createdFromWifiDirectNetwork;

    ReceiverSocketCandidate(Socket socket, boolean createdFromWifiDirectNetwork) {
        this.socket = socket;
        this.createdFromWifiDirectNetwork = createdFromWifiDirectNetwork;
    }
}

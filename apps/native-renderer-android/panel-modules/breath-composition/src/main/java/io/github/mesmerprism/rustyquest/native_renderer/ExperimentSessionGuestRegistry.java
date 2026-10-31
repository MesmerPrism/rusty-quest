package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Memory-only client registry. No method invokes an owner or platform callback. */
final class ExperimentSessionGuestRegistry {
    static final long IDLE_MS=120000;
    static final int CAPACITY=8;
    static final class Guest {
        final String controller;
        final ExperimentSessionGatewayProtocol protocol;
        long lastUse;
        Guest(String controller,ExperimentSessionGatewayProtocol protocol,long now){this.controller=controller;this.protocol=protocol;lastUse=now;}
    }
    private final Map<String,Guest> guests=new LinkedHashMap<>();
    private void expire(long now){
        Iterator<Guest> iterator=guests.values().iterator();
        while(iterator.hasNext()){
            Guest guest=iterator.next();
            if(now<guest.lastUse||now-guest.lastUse>=IDLE_MS){guest.protocol.retire();iterator.remove();}
        }
    }
    synchronized Guest add(String controller,ExperimentSessionGatewayProtocol protocol,long now){
        expire(now);
        if(guests.size()>=CAPACITY)throw new SecurityException("guest-capacity");
        Guest guest=new Guest(controller,protocol,now);guests.put(controller,guest);return guest;
    }
    synchronized Guest find(String controller,long now){expire(now);return guests.get(controller);}
    synchronized void authenticated(String controller,ExperimentSessionGatewayProtocol protocol,long now){
        expire(now);Guest guest=guests.get(controller);
        if(guest==null||guest.protocol!=protocol)throw new SecurityException("guest-retired");
        guest.lastUse=now;
    }
    synchronized void clear(){for(Guest guest:guests.values())guest.protocol.retire();guests.clear();}
}

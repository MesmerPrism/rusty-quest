package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.*;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event;
import java.io.Closeable;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Dormant, explicit-start Binder adapter. Does not start a listener or dispatch app commands. */
final class ExperimentSessionHubSurfaceClient implements Closeable, ExperimentSessionHubProvider.Platform, ExperimentSessionHubLifetime.Driver {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> timers = new HashMap<>();
    private final ExperimentSessionHubProvider provider;
    private Connection connection;
    ExperimentSessionHubSurfaceClient(Context context, String channel, long epoch,
            Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> source) {
        requireMain();
        this.context = context.getApplicationContext();
        long generation = new SecureRandom().nextLong() & Long.MAX_VALUE;
        provider = new ExperimentSessionHubProvider(this, Math.max(1L, generation), channel, epoch, source);
    }
    public void start() { requireMain(); provider.start(); }
    @Override public void close() { requireMain(); provider.close(); }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("main looper required");
    }
    public long now() { return SystemClock.uptimeMillis(); }
    private final class Connection implements ServiceConnection {
        final long generation;
        final int brokerUid;
        IBinder binder;
        Messenger remote;
        final Messenger callback;
        final IBinder.DeathRecipient death;
        Connection(long generation) {
            this.generation = generation;
            try {
                brokerUid = context.getPackageManager().getApplicationInfo(
                    "io.github.mesmerprism.rustymanifold.broker", 0).uid;
                String[] packages = context.getPackageManager().getPackagesForUid(brokerUid);
                if (packages == null || packages.length != 1 || !packages[0].equals(
                    "io.github.mesmerprism.rustymanifold.broker")) throw new IllegalStateException("ambiguous broker UID");
            } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
                throw new IllegalStateException("selected broker unavailable", missing);
            }
            callback = new Messenger(new Handler(Looper.getMainLooper()) {
                @Override public void handleMessage(Message message) {
                    if (connection != Connection.this || message.sendingUid != brokerUid) return;
                    // No command handler: an empty surface cannot own an effect.
                    if (message.what != 1 && message.what != 2 && message.what != 6 && message.what != 20) return;
                    Bundle data = message.getData();
                    provider.reply(generation, message.what, data.getLong("session_generation", 0),
                        data.getString("correlation_id", ""), data.getString("broker_epoch_id", ""),
                        data.getString("error", ""), data.getString("response_json", "{}"));
                }
            });
            death = new IBinder.DeathRecipient() {
                @Override public void binderDied() {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (connection == Connection.this) provider.event(Event.binderDied(generation, now()));
                        }
                    });
                }
            };
        }
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (connection != this) return;
            this.binder = binder; remote = new Messenger(binder);
            provider.event(Event.connected(generation, now()));
        }
        public void onServiceDisconnected(ComponentName name) {
            if (connection == this) { remote = null; provider.event(Event.disconnected(generation, now())); }
        }
        public void onNullBinding(ComponentName name) {
            if (connection == this) provider.event(Event.nullBinding(generation, now()));
        }
        public void onBindingDied(ComponentName name) {
            if (connection == this) provider.event(Event.bindingDied(generation, now()));
        }
    }
    public void bind(long generation) {
        Connection next;
        try { next = new Connection(generation); }
        catch (RuntimeException unavailable) { provider.event(Event.bindReturned(generation, false, now())); return; }
        connection = next;
        boolean accepted;
        try { accepted = context.bindService(new Intent().setComponent(new ComponentName(
            "io.github.mesmerprism.rustymanifold.broker",
            "io.github.mesmerprism.rustymanifold.broker.ConnectionHubAdmissionService")), next, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException denied) { accepted = false; }
        provider.event(Event.bindReturned(generation, accepted, now()));
    }
    public void link(long generation) {
        Connection current = connection;
        if (current == null || current.generation != generation || current.binder == null) {
            provider.event(Event.binderDied(generation, now())); return;
        }
        try { current.binder.linkToDeath(current.death, 0); provider.event(Event.deathLinked(generation, now())); }
        catch (RemoteException denied) { provider.event(Event.binderDied(generation, now())); }
    }
    public void unlink(long generation) {
        Connection current = connection;
        if (current != null && current.generation == generation && current.binder != null)
            current.binder.unlinkToDeath(current.death, 0);
    }
    public void unbind(long generation) {
        Connection current = connection;
        if (current == null || current.generation != generation) return;
        connection = null;
        try { context.unbindService(current); } catch (IllegalArgumentException alreadyUnbound) { }
        for (Runnable timer : timers.values()) handler.removeCallbacks(timer);
        timers.clear();
    }
    public boolean send(long generation, int what, Map<String, Object> fields) {
        Connection current = connection;
        if (current == null || current.generation != generation || current.remote == null) return false;
        Bundle data = new Bundle();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (entry.getValue() instanceof Long) data.putLong(entry.getKey(), (Long) entry.getValue());
            else if (entry.getValue() instanceof String) data.putString(entry.getKey(), (String) entry.getValue());
            else throw new IllegalArgumentException("closed Binder field type");
        }
        Message message = Message.obtain(null, what); message.setData(data); message.replyTo = current.callback;
        try { current.remote.send(message); return true; } catch (RemoteException unavailable) { return false; }
    }
    public void schedule(String key, long deadline, Runnable task) {
        cancel(key);
        Runnable timer = new Runnable() {
            public void run() {
                if (timers.get(key) != this) return;
                timers.remove(key); task.run();
            }
        };
        timers.put(key, timer); handler.postDelayed(timer, Math.max(0L, deadline - now()));
    }
    public void cancel(String key) { Runnable timer = timers.remove(key); if (timer != null) handler.removeCallbacks(timer); }
}
